package com.android.grafika.day13;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import com.android.grafika.VideoEncoderCore;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 管理 Day13 录制视频的相册输出、发布和失败清理。 */
final class Day13VideoOutput {
    private static final String TAG = "Day13VideoOutput";
    private static final String MIME_TYPE = "video/mp4";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final String mDisplayName;
    private Uri mUri;
    private ParcelFileDescriptor mDescriptor;
    private File mLegacyFile;
    private boolean mPublished;

    private Day13VideoOutput(Context context) {
        mContext = context.getApplicationContext();
        mResolver = mContext.getContentResolver();
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        mDisplayName = "day13_" + time + ".mp4";
    }

    static Day13VideoOutput create(Context context) throws IOException {
        Day13VideoOutput output = new Day13VideoOutput(context);
        try {
            output.prepare();
            return output;
        } catch (IOException | RuntimeException e) {
            output.discard();
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    private void prepare() throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, mDisplayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE);
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Grafika");
            values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
            // 完成 MP4 封装之前，相册应用不能读取这条记录。
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            mUri = mResolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
            );
            if (mUri == null) {
                throw new IOException("无法创建相册视频记录");
            }
            mDescriptor = mResolver.openFileDescriptor(mUri, "rw");
            if (mDescriptor == null) {
                throw new IOException("无法打开相册视频文件");
            }
        } else {
            // Android 9 及以下使用公共目录，调用前需要取得写入存储权限。
            File directory = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "Grafika"
            );
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("无法创建公共视频目录");
            }
            File file = new File(directory, mDisplayName);
            if (!file.createNewFile()) {
                throw new IOException("视频文件已存在");
            }
            mLegacyFile = file;
        }
    }

    VideoEncoderCore createEncoder(int width, int height, int bitRate) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new VideoEncoderCore(width, height, bitRate, mDescriptor.getFileDescriptor());
        }
        return new VideoEncoderCore(width, height, bitRate, mLegacyFile);
    }

    /** 必须在编码器排空且 MediaMuxer 完成封装、释放后调用。 */
    void publish() throws IOException {
        closeDescriptor();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (mResolver.update(mUri, values, null, null) != 1) {
                throw new IOException("发布相册视频失败");
            }
        } else {
            MediaScannerConnection.scanFile(mContext,
                    new String[]{mLegacyFile.getAbsolutePath()},
                    new String[]{MIME_TYPE}, null);
        }
        mPublished = true;
    }

    String getDisplayName() {
        return mDisplayName;
    }

    /** 录制失败或没有有效帧时，移除未完成的视频及其相册记录。 */
    void discard() {
        if (mPublished) {
            return;
        }
        try {
            closeDescriptor();
        } catch (IOException e) {
            Log.e(TAG, "关闭相册视频文件失败", e);
        }
        if (mUri != null) {
            try {
                mResolver.delete(mUri, null, null);
            } catch (RuntimeException e) {
                Log.e(TAG, "清理未完成的相册记录失败", e);
            }
            mUri = null;
        }
        if (mLegacyFile != null) {
            if (mLegacyFile.exists() && !mLegacyFile.delete()) {
                Log.w(TAG, "清理未完成的视频失败: " + mLegacyFile);
            }
            mLegacyFile = null;
        }
    }

    private void closeDescriptor() throws IOException {
        if (mDescriptor != null) {
            try {
                mDescriptor.close();
            } finally {
                mDescriptor = null;
            }
        }
    }
}
