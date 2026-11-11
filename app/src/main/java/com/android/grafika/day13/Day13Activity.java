package com.android.grafika.day13;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Bundle;
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
public class Day13Activity extends Activity implements SurfaceHolder.Callback {
    private static final int REQUEST_CAMERA = 1301;

    private SurfaceHolder mSurfaceHolder;
    private Day13RenderThread mRenderThread;
    private Button mRecordButton;
    private TextView mStatusView;
    private boolean mRecording;
    private int mLensFacing = CameraCharacteristics.LENS_FACING_BACK;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day13);

        SurfaceView preview = findViewById(R.id.activity13_preview);
        mSurfaceHolder = preview.getHolder();
        mSurfaceHolder.addCallback(this);

        mRecordButton = findViewById(R.id.activity13_record);
        mStatusView = findViewById(R.id.activity13_status);
        mRecordButton.setOnClickListener(v -> {
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

        mRenderThread = new Day13RenderThread(this, mLensFacing);
        mRenderThread.start();
        mRenderThread.waitUntilReady();
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
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate();
        }
    }

    void showStatus(final String text) {
        runOnUiThread(() -> mStatusView.setText(text));
    }

    /** 在独立线程中拥有 Camera2、EGL、SurfaceTexture 和编码器。 */
}
