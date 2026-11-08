package com.android.grafika.day09

import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceProcessor
import androidx.core.util.Consumer
import java.util.concurrent.Executor

class CameraFilterEffect(
    executor: Executor,
    processor: SurfaceProcessor
) : CameraEffect(
    // CameraX 1.4.2 的 SurfaceProcessor 目标组合要求包含 VIDEO_CAPTURE。
    PREVIEW or VIDEO_CAPTURE or IMAGE_CAPTURE,
    executor,
    processor,
    Consumer { error ->
        Log.e("GrayScaleCameraEffect", "滤镜处理失败", error)
    }
)
