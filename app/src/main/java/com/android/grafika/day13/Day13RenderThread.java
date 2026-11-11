package com.android.grafika.day13;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import com.android.grafika.VideoEncoderCore;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.io.IOException;

/**
 * Day13 的 EGL/OpenGL 渲染和 MediaCodec 输出线程。
 */
public final class Day13RenderThread extends Thread
        implements SurfaceTexture.OnFrameAvailableListener {
        private static final String TAG = "Day13RenderThread";
        private static final int MSG_SURFACE_AVAILABLE = 1;
        private static final int MSG_SURFACE_CHANGED = 2;
        private static final int MSG_SURFACE_DESTROYED = 3;
        private static final int MSG_FRAME_AVAILABLE = 4;
        private static final int MSG_SET_RECORDING = 5;
        private static final int MSG_SWITCH_CAMERA = 6;
        private static final int MSG_SHUTDOWN = 7;
        private static final int MSG_DISPLAY_ROTATION_CHANGED = 8;

        private final Day13Activity mActivity;
        private final Context mContext;
        private final Object mReadyLock = new Object();
        private int mLensFacing;

        private Handler mHandler;
        private boolean mReady;
        private boolean mDone;

        private EglCore mEglCore;
        private android.opengl.EGLSurface mPbuffer;
        private WindowSurface mPreviewWindow;
        private int mPreviewWidth;
        private int mPreviewHeight;

        private FullFrameRect mFullFrame;
        private int mTextureId;
        private SurfaceTexture mCameraTexture;
        private Surface mCameraSurface;
        private final float[] mSurfaceTextureMatrix = new float[16];
        private final float[] mDisplayRotationMatrix = new float[16];
        private final float[] mFinalTextureMatrix = new float[16];

        private Day13CameraController mCameraController;
        private Size mCameraSize;
        private int mSensorOrientation;
        private boolean mFrontCamera;
        private int mDisplayRotation;

        private boolean mFramePending;
        private boolean mHasSubmittedFrames;
        private boolean mRecordingFailed;
        private VideoEncoderCore mVideoEncoder;
        private WindowSurface mEncoderWindow;
        private int mEncoderWidth;
        private int mEncoderHeight;
        private Day13VideoOutput mVideoOutput;

        Day13RenderThread(Day13Activity activity, int lensFacing, int displayRotation) {
            super("Day13-Camera2-GL");
            mActivity = activity;
            mContext = activity.getApplicationContext();
            mLensFacing = lensFacing;
            mDisplayRotation = displayRotation;
        }

        void waitUntilReady() {
            synchronized (mReadyLock) {
                while (!mReady) {
                    try {
                        mReadyLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        @Override
        public void run() {
            Looper.prepare();
            mHandler = new RenderHandler(this);
            synchronized (mReadyLock) {
                mReady = true;
                mReadyLock.notifyAll();
            }

            try {
                initGl();
                Looper.loop();
            } catch (Throwable t) {
                Log.e(TAG, "Day13 渲染线程异常", t);
                mActivity.showStatus("渲染失败: " + t.getClass().getSimpleName());
            } finally {
                releaseAll();
            }
        }

        private void initGl() {
            // 使用可录制 EGLConfig，使同一个 context 可以绘制到编码器输入 Surface。
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            mPbuffer = mEglCore.createOffscreenSurface(1, 1);
            mEglCore.makeCurrent(mPbuffer);

            mFullFrame = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT_BW)
            );
            mTextureId = mFullFrame.createTextureObject();
            mCameraTexture = new SurfaceTexture(mTextureId);
            mCameraTexture.setOnFrameAvailableListener(this, mHandler);
            mCameraSurface = new Surface(mCameraTexture);
            mCameraController = new Day13CameraController(
                    mContext,
                    mHandler,
                    mCameraTexture,
                    mCameraSurface,
                    new Day13CameraController.Listener() {
                        @Override
                        public void onCameraReady(
                                Size size,
                                int sensorOrientation,
                                boolean frontCamera
                        ) {
                            mCameraSize = size;
                            mSensorOrientation = sensorOrientation;
                            mFrontCamera = frontCamera;
                            mActivity.showStatus("相机已就绪");
                        }

                        @Override
                        public void onCameraError(String message, Throwable cause) {
                            if (cause == null) {
                                Log.e(TAG, message);
                            } else {
                                Log.e(TAG, message, cause);
                            }
                            mActivity.showStatus(message);
                        }
                    }
            );
            mActivity.showStatus("等待相机画面");
        }

        void surfaceAvailable(Surface surface, int width, int height) {
            post(MSG_SURFACE_AVAILABLE, new SurfaceMessage(surface, width, height));
        }

        void surfaceChanged(int width, int height) {
            post(MSG_SURFACE_CHANGED, new int[]{width, height});
        }

        void surfaceDestroyed() {
            post(MSG_SURFACE_DESTROYED, null);
        }

        void setRecording(boolean recording) {
            post(MSG_SET_RECORDING, recording);
        }

        void switchCamera(int lensFacing) {
            post(MSG_SWITCH_CAMERA, lensFacing);
        }

        void setDisplayRotation(int displayRotation) {
            post(MSG_DISPLAY_ROTATION_CHANGED, displayRotation);
        }

        void shutdown() {
            post(MSG_SHUTDOWN, null);
        }

        private void post(int what, Object value) {
            Handler handler = mHandler;
            if (handler != null) {
                handler.obtainMessage(what, value).sendToTarget();
            }
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            mFramePending = true;
            if (mHandler != null) {
                mHandler.sendEmptyMessage(MSG_FRAME_AVAILABLE);
            }
        }

        private void handleSurfaceAvailable(SurfaceMessage message) {
            if (mPreviewWindow != null) {
                mPreviewWindow.release();
            }
            mPreviewWindow = new WindowSurface(mEglCore, message.surface, false);
            mPreviewWindow.makeCurrent();
            if (message.width > 0 && message.height > 0) {
                mPreviewWidth = message.width;
                mPreviewHeight = message.height;
            } else {
                mPreviewWidth = mPreviewWindow.getWidth();
                mPreviewHeight = mPreviewWindow.getHeight();
            }
            startCameraIfNeeded(mLensFacing);
        }

        private void handleSurfaceChanged(int[] size) {
            mPreviewWidth = size[0];
            mPreviewHeight = size[1];
        }

        private void handleSurfaceDestroyed() {
            stopCamera();
            if (mPreviewWindow != null) {
                mPreviewWindow.release();
                mPreviewWindow = null;
            }
        }

        private void handleFrame() {
            if (!mFramePending || mCameraTexture == null) {
                return;
            }
            mFramePending = false;
            try {
                mCameraTexture.updateTexImage();
                mCameraTexture.getTransformMatrix(mSurfaceTextureMatrix);
                drawFrame(mCameraTexture.getTimestamp());
            } catch (RuntimeException e) {
                Log.e(TAG, "处理相机帧失败", e);
                if (mVideoEncoder != null) {
                    mRecordingFailed = true;
                    stopRecording();
                }
            }
        }

        private void drawFrame(long timestampNanos) {
            if (mPreviewWindow == null || mCameraSize == null) {
                return;
            }

            buildTextureMatrix();

            if (mVideoEncoder != null) {
                // 在提交下一帧前先排空编码器，降低输入 Surface 背压。
                mVideoEncoder.drainEncoder(false);
            }

            mPreviewWindow.makeCurrent();
            drawToWindow(
                    mPreviewWindow,
                    mPreviewWidth,
                    mPreviewHeight,
                    getDisplaySourceWidth(),
                    getDisplaySourceHeight(),
                    false
            );

            if (mEncoderWindow != null && mVideoEncoder != null) {
                mEncoderWindow.makeCurrent();
                drawToWindow(
                        mEncoderWindow,
                        mEncoderWidth,
                        mEncoderHeight,
                        getDisplaySourceWidth(),
                        getDisplaySourceHeight(),
                        true
                );
                mEncoderWindow.setPresentationTime(timestampNanos);
                if (!mEncoderWindow.swapBuffers()) {
                    throw new IllegalStateException("向编码器提交画面失败");
                }
                mHasSubmittedFrames = true;
                mVideoEncoder.drainEncoder(false);
            }
        }

        private void drawToWindow(
                WindowSurface window,
                int targetWidth,
                int targetHeight,
                int sourceWidth,
                int sourceHeight,
                boolean encoder
        ) {
            if (targetWidth <= 0 || targetHeight <= 0) {
                return;
            }
            setFillViewport(targetWidth, targetHeight, sourceWidth, sourceHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            mFullFrame.drawFrame(mTextureId, mFinalTextureMatrix);
            if (!encoder) {
                window.swapBuffers();
            }
        }

        private void setFillViewport(
                int targetWidth,
                int targetHeight,
                int sourceWidth,
                int sourceHeight
        ) {
            float sourceAspect = sourceWidth / (float) sourceHeight;
            float targetAspect = targetWidth / (float) targetHeight;
            int width;
            int height;
            if (sourceAspect > targetAspect) {
                width = Math.round(targetHeight * sourceAspect);
                height = targetHeight;
            } else {
                width = targetWidth;
                height = Math.round(targetWidth / sourceAspect);
            }
            GLES20.glViewport(
                    (targetWidth - width) / 2,
                    (targetHeight - height) / 2,
                    width,
                    height
            );
        }

        private void buildTextureMatrix() {
            // Camera2 的 SurfaceTexture 矩阵已包含传感器旋转和前置镜像，不能重复叠加。
            // 这里只绕纹理中心补偿屏幕旋转；采样坐标使用显示旋转的负角度。
            int rotation = -rotationToDegrees(mDisplayRotation);
            Matrix.setIdentityM(mDisplayRotationMatrix, 0);
            Matrix.translateM(mDisplayRotationMatrix, 0, 0.5f, 0.5f, 0f);
            Matrix.rotateM(mDisplayRotationMatrix, 0, rotation, 0f, 0f, 1f);
            Matrix.translateM(mDisplayRotationMatrix, 0, -0.5f, -0.5f, 0f);
            Matrix.multiplyMM(
                    mFinalTextureMatrix,
                    0,
                    mSurfaceTextureMatrix,
                    0,
                    mDisplayRotationMatrix,
                    0
            );
        }

        private int getCameraToDisplayRotation() {
            // 传感器相对屏幕的角度只用于计算显示宽高，不再用于追加纹理旋转。
            int displayDegrees = rotationToDegrees(mDisplayRotation);
            if (mFrontCamera) {
                return (mSensorOrientation + displayDegrees) % 360;
            }
            return (mSensorOrientation - displayDegrees + 360) % 360;
        }

        private int getDisplaySourceWidth() {
            if (mCameraSize == null) {
                return 1;
            }
            return isQuarterTurn(getCameraToDisplayRotation())
                    ? mCameraSize.getHeight() : mCameraSize.getWidth();
        }

        private int getDisplaySourceHeight() {
            if (mCameraSize == null) {
                return 1;
            }
            return isQuarterTurn(getCameraToDisplayRotation())
                    ? mCameraSize.getWidth() : mCameraSize.getHeight();
        }

        private void handleSetRecording(boolean recording) {
            if (recording == (mVideoEncoder != null)) {
                return;
            }
            if (recording) {
                startRecording();
            } else {
                stopRecording();
            }
        }

        private void startRecording() {
            if (mCameraSize == null) {
                mActivity.showRecordingState(false, "相机尚未准备好");
                return;
            }
            mEncoderWidth = getDisplaySourceWidth();
            mEncoderHeight = getDisplaySourceHeight();
            mEncoderWidth &= ~1;
            mEncoderHeight &= ~1;
            mHasSubmittedFrames = false;
            mRecordingFailed = false;
            try {
                mVideoOutput = Day13VideoOutput.create(mContext);
                mVideoEncoder = mVideoOutput.createEncoder(
                        mEncoderWidth,
                        mEncoderHeight,
                        4_000_000
                );
                mEncoderWindow = new WindowSurface(
                        mEglCore,
                        mVideoEncoder.getInputSurface(),
                        false
                );
                mActivity.showRecordingState(true, "录制中");
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "创建视频编码器失败", e);
                releaseEncoder();
                if (mVideoOutput != null) {
                    mVideoOutput.discard();
                    mVideoOutput = null;
                }
                mActivity.showRecordingState(false, "录制启动失败");
            }
        }

        private void stopRecording() {
            if (mVideoEncoder == null) {
                return;
            }
            boolean finalized = mHasSubmittedFrames && !mRecordingFailed;
            if (finalized) {
                try {
                    mVideoEncoder.drainEncoder(true);
                    finalized = mVideoEncoder.hasEncodedFrames();
                } catch (RuntimeException e) {
                    Log.e(TAG, "结束编码失败", e);
                    finalized = false;
                }
            }
            // MP4 的尾部索引在 MediaMuxer.stop() 时写入，成功封装后才能发布到相册。
            finalized = releaseEncoder() && finalized;
            Day13VideoOutput output = mVideoOutput;
            mVideoOutput = null;
            if (output == null) {
                mActivity.showRecordingState(false, "保存失败");
                return;
            }
            if (!finalized) {
                output.discard();
                mActivity.showRecordingState(false,
                        mHasSubmittedFrames ? "录制失败，未保存视频" : "未录制到有效画面");
                return;
            }
            try {
                output.publish();
                mActivity.showRecordingState(false, "已保存到相册: " + output.getDisplayName());
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "保存视频到相册失败", e);
                output.discard();
                mActivity.showRecordingState(false, "保存到相册失败");
            }
        }

        private boolean releaseEncoder() {
            boolean success = true;
            if (mEncoderWindow != null) {
                try {
                    mEglCore.makeCurrent(mPbuffer);
                    mEncoderWindow.release();
                } catch (RuntimeException e) {
                    Log.e(TAG, "释放编码器 EGLSurface 失败", e);
                    success = false;
                } finally {
                    mEncoderWindow = null;
                }
            }
            if (mVideoEncoder != null) {
                try {
                    mVideoEncoder.release();
                } catch (RuntimeException e) {
                    Log.e(TAG, "释放视频编码器失败", e);
                    success = false;
                } finally {
                    // 编码器输入 Surface 由本线程持有，连 EGL 窗口创建失败的情况也要释放。
                    mVideoEncoder.getInputSurface().release();
                    mVideoEncoder = null;
                }
            }
            return success;
        }

        private void handleSwitchCamera(int lensFacing) {
            mLensFacing = lensFacing;
            stopCamera();
            startCameraIfNeeded(lensFacing);
        }

        private void startCameraIfNeeded(int lensFacing) {
            if (mCameraController != null && mPreviewWindow != null) {
                mCameraController.start(lensFacing);
            }
        }

        private void stopCamera() {
            if (mCameraController != null) {
                mCameraController.stop();
            }
            mCameraSize = null;
        }

        private static boolean isQuarterTurn(int rotation) {
            return rotation == 90 || rotation == 270;
        }

        private static int rotationToDegrees(int rotation) {
            switch (rotation) {
                case Surface.ROTATION_90:
                    return 90;
                case Surface.ROTATION_180:
                    return 180;
                case Surface.ROTATION_270:
                    return 270;
                default:
                    return 0;
            }
        }

        private void releaseAll() {
            stopRecording();
            stopCamera();
            if (mCameraController != null) {
                mCameraController.release();
                mCameraController = null;
            }
            if (mEglCore != null && mPbuffer != null) {
                mEglCore.makeCurrent(mPbuffer);
            }
            if (mCameraSurface != null) {
                mCameraSurface.release();
                mCameraSurface = null;
            }
            if (mCameraTexture != null) {
                mCameraTexture.release();
                mCameraTexture = null;
            }
            if (mPreviewWindow != null) {
                mPreviewWindow.release();
                mPreviewWindow = null;
            }
            if (mFullFrame != null) {
                mFullFrame.release(true);
                mFullFrame = null;
            }
            if (mEglCore != null) {
                if (mPbuffer != null) {
                    mEglCore.releaseSurface(mPbuffer);
                    mPbuffer = null;
                }
                mEglCore.release();
                mEglCore = null;
            }
        }

        private static final class SurfaceMessage {
            final Surface surface;
            final int width;
            final int height;

            SurfaceMessage(Surface surface, int width, int height) {
                this.surface = surface;
                this.width = width;
                this.height = height;
            }
        }

        private static final class RenderHandler extends Handler {
            private final Day13RenderThread mThread;

            RenderHandler(Day13RenderThread thread) {
                super(Looper.myLooper());
                mThread = thread;
            }

            @Override
            public void handleMessage(@NonNull Message message) {
                switch (message.what) {
                    case MSG_SURFACE_AVAILABLE:
                        mThread.handleSurfaceAvailable((SurfaceMessage) message.obj);
                        break;
                    case MSG_SURFACE_CHANGED:
                        int[] size = (int[]) message.obj;
                        mThread.handleSurfaceChanged(size);
                        break;
                    case MSG_SURFACE_DESTROYED:
                        mThread.handleSurfaceDestroyed();
                        break;
                    case MSG_FRAME_AVAILABLE:
                        mThread.handleFrame();
                        break;
                    case MSG_SET_RECORDING:
                        mThread.handleSetRecording((Boolean) message.obj);
                        break;
                    case MSG_SWITCH_CAMERA:
                        mThread.handleSwitchCamera((Integer) message.obj);
                        break;
                    case MSG_DISPLAY_ROTATION_CHANGED:
                        mThread.mDisplayRotation = (Integer) message.obj;
                        break;
                    case MSG_SHUTDOWN:
                        mThread.mDone = true;
                        Looper.myLooper().quitSafely();
                        break;
                    default:
                        throw new IllegalArgumentException("未知渲染消息: " + message.what);
                }
            }
        }
    }
