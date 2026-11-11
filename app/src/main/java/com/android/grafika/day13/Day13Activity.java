package com.android.grafika.day13;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.android.grafika.R;

/**
 * Camera2 + OpenGL ES + MediaCodec 的独立滤镜相机示例。
 *
 * <p>数据流为：</p>
 * <pre>
 * Camera2 -> SurfaceTexture/OES -> 灰度 Shader
 *                    -> 预览 WindowSurface
 *                    -> MediaCodec 输入 Surface -> MP4
 * </pre>
 *
 * <p>所有 Camera2、SurfaceTexture、EGL 和 OpenGL 操作都在同一个渲染线程执行，避免
 * SurfaceTexture 被错误线程调用。MediaCodec 使用 Surface 输入模式，OpenGL 绘制完成后
 * 通过 eglSwapBuffers() 把同一帧提交给编码器。</p>
 */
public class Day13Activity extends Activity
        implements SurfaceHolder.Callback, DisplayManager.DisplayListener {
    private static final int REQUEST_CAMERA = 1301;
    private static final int REQUEST_VIDEO_STORAGE = 1302;

    private SurfaceHolder mSurfaceHolder;
    private SurfaceView mPreviewView;
    private DisplayManager mDisplayManager;
    private Day13RenderThread mRenderThread;
    private Button mRecordButton;
    private TextView mStatusView;
    private boolean mRecording;
    private int mLensFacing = CameraCharacteristics.LENS_FACING_BACK;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day13);

        mPreviewView = findViewById(R.id.activity13_preview);
        mSurfaceHolder = mPreviewView.getHolder();
        mSurfaceHolder.addCallback(this);
        mDisplayManager = getSystemService(DisplayManager.class);

        mRecordButton = findViewById(R.id.activity13_record);
        mStatusView = findViewById(R.id.activity13_status);
        mRecordButton.setOnClickListener(v -> {
            if (mRenderThread == null) {
                showStatus("相机尚未准备好");
                return;
            }
            // Android 10 起写入应用自己创建的 MediaStore 视频不需要存储权限。
            if (!mRecording && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_VIDEO_STORAGE);
                return;
            }
            mRecording = !mRecording;
            mRecordButton.setText(mRecording ? "停止录制" : "开始录制");
            if (mRenderThread != null) {
                mRenderThread.setRecording(mRecording);
            }
        });

        Button switchButton = findViewById(R.id.activity13_switch);
        switchButton.setOnClickListener(v -> {
            mLensFacing = mLensFacing == CameraCharacteristics.LENS_FACING_BACK
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            if (mRenderThread != null) {
                mRenderThread.switchCamera(mLensFacing);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA
            );
            return;
        }

        mRenderThread = new Day13RenderThread(this, mLensFacing, currentDisplayRotation());
        mRenderThread.start();
        mRenderThread.waitUntilReady();
        // 180° 旋转不一定重建 Activity，因此需要监听显示方向的变化。
        mDisplayManager.registerDisplayListener(this, null);
        mRenderThread.setDisplayRotation(currentDisplayRotation());
        if (mSurfaceHolder != null && mSurfaceHolder.getSurface().isValid()) {
            mRenderThread.surfaceAvailable(
                    mSurfaceHolder.getSurface(),
                    mSurfaceHolder.getSurfaceFrame().width(),
                    mSurfaceHolder.getSurfaceFrame().height()
            );
        }
    }

    @Override
    protected void onPause() {
        mDisplayManager.unregisterDisplayListener(this);
        if (mRenderThread != null) {
            mRenderThread.shutdown();
            try {
                mRenderThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mRenderThread = null;
        }
        mRecording = false;
        mRecordButton.setText("开始录制");
        super.onPause();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        mSurfaceHolder = holder;
        if (mRenderThread != null) {
            mRenderThread.surfaceAvailable(holder.getSurface(), 0, 0);
        }
    }

    @Override
    public void surfaceChanged(
            @NonNull SurfaceHolder holder,
            int format,
            int width,
            int height
    ) {
        if (mRenderThread != null) {
            mRenderThread.setDisplayRotation(currentDisplayRotation());
            mRenderThread.surfaceChanged(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (mRenderThread != null) {
            mRenderThread.surfaceDestroyed();
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
        Display display = mPreviewView.getDisplay();
        if (mRenderThread != null && display != null && display.getDisplayId() == displayId) {
            mRenderThread.setDisplayRotation(display.getRotation());
        }
    }

    private int currentDisplayRotation() {
        Display display = mPreviewView.getDisplay();
        return display == null ? Surface.ROTATION_0 : display.getRotation();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate();
        } else if (requestCode == REQUEST_VIDEO_STORAGE) {
            showStatus(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    ? "存储权限已授予，请点击开始录制"
                    : "需要存储权限才能保存视频到相册");
        }
    }

    void showRecordingState(boolean recording, String status) {
        runOnUiThread(() -> {
            mRecording = recording;
            mRecordButton.setText(recording ? "停止录制" : "开始录制");
            mStatusView.setText(status);
        });
    }

    void showStatus(final String text) {
        runOnUiThread(() -> mStatusView.setText(text));
    }
}
