package com.android.grafika.day13;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.Comparator;

/** 负责 Day13 的 Camera2 设备、输出尺寸和重复采集请求。 */
final class Day13CameraController {
    interface Listener {
        void onCameraReady(Size size, int sensorOrientation, boolean frontCamera);

        void onCameraError(String message, Throwable cause);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final SurfaceTexture mSurfaceTexture;
    private final Surface mOutputSurface;
    private final CameraManager mCameraManager;
    private final Listener mListener;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private Size mPreviewSize;

    Day13CameraController(
            Context context,
            Handler handler,
            SurfaceTexture surfaceTexture,
            Surface outputSurface,
            Listener listener
    ) {
        mContext = context;
        mHandler = handler;
        mSurfaceTexture = surfaceTexture;
        mOutputSurface = outputSurface;
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        mListener = listener;
    }

    void start(int lensFacing) {
        stop();
        try {
            String cameraId = findCameraId(lensFacing);
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = characteristics.get(
                    CameraCharacteristics.SENSOR_ORIENTATION
            );
            Integer actualFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            );
            if (sensorOrientation == null || map == null) {
                throw new IllegalStateException("Camera2 缺少方向或输出尺寸信息");
            }

            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            mPreviewSize = choosePreviewSize(sizes);
            mSurfaceTexture.setDefaultBufferSize(
                    mPreviewSize.getWidth(),
                    mPreviewSize.getHeight()
            );

            if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("没有相机权限");
            }

            final int orientation = sensorOrientation;
            final boolean front = actualFacing != null
                    && actualFacing == CameraCharacteristics.LENS_FACING_FRONT;
            mCameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    mCameraDevice = camera;
                    createCaptureSession(orientation, front);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    mCameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    mCameraDevice = null;
                    mListener.onCameraError("Camera2 打开失败: " + error, null);
                }
            }, mHandler);
        } catch (CameraAccessException | RuntimeException e) {
            mListener.onCameraError("Camera2 初始化失败", e);
        }
    }

    private void createCaptureSession(int sensorOrientation, boolean frontCamera) {
        if (mCameraDevice == null) {
            return;
        }
        try {
            mCameraDevice.createCaptureSession(
                    Arrays.asList(mOutputSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(
                                @NonNull CameraCaptureSession session
                        ) {
                            mCaptureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        mCameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_PREVIEW
                                        );
                                builder.addTarget(mOutputSurface);
                                builder.set(
                                        CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO
                                );
                                builder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                );
                                session.setRepeatingRequest(
                                        builder.build(),
                                        null,
                                        mHandler
                                );
                                mListener.onCameraReady(
                                        mPreviewSize,
                                        sensorOrientation,
                                        frontCamera
                                );
                            } catch (CameraAccessException e) {
                                mListener.onCameraError("启动 Camera2 预览失败", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession session
                        ) {
                            mListener.onCameraError("相机输出配置失败", null);
                        }
                    },
                    mHandler
            );
        } catch (CameraAccessException e) {
            mListener.onCameraError("创建 Camera2 会话失败", e);
        }
    }

    void stop() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        mPreviewSize = null;
    }

    void release() {
        stop();
    }

    private String findCameraId(int lensFacing) throws CameraAccessException {
        for (String id : mCameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == lensFacing) {
                return id;
            }
        }
        throw new IllegalStateException("没有找到目标摄像头");
    }

    private static Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            throw new IllegalStateException("没有可用的相机输出尺寸");
        }
        return Arrays.stream(sizes)
                .filter(size -> size.getWidth() <= 1920 && size.getHeight() <= 1080)
                .min(Comparator.comparingDouble(Day13CameraController::sizeScore))
                .orElse(sizes[0]);
    }

    private static double sizeScore(Size size) {
        double aspect = size.getWidth() / (double) size.getHeight();
        double area = size.getWidth() * (double) size.getHeight();
        return Math.abs(aspect - (16.0 / 9.0)) * 10_000_000
                + Math.abs(area - 1280.0 * 720.0);
    }
}
