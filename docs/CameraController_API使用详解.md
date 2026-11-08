# CameraController API 使用详解

本文根据本项目 CameraX `1.4.2` 依赖及 `androidx/camera/view/CameraController.java` 整理，说明 `CameraController` / `LifecycleCameraController` 的初始化、配置和运行时控制方式。CameraX 不同版本间可能存在 API 差异，使用时应以项目实际依赖版本为准。

## 1. CameraController 是什么

`CameraController` 是 CameraX 的高层控制器，将相机初始化、UseCase 创建与绑定、旋转处理、预览触摸交互等操作封装起来。它必须配合 `PreviewView` 使用。

Controller 内部管理以下 UseCase：

| UseCase | 用途 | 默认状态 |
| --- | --- | --- |
| Preview | 在 `PreviewView` 显示相机画面 | 始终启用 |
| ImageCapture | 拍照 | 启用 |
| ImageAnalysis | 将相机帧交给应用分析 | 启用 |
| VideoCapture | 录像 | 禁用 |

常见实现类是 `LifecycleCameraController`。它使用 `LifecycleOwner` 管理相机生命周期；应用通常不必直接创建 `ProcessCameraProvider`。

```kotlin
private lateinit var cameraController: LifecycleCameraController

private fun startCamera() {
    cameraController = LifecycleCameraController(this).apply {
        // 在绑定之前配置，减少相机重新配置导致的预览中断。
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        bindToLifecycle(this@CameraActivity)
    }

    binding.previewView.controller = cameraController
}
```

`PreviewView` 通过 `controller` 属性连接预览。绑定生命周期和设置 `PreviewView.controller` 是两个不同步骤：前者指定相机跟随哪个生命周期，后者提供预览显示界面。

> 项目 `Day09Activity` 示例中既调用了 `bindToLifecycle()`，又将 Controller 赋给 `PreviewView.controller`，流程核心就是这两步。实际代码建议只在一个明确位置绑定一次，并在权限通过后再创建和启动相机。

## 2. 初始化和线程规则

### 2.1 获取初始化结果

```java
ListenableFuture<Void> getInitializationFuture()
```

返回相机初始化的 `ListenableFuture`。可用它在初始化成功后检查相机是否存在，或更新 UI。初始化 Future 失败通常意味着当前设备相机不可用；Future 成功不代表预览界面已经连接。

```kotlin
cameraController.initializationFuture.addListener({
    val hasFrontCamera = cameraController.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
    // 根据检查结果更新界面
}, ContextCompat.getMainExecutor(this))
```

如果直接在启动时连接 PreviewView，并不一定需要等待该 Future；只有需要主动检查设备能力时才常用。

### 2.2 主线程调用

大部分配置 API 和运行时控制 API 都要求在主线程调用。相机帧分析则应交给单独的 Executor，避免阻塞主线程。

### 2.3 配置时机

修改分辨率、拍照模式、分析器队列、录像质量等配置可能导致 UseCase 重建或相机重新绑定，造成短暂预览中断。尽量在 `bindToLifecycle()` / `PreviewView.controller` 之前完成初始配置；确实需要运行时切换时，应将重新配置视为一次相机状态变更。

## 3. UseCase 启用与查询

### 3.1 `setEnabledUseCases()`

```java
void setEnabledUseCases(int enabledUseCases)
```

参数是以下标志的按位或组合：

| 常量 | 含义 |
| --- | --- |
| `CameraController.IMAGE_CAPTURE` | 启用拍照 |
| `CameraController.IMAGE_ANALYSIS` | 启用图像分析 |
| `CameraController.VIDEO_CAPTURE` | 启用录像 |

```kotlin
// 只拍照
cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE)

// 拍照并分析
cameraController.setEnabledUseCases(
    CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS
)

// 录像时通常只启用录像，结束后再切回拍照/分析
cameraController.setEnabledUseCases(CameraController.VIDEO_CAPTURE)
```

Preview 始终开启，不需要也不能作为标志传入。默认值是 `IMAGE_CAPTURE | IMAGE_ANALYSIS`，录像默认关闭。多个 UseCase 同时运行会竞争相机的分辨率、帧率及硬件资源；尤其低端设备上，不建议无需求地同时开启录像与其他 UseCase。

可用以下方法查询开关：

```java
boolean isImageCaptureEnabled()
boolean isImageAnalysisEnabled()
boolean isVideoCaptureEnabled()
```

调用未启用的 UseCase 对应操作（例如未开启拍照时调用 `takePicture()`）会失败。

## 4. 相机选择与设备检查

### 4.1 选择前置或后置摄像头

```java
void setCameraSelector(CameraSelector selector)
CameraSelector getCameraSelector()
```

默认选择后置摄像头。切换摄像头会重新绑定当前启用的 UseCase。

```kotlin
cameraController.cameraSelector =
    if (cameraController.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
        CameraSelector.DEFAULT_FRONT_CAMERA
    } else {
        CameraSelector.DEFAULT_BACK_CAMERA
    }
```

前置镜头、后置镜头之外，`CameraSelector` 还可以通过过滤条件组合选择设备。若指定的相机无法满足当前启用的 UseCase，重新绑定可能抛出异常。

### 4.2 检查设备是否有目标相机

```java
boolean hasCamera(CameraSelector selector)
```

必须等 `getInitializationFuture()` 完成后再调用。相机尚未初始化时会抛出 `IllegalStateException`；如果设备没有匹配相机则返回 `false`。

### 4.3 获取底层信息与控制对象

```java
@Nullable CameraInfo getCameraInfo()
@Nullable CameraControl getCameraControl()
```

相机尚未就绪时返回 `null`。对于 Controller 已直接提供的功能，优先使用 Controller 方法（如 `setLinearZoom()`、`enableTorch()`）；仅在需要 CameraX 更底层能力时再访问这两个对象。切换相机后，底层对象代表当前相机，不要长期缓存旧对象。

## 5. Preview 配置

### 5.1 分辨率选择

推荐使用 `ResolutionSelector`：

```java
void setPreviewResolutionSelector(@Nullable ResolutionSelector selector)
ResolutionSelector getPreviewResolutionSelector()
```

传入 `null` 表示使用 Preview 默认策略。选择器指定的是期望值/策略，实际输出仍受相机能力、其他并发 UseCase、屏幕尺寸和设备限制影响。默认预览分辨率会参考设备屏幕尺寸，并受 1080p 上限约束。

```kotlin
val previewResolution = ResolutionSelector.Builder()
    .setAspectRatioStrategy(
        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    )
    .build()

cameraController.setPreviewResolutionSelector(previewResolution)
```

根据目标场景，也可以通过 `ResolutionSelector.Builder` 配置分辨率策略或回退规则。预览画面的裁切与 ViewPort/PreviewView 显示比例有关，不能仅凭设置的比例推断实际输出尺寸。

### 5.2 旧版 `OutputSize` 接口

```java
@Deprecated void setPreviewTargetSize(OutputSize size)
@Deprecated OutputSize getPreviewTargetSize()
```

`OutputSize` 只能表达 4:3、16:9 或具体 `Size`。例如旧代码可以使用 `CameraController.OutputSize(AspectRatio.RATIO_16_9)` 或 `CameraController.OutputSize(Size(1920, 1080))`。这些 API 已废弃，新代码优先使用 `ResolutionSelector`。如果 `ResolutionSelector` 和 `OutputSize` 同时设置，前者优先。

### 5.3 动态范围

源码中存在 `setPreviewDynamicRange()` / `getPreviewDynamicRange()`，用于 SDR/HDR 动态范围设置；但本项目对应实现标注为 `@RestrictTo(LIBRARY_GROUP)`，属于库组受限 API，不建议普通应用直接调用。应用应先确认所用 CameraX 版本是否将该能力公开，并查询相机支持的动态范围。

## 6. ImageCapture 拍照

### 6.1 闪光灯模式

```java
void setImageCaptureFlashMode(int flashMode)
int getImageCaptureFlashMode()
```

常用模式：

| 常量 | 含义 |
| --- | --- |
| `ImageCapture.FLASH_MODE_OFF` | 关闭闪光灯，默认值 |
| `ImageCapture.FLASH_MODE_ON` | 拍照时开启闪光灯 |
| `ImageCapture.FLASH_MODE_AUTO` | 根据场景自动决定 |
| `ImageCapture.FLASH_MODE_SCREEN` | 前置拍照时通过屏幕补光（版本支持时） |

`FLASH_MODE_SCREEN` 需要将有效的 Window 设置给相应的 `PreviewView`/`ScreenFlashView`，且仅适用于前置相机；切换到不适用的摄像头时应切换闪光灯模式：

```kotlin
binding.previewView.setScreenFlashWindow(window)
cameraController.imageCaptureFlashMode = ImageCapture.FLASH_MODE_SCREEN
```

屏幕闪光相关的 `setScreenFlashUiInfo()`、`updateScreenFlashToImageCapture()` 和 `getScreenFlashUiInfoByPriority()` 在源码中属于库内部 API，应用不应直接调用。

### 6.2 拍照延迟/画质模式

```java
void setImageCaptureMode(int mode)
int getImageCaptureMode()
```

| 模式 | 说明 |
| --- | --- |
| `ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY` | 优先缩短快门延迟 |
| `ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY` | 优先图像质量 |

选择后可能重建 ImageCapture，最好在绑定前设置。

### 6.3 拍照分辨率

```java
void setImageCaptureResolutionSelector(@Nullable ResolutionSelector selector)
ResolutionSelector getImageCaptureResolutionSelector()
```

传入 `null` 使用默认策略。源码注释指出默认分辨率策略倾向可用的最高分辨率，但最终仍受硬件与并发 UseCase 限制。旧的 `setImageCaptureTargetSize(OutputSize)` 已废弃。

### 6.4 IO Executor

```java
void setImageCaptureIoExecutor(@Nullable Executor executor)
Executor getImageCaptureIoExecutor()
```

设置 ImageCapture 内部 IO 任务的默认执行器；不设置时使用 CameraX 默认 IO Executor。它和调用 `takePicture()` 时传入的回调 Executor 不是同一个概念。修改后可能重建 UseCase。

### 6.5 执行拍照：保存到文件

```java
void takePicture(
    ImageCapture.OutputFileOptions options,
    Executor executor,
    ImageCapture.OnImageSavedCallback callback)
```

拍照结果由 CameraX 保存到指定文件、MediaStore 等目标，回调报告成功或失败：

```kotlin
val photoDir = File(filesDir, "photos")
if (!photoDir.exists() && !photoDir.mkdirs()) {
    throw IOException("无法创建照片目录: ${photoDir.absolutePath}")
}

val photoFile = File(
    photoDir,
    "IMG_${System.currentTimeMillis()}.jpg"
)
val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

cameraController.takePicture(
    outputOptions,
    ContextCompat.getMainExecutor(this),
    object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(
            outputFileResults: ImageCapture.OutputFileResults
        ) {
            // File 输出时 savedUri 通常为 null，应从 photoFile 获取路径。
            Log.d(TAG, "照片已保存: ${photoFile.absolutePath}")
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "拍照或保存失败", exception)
        }
    }
)
```

`ImageCapture.OutputFileOptions.Builder(photoFile)` 接收一个 `java.io.File`，`photoFile` 的父目录和文件名就决定了保存位置；它不是接收路径字符串的构造器，也不会自动创建父目录。建议每次拍照生成唯一文件名，并在调用前确认目录存在且可写。保存失败（例如目录不存在、空间不足或相机已关闭）会进入 `onError()`。

选择保存路径时，可按用途创建 `photoFile`：

| 目录 | 示例 | 特点 |
| --- | --- | --- |
| 应用内部私有目录 | `File(filesDir, "photos/IMG_xxx.jpg")` | 不需要存储权限；应用卸载时删除 |
| 应用专属外部图片目录 | `File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "IMG_xxx.jpg")` | 通常不需要存储权限；应用卸载时删除；目录可能不可用，需判空 |
| 公共相册 | 使用 MediaStore 输出选项 | 适合让照片出现在系统相册；不要用硬编码公共存储绝对路径 |

例如，写入应用专属外部图片目录：

```kotlin
val photoDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    ?: throw IOException("外部图片目录不可用")
val photoFile = File(photoDir, "IMG_${System.currentTimeMillis()}.jpg")
val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
```

如果希望图片直接进入公共相册，应改用 `MediaStore` 构造 `OutputFileOptions`，然后把它传给同一个 `cameraController.takePicture()`：

```kotlin
val values = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
}
val outputOptions = ImageCapture.OutputFileOptions.Builder(
    contentResolver,
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    values
).build()
```

File 输出成功后用 `photoFile.absolutePath` 获取实际文件路径；MediaStore 输出则从 `outputFileResults.savedUri` 获取内容 URI。Android 10 及以上通常优先用 MediaStore 管理公共照片，不要把设备上的 `/storage/emulated/0/...` 等绝对路径写死在代码里。

### 6.6 执行拍照：获取内存图像

```java
void takePicture(
    Executor executor,
    ImageCapture.OnImageCapturedCallback callback)
```

该重载把拍到的图像以内存中的 `ImageProxy` 交给回调，不会自动保存文件。它适合拍照后立即显示缩略图、交给图像算法、上传或送入 GPU 处理；如果只是想保存照片，应优先使用 6.5 节的 `OutputFileOptions`，避免不必要的解码和内存复制。

```kotlin
private data class CapturedJpeg(
    val bytes: ByteArray,
    val rotationDegrees: Int,
    val cropRect: Rect,
    val width: Int,
    val height: Int
)

private val captureExecutor = Executors.newSingleThreadExecutor()
private val processingExecutor = Executors.newSingleThreadExecutor()
private val mainExecutor = ContextCompat.getMainExecutor(this)

cameraController.takePicture(
    captureExecutor,
    object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val captured = try {
                if (image.format != ImageFormat.JPEG) {
                    Log.e(TAG, "此示例只解码 JPEG，实际格式为 ${image.format}")
                    return
                }

                // 在关闭 ImageProxy 前，把压缩数据复制到应用自己的内存。
                val buffer = image.planes[0].buffer.duplicate()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                CapturedJpeg(
                    bytes = bytes,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                    cropRect = Rect(image.cropRect),
                    width = image.width,
                    height = image.height
                )
            } catch (e: Exception) {
                Log.e(TAG, "复制拍照数据失败", e)
                return
            } finally {
                image.close()
            }

            // 解码和旋转较耗时，放到后台线程；JPEG 字节已脱离 ImageProxy 生命周期。
            processingExecutor.execute {
                val source = BitmapFactory.decodeByteArray(
                    captured.bytes,
                    0,
                    captured.bytes.size
                ) ?: return@execute

                val displayBitmap = if (captured.rotationDegrees == 0) {
                    source
                } else {
                    val matrix = Matrix().apply {
                        postRotate(captured.rotationDegrees.toFloat())
                    }
                    Bitmap.createBitmap(
                        source,
                        0,
                        0,
                        source.width,
                        source.height,
                        matrix,
                        true
                    ).also {
                        if (it !== source) source.recycle()
                    }
                }

                mainExecutor.execute {
                    imageView.setImageBitmap(displayBitmap)
                }
            }
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "内存拍照失败", exception)
        }
    }
)
```

#### ImageProxy 里有什么

`ImageProxy` 是 CameraX 对底层图像和拍摄元数据的包装，不是 `Bitmap`。常用属性如下：

| 属性 | 用途 |
| --- | --- |
| `image.format` | 图像格式。ImageCapture 默认输出 JPEG；不同输出格式要采用相应解码方式 |
| `image.width` / `image.height` | 当前图像缓冲区尺寸 |
| `image.planes` | 图像平面及 ByteBuffer。JPEG 通常只有一个平面，内容是压缩 JPEG 字节；不要按 YUV 的三个平面处理 |
| `image.imageInfo.rotationDegrees` | 为使图像匹配 `ImageCapture` 目标方向所需的顺时针旋转角度 |
| `image.cropRect` | 当前图像中有效/应使用的裁切区域；需要与预览对齐时再应用，避免重复裁切 |
| `image.imageInfo.timestamp` | 图像时间戳，可用于与其他帧或传感器数据对时 |

要使用 JPEG 数据，读取 `planes[0].buffer`，并在 `ImageProxy.close()` 前复制到 `ByteArray`。`ByteBuffer.duplicate()` 可以避免移动 ImageProxy 自己的 Buffer 位置。`ImageProxy`、它的 Plane、ByteBuffer 都受图像缓冲区生命周期约束，关闭后不能继续访问。

示例中的 JPEG 解码会得到未按 `rotationDegrees` 旋转的 Bitmap，因此显示前要旋转一次。若送入支持方向元数据的算法或 GPU 管线，也可以传递旋转角度由下游处理，不必真的生成一张旋转后的 Bitmap。使用 `cropRect` 时检查它是否已经是完整图像范围；对齐预览裁切时只应用一次。

#### 处理方式选择

- **显示缩略图**：JPEG 字节用 `BitmapFactory.decodeByteArray()` 解码，按 `rotationDegrees` 旋转，然后在主线程更新 View。大图解码应在后台线程，并留意 Bitmap 占用的内存。
- **CPU 图像算法**：先复制需要的数据，再将 JPEG 解码为算法接受的像素格式；JPEG 压缩字节本身不是 RGB/YUV 像素矩阵。
- **GLES 后处理**：先把 JPEG 解码成 Bitmap/纹理再上传 GPU；如果目标是让实时预览、拍照和录像统一使用 GPU 滤镜，应使用文档后面的 `CameraEffect` + `SurfaceProcessor`，不要每次拍照都通过 Bitmap 中转。
- **保存照片**：直接使用 `takePicture(OutputFileOptions, ...)`，让 CameraX 写文件或 MediaStore，通常比先拿 ImageProxy 再自行编码更合适。
- **连续识别/逐帧处理**：使用 `ImageAnalysis`，而不是反复调用拍照接口。

如果选择的 ImageCapture 输出格式是 `JPEG_R`（Ultra HDR），应先检查 `image.format` 并使用能处理该格式的解码流程；不要无条件把每种格式都交给同一 JPEG/RGB 处理代码。`CameraController` 常规配置默认使用 JPEG。

#### ImageProxy 必须及时关闭

`onCaptureSuccess()` 中无论成功、格式不支持还是处理抛异常，都必须关闭 `ImageProxy`。如果同步处理只需很短时间，可以在 `try/finally` 里处理完再关闭；如果要异步处理，先复制出所需字节/像素和元数据，再关闭，然后把自有副本交给后台线程。

不要把 `ImageProxy` 或它的 `ByteBuffer` 保存到成员变量、队列或异步任务中并在关闭后继续读。忘记 `close()` 会占住相机图像缓冲区，后续拍照可能卡住或失败。每张照片通常是高分辨率图像，同时保留压缩字节、解码 Bitmap 和旋转后的 Bitmap 会明显增加内存峰值；完成后及时复用或释放不再使用的 Bitmap。

`captureExecutor` 和 `processingExecutor` 是应用自己创建的线程池，应在页面/组件不再使用时调用 `shutdown()`，不要每次拍照都新建一个 Executor。

拍照前必须启用 ImageCapture 并完成相机初始化。使用前置相机时，Controller 默认会将保存图片水平镜像，以匹配预览；应用可通过 `ImageCapture.OutputFileOptions` 的 Metadata 显式指定镜像行为。输出图像还会按 PreviewView 的视口比例裁切。

## 7. ImageAnalysis 图像分析

### 7.1 配置 Analyzer

```java
void setImageAnalysisAnalyzer(Executor executor, ImageAnalysis.Analyzer analyzer)
void clearImageAnalysisAnalyzer()
```

同一时间只允许一个 Analyzer；再次设置会替换原 Analyzer。处理应放在后台线程，并且每个 `ImageProxy` 都必须关闭。

```kotlin
private val analysisExecutor = Executors.newSingleThreadExecutor()

cameraController.setImageAnalysisAnalyzer(analysisExecutor) { image ->
    try {
        // 读取 image.format、planes、rotationDegrees 等信息并执行分析
    } finally {
        image.close()
    }
}

// 不再需要分析时
cameraController.clearImageAnalysisAnalyzer()
```

如果 Analyzer 声明了默认目标分辨率，设置或清除 Analyzer 可能触发相机重新配置。需要把分析结果坐标绘制到预览上时，可让 Analyzer 使用 `ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED`，并在实现 `updateTransform()` 时使用 Controller 提供的坐标变换。旧常量 `CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED` 已废弃，优先使用 `ImageAnalysis` 中的对应常量。

### 7.2 背压策略

```java
void setImageAnalysisBackpressureStrategy(int strategy)
int getImageAnalysisBackpressureStrategy()
```

| 策略 | 行为与用途 |
| --- | --- |
| `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` | 默认值。分析速度跟不上相机时丢弃中间旧帧，适合实时识别 |
| `ImageAnalysis.STRATEGY_BLOCK_PRODUCER` | 使用队列暂存图像；队列满后会阻塞生产者，适合必须处理连续帧的场景，但可能影响预览和其他 UseCase |

### 7.3 图像队列深度

```java
void setImageAnalysisImageQueueDepth(int depth)
int getImageAnalysisImageQueueDepth()
```

设置并行可用图像缓冲区数量，仅在 `STRATEGY_BLOCK_PRODUCER` 下生效。增大队列会增加缓冲和内存占用，并不能自动提高分析吞吐量。

### 7.4 分析分辨率

```java
void setImageAnalysisResolutionSelector(@Nullable ResolutionSelector selector)
ResolutionSelector getImageAnalysisResolutionSelector()
```

使用 `ResolutionSelector` 控制期望的分析输出尺寸。默认分辨率策略以 640×480 为界限尺寸，并按“优先回退至更高、再回退至更低”的规则寻找可用值。实际值可能因设备和其他 UseCase 而不同。

`setImageAnalysisTargetSize(OutputSize)` / `getImageAnalysisTargetSize()` 已废弃。

### 7.5 后台 Executor 与输出格式

```java
void setImageAnalysisBackgroundExecutor(@Nullable Executor executor)
Executor getImageAnalysisBackgroundExecutor()

void setImageAnalysisOutputImageFormat(int format)
int getImageAnalysisOutputImageFormat()
```

后台 Executor 用于 ImageAnalysis 内部任务；Analyzer 自己运行在哪个 Executor 由 `setImageAnalysisAnalyzer(executor, analyzer)` 的第一个参数决定。

输出格式支持：

| 格式 | 说明 |
| --- | --- |
| `ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888` | 默认格式，适合多数视觉算法和较低转换开销 |
| `ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888` | 输出 RGBA，使用更方便，但需格式转换，有额外开销 |

格式或后台 Executor 变更可能重建 ImageAnalysis。运行时更改格式时，应检查每个 `ImageProxy.format`，确认新格式已经生效。

## 8. VideoCapture 录像

### 8.1 录像质量

```java
void setVideoCaptureQualitySelector(QualitySelector selector)
QualitySelector getVideoCaptureQualitySelector()
```

通过 `QualitySelector` 请求录像质量，CameraX 会结合摄像头分辨率和编码器能力选择输出。常用质量级别包括 `Quality.UHD`、`Quality.FHD`、`Quality.HD`、`Quality.SD`；设备不支持请求质量时可配置回退策略。默认值是 `Recorder.DEFAULT_QUALITY_SELECTOR`。

修改质量可能导致当前录像结束并重建 VideoCapture，因此应在录像开始前设置。

### 8.2 镜像、动态范围、帧率

```java
void setVideoCaptureMirrorMode(int mode)
int getVideoCaptureMirrorMode()

void setVideoCaptureDynamicRange(DynamicRange dynamicRange)
DynamicRange getVideoCaptureDynamicRange()

void setVideoCaptureTargetFrameRate(Range<Integer> fpsRange)
Range<Integer> getVideoCaptureTargetFrameRate()
```

镜像模式：

- `MirrorMode.MIRROR_MODE_OFF`：不镜像，默认值。
- `MirrorMode.MIRROR_MODE_ON`：始终镜像。
- `MirrorMode.MIRROR_MODE_ON_FRONT_ONLY`：仅前置镜头镜像。

视频动态范围默认 SDR。HDR 是否能用取决于摄像头、编码器及并发流配置。帧率参数是目标范围，不保证最终输出严格等于目标；CameraX 还需考虑其他 UseCase 和设备限制。帧率支持情况可从 `CameraInfo.getSupportedFrameRateRanges()` 查询。

### 8.3 启用录像

录像 UseCase 默认关闭，调用 `startRecording()` 前必须启用：

```kotlin
cameraController.setEnabledUseCases(CameraController.VIDEO_CAPTURE)
```

`setEnabledUseCases()` 可能重新绑定相机。项目 CameraController 源码建议只在录像时启用录像 UseCase，完成后切回拍照/分析，以便恢复相应 UseCase 的资源与分辨率选择。

### 8.4 开始录像与处理事件

Controller 提供三个 `startRecording()` 重载，分别写入：

| OutputOptions | 目标 |
| --- | --- |
| `FileOutputOptions` | 应用指定文件 |
| `MediaStoreOutputOptions` | MediaStore |
| `FileDescriptorOutputOptions` | 文件描述符，要求 Android API 26+ |

每个重载还需要 `AudioConfig`、事件 Executor 和 `Consumer<VideoRecordEvent>`。项目 CameraX `1.4.2` 中，无音频配置为 `AudioConfig.AUDIO_DISABLED`；启用音频使用 `AudioConfig.create(true)`，并且必须申请并获准 `RECORD_AUDIO` 权限。

```kotlin
val outputOptions = FileOutputOptions.Builder(videoFile).build()

val recording = cameraController.startRecording(
    outputOptions,
    AudioConfig.AUDIO_DISABLED,
    ContextCompat.getMainExecutor(this),
    Consumer { event ->
        when (event) {
            is VideoRecordEvent.Start -> {
                // 录像已经开始
            }
            is VideoRecordEvent.Status -> {
                // 更新时长或文件大小等状态
            }
            is VideoRecordEvent.Finalize -> {
                // 录像结束；检查 event.error 判断是否成功
            }
        }
    }
)
```

```kotlin
// 已获 RECORD_AUDIO 运行时权限后，使用音频录制：
val audioConfig = AudioConfig.create(true)
```

若不需要音轨，使用 `AudioConfig.AUDIO_DISABLED`，不需要申请录音权限。

`startRecording()` 返回 `Recording`。用它控制本次录像：

```kotlin
recording.pause()
recording.resume()
recording.stop()
recording.close()
```

每次最多只能有一个未完成的录像。成功启动时首先收到 `VideoRecordEvent.Start`；启动失败时可能直接收到 `Finalize`。录像结束后以 `Finalize` 事件为最终结果，并检查其中的错误码。`isRecording()` 可查询当前是否有进行中的录像。

## 9. 预览手势与实时相机控制

### 9.1 双指缩放

```java
void setPinchToZoomEnabled(boolean enabled)
boolean isPinchToZoomEnabled()
```

默认开启。开启后，用户可在 `PreviewView` 上双指缩放；设备不支持缩放时效果受限。

### 9.2 点击对焦

```java
void setTapToFocusEnabled(boolean enabled)
boolean isTapToFocusEnabled()
LiveData<Integer> getTapToFocusState()
```

默认开启。用户点按 `PreviewView` 后，Controller 自动在点按位置进行对焦与测光。状态值为：

| 常量 | 含义 |
| --- | --- |
| `TAP_TO_FOCUS_NOT_STARTED` | 尚未开始 |
| `TAP_TO_FOCUS_STARTED` | 对焦处理中 |
| `TAP_TO_FOCUS_FOCUSED` | 对焦成功 |
| `TAP_TO_FOCUS_NOT_FOCUSED` | 请求完成，但仍未合焦 |
| `TAP_TO_FOCUS_FAILED` | 操作因设备限制等原因失败 |

```kotlin
cameraController.tapToFocusState.observe(this) { state ->
    // 根据 state 更新对焦提示
}
```

### 9.3 缩放比例

```java
ListenableFuture<Void> setZoomRatio(float zoomRatio)
ListenableFuture<Void> setLinearZoom(float linearZoom)
LiveData<ZoomState> getZoomState()
```

- `setZoomRatio()` 使用相机缩放倍率，合法范围由 `ZoomState.minZoomRatio` 与 `maxZoomRatio` 决定，例如 1.0 表示无数字放大。
- `setLinearZoom()` 接收 `[0.0, 1.0]`，0 为最小倍率、1 为最大倍率；视场角随线性值变化，适合滑块。
- Future 成功表示控制已应用；设备关闭或新请求覆盖旧请求时可能失败。
- 相机未就绪时，Controller 会暂存最新请求，在相机可用后应用。

```kotlin
cameraController.zoomState.observe(this) { zoomState ->
    // 更新缩放滑块或显示当前倍率
}

cameraController.setLinearZoom(0.35f)
```

### 9.4 手电筒

```java
ListenableFuture<Void> enableTorch(boolean enabled)
LiveData<Integer> getTorchState()
```

控制持续补光灯（torch），不是拍照瞬间闪光灯模式。手电筒受当前相机硬件支持情况限制；Future 可用于处理控制成功/失败，`getTorchState()` 可观察状态。相机未就绪时请求会暂存。

```kotlin
cameraController.enableTorch(true)
cameraController.torchState.observe(this) { state ->
    // TorchState.ON 或 TorchState.OFF
}
```

## 10. CameraEffect 特效

```java
void setEffects(Set<CameraEffect> effects)
void clearEffects()
```

`CameraEffect` 是 CameraX UseCase 输出管线中的帧处理节点。与在 `PreviewView` 上叠加 View/GL 画面不同，CameraEffect 处理的是相机帧，因此可让滤镜进入 Preview、拍照结果和录像文件。

### 10.1 目标 UseCase

`CameraEffect` 用位标志指出效果要接收哪些输出：

| 目标 | 常量 | 结果 |
| --- | --- | --- |
| 预览 | `CameraEffect.PREVIEW` | 滤镜结果显示在 PreviewView |
| 拍照 | `CameraEffect.IMAGE_CAPTURE` | 保存的照片包含滤镜 |
| 录像 | `CameraEffect.VIDEO_CAPTURE` | 写入录像文件的视频帧包含滤镜 |

CameraX 1.4.2 中，供 GLES 使用的 `SurfaceProcessor` 支持的常见目标组合为：

- `PREVIEW`
- `VIDEO_CAPTURE`
- `PREVIEW | VIDEO_CAPTURE`
- `PREVIEW | VIDEO_CAPTURE | IMAGE_CAPTURE`

`UseCaseGroup.Builder.addEffect()` 还允许用 `ImageProcessor` 只处理 `IMAGE_CAPTURE`。Preview、拍照和录像都要应用同一 GLES 滤镜时，使用一个 `SurfaceProcessor` 的全目标组合即可。`IMAGE_ANALYSIS` 不是 CameraEffect 的目标；分析器接收的帧不会自动带上该效果。

### 10.2 用 SurfaceProcessor 接入 GLES

`SurfaceProcessor` 是 CameraX 面向 GPU 后处理的接口，输入和输出都是 `Surface`。OpenGL ES 典型数据流如下：

```text
CameraX 相机帧
   -> SurfaceRequest 提供的输入 Surface / SurfaceTexture
   -> updateTexImage() 取得纹理帧
   -> GLES 着色器处理
   -> SurfaceOutput 提供的 EGL 窗口 Surface
   -> Preview / ImageCapture / VideoCapture
```

通常应用需要实现两个对象：

```kotlin
private class GlesCameraEffect(
    targets: Int,
    executor: Executor,
    processor: SurfaceProcessor,
    errorListener: Consumer<Throwable>
) : CameraEffect(targets, executor, processor, errorListener)
```

`CameraEffect` 的构造器是受保护的，因此应用通过继承创建自己的效果类。`executor` 是 CameraX 调用 Processor 和错误回调所用的执行器；GLES 通常依赖 EGL Context 的线程亲和性，建议使用单线程 GL Executor/HandlerThread，并在该线程创建、使用和销毁 EGL 资源。

`SurfaceProcessor` 有两个入口：

| 方法 | 职责 |
| --- | --- |
| `onInputSurface(SurfaceRequest request)` | 创建输入 `SurfaceTexture`/`Surface`，尺寸必须匹配 `request.getResolution()`，然后调用 `request.provideSurface()` |
| `onOutputSurface(SurfaceOutput output)` | 通过 `output.getSurface()` 获取输出 Surface，创建 EGL Window Surface，并将处理后的帧绘制到该目标 |

#### 输入 Surface 生命周期

在 `onInputSurface()` 中，GLES 实现通常按以下顺序处理：

1. 在 GL 线程创建 OES 输入纹理、`SurfaceTexture`，用 `request.getResolution()` 设置默认缓冲区尺寸。
2. 基于 `SurfaceTexture` 创建 Android `Surface`，通过 `request.provideSurface(surface, executor, resultListener)` 交给 CameraX。
3. 收到帧可用通知后，在 GL 线程调用 `SurfaceTexture.updateTexImage()`，读取纹理矩阵并执行 shader。
4. **只有 `provideSurface()` 的结果回调表示 CameraX 不再使用输入 Surface 后**，才释放 Surface、SurfaceTexture 和关联纹理。

CameraX 重新绑定、分辨率变化或视图重建时，可能在旧 Surface 尚未释放前发出新的 `SurfaceRequest`。实现应允许短时间内同时存在新旧输入资源，不能收到新请求就立即销毁旧 Surface。请求无法完成时及时调用 `willNotProvideSurface()`；需要替换仍有效的输入时可调用 `invalidate()` 让 CameraX 重新请求。

#### 输出 Surface 生命周期与坐标变换

`onOutputSurface()` 收到的 `SurfaceOutput` 表示 CameraX 的下游目标。典型做法是：

```kotlin
val outputSurface = surfaceOutput.getSurface(glExecutor) { event ->
    if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
        glExecutor.execute {
            // 停止向该 EGL Surface 绘制，然后关闭输出。
            destroyEglWindowSurface(surfaceOutput)
            surfaceOutput.close()
        }
    }
}
createEglWindowSurface(outputSurface)
```

示例中的 `createEglWindowSurface()` / `destroyEglWindowSurface()` 是应用自己的 EGL 管理代码。收到 `EVENT_REQUEST_CLOSE` 后必须尽快停止使用输出并调用 `SurfaceOutput.close()`；CameraX 收到 close 后才会继续完成 Surface 的释放。新输出可能在旧输出收到 close 请求前到达，所以输出对象也要按实例分别管理。

CameraX 会处理 ViewPort、目标旋转和前置镜头镜像等变换。对 SurfaceTexture 的输入纹理矩阵，绘制到输出前应使用 `SurfaceOutput.updateTransformMatrix(updated, original)` 合并 CameraX 提供的附加变换，再将矩阵传给 GLES shader；不能只依赖 `SurfaceTexture.getTransformMatrix()`，否则常见结果是画面方向、裁切或镜像错误。`SurfaceOutput.getTargets()` 可用于判断一个输出对应哪些目标。

### 10.3 Controller 接入示例

下面展示把同一个 GLES `SurfaceProcessor` 同时用于预览、拍照和录像。`GlesSurfaceProcessor` 代表应用实现的 GL 处理器：

```kotlin
val glExecutor = Executors.newSingleThreadExecutor()
val glesProcessor = GlesSurfaceProcessor(glExecutor)
val allTargets =
    CameraEffect.PREVIEW or
        CameraEffect.IMAGE_CAPTURE or
        CameraEffect.VIDEO_CAPTURE

val glesEffect = GlesCameraEffect(
    allTargets,
    glExecutor,
    glesProcessor,
    Consumer { error ->
        Log.e(TAG, "CameraX GLES 滤镜处理失败", error)
    }
)

cameraController = LifecycleCameraController(this).apply {
    setEnabledUseCases(
        CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE
    )
    setEffects(setOf(glesEffect))
    bindToLifecycle(this@CameraActivity)
}
binding.previewView.controller = cameraController
```

Preview 在 Controller 中始终启用；示例另外启用拍照和录像。若设备不支持三种输出同时绑定，应按功能切换 `setEnabledUseCases()`，或缩小 CameraEffect 目标集合。启用图像分析时，分析帧仍是原始相机帧，不会经过这个 CameraEffect。

### 10.4 更新、清除与资源管理

- `setEffects(effects)` 用新集合替换当前效果集合；目标重复或组合不受支持时抛出 `IllegalArgumentException`。
- `clearEffects()` 移除全部效果。
- 两个方法都会重新绑定 UseCase。CameraX 1.4.2 的 Controller 实现会先对其 Provider 调用 `unbindAll()`，因此可能影响同一进程共享该 Provider 的其他相机使用方；建议集中管理并尽量在首次绑定前设置。
- 若只是调整滤镜强度、LUT、颜色矩阵等参数，不要每帧调用 `setEffects()`。把参数安全地投递到 GL 线程并更新 shader uniform，避免触发相机解绑/重绑。
- `CameraEffect` 的 error listener 用于不可恢复错误；`SurfaceProcessor` 应在无法处理时抛出 `ProcessingException` 并通知应用。仅以 Preview 为目标时，CameraX 不一定把处理错误继续转发给应用，处理器应主动报告错误。
- Controller/页面退出时，等待 Surface 的结果回调或 close 事件，再释放 GL 资源并关闭自建 Executor；不要在 `clearEffects()` 后立即释放仍被 CameraX 使用的 Surface。

### 10.5 ImageProcessor 与 GLES 的区别

`ImageProcessor` 只支持 `IMAGE_CAPTURE`，输入是 `ImageProxy` 图像请求，返回处理后的图像响应，不是连续的输入/输出 Surface。它适合拍照后处理或 CPU 图像算法；要用 GLES 也必须自行处理图像上传、同步、输出图像格式和 `ImageProxy` 生命周期。

若预览/录像用 GLES，而拍照要使用另一套处理方式，可分别创建目标互不重叠的 `CameraEffect`（例如一个 `PREVIEW | VIDEO_CAPTURE` 的 SurfaceProcessor 效果，以及一个 `IMAGE_CAPTURE` 的 ImageProcessor 效果）。同一个 UseCase 不能被两个效果重复声明。若三种输出要保持同一滤镜观感，优先使用目标为三者的单个 SurfaceProcessor。

## 11. 默认值速查

| 项目 | 默认值/行为 |
| --- | --- |
| 相机 | `CameraSelector.DEFAULT_BACK_CAMERA` |
| 启用的 UseCase | `IMAGE_CAPTURE | IMAGE_ANALYSIS` |
| Preview | 始终启用 |
| VideoCapture | 默认关闭 |
| 拍照闪光灯 | `FLASH_MODE_OFF` |
| ImageAnalysis 背压 | `STRATEGY_KEEP_ONLY_LATEST` |
| ImageAnalysis 输出 | `YUV_420_888` |
| 视频质量 | `Recorder.DEFAULT_QUALITY_SELECTOR` |
| 视频镜像 | `MIRROR_MODE_OFF` |
| 视频动态范围 | SDR |
| 视频目标帧率 | 未指定 |
| PreviewView 双指缩放 | 开启 |
| PreviewView 点击对焦 | 开启 |

## 12. 生命周期与资源清理

`LifecycleCameraController` 会按绑定的 `LifecycleOwner` 管理相机开启/关闭。`bindToLifecycle()` 和 `unbind()` 是 `LifecycleCameraController` 的 API，不是 `CameraController` 基类 API。Controller 不再使用时，可断开 PreviewView 并解除生命周期绑定，然后停止应用自己创建的 Executor：

```kotlin
override fun onDestroy() {
    binding.previewView.controller = null
    cameraController.unbind()
    cameraExecutor.shutdown()
    super.onDestroy()
}
```

`PreviewView.controller = null` 会断开预览 View 与 Controller 的连接；`LifecycleCameraController.unbind()` 会清除生命周期绑定并解除 UseCase。若 Controller 仍需复用，可按页面生命周期设计决定何时解绑、何时重新绑定。

相机权限仍由应用负责申请。基本预览/拍照需要 `CAMERA`；录音需要 `RECORD_AUDIO`。权限未获准前不要启动相机或开始带音频的录像。

## 13. 常见问题与使用建议

1. **预览黑屏或相机启动失败**：确认已获得 `CAMERA` 权限、PreviewView 已设置 Controller、LifecycleOwner 有效，并检查初始化 Future/日志。
2. **调用拍照报错**：确认 `IMAGE_CAPTURE` 已启用且相机已初始化；内存拍照回调中的 `ImageProxy` 必须关闭。
3. **开始录像报错**：确认 `VIDEO_CAPTURE` 已启用且没有其他未完成录像。录音时还必须授予 `RECORD_AUDIO`。
4. **设置分辨率后结果不一致**：分辨率选择是偏好而非绝对承诺；设备、显示比例和并发 UseCase 都会影响实际输出。
5. **运行时配置造成画面闪断**：把稳定配置提前到绑定前；需要切换拍照/录像时，切换 UseCase 并等待相机重新绑定。
6. **拍照闪光灯与手电筒混淆**：`setImageCaptureFlashMode()` 控制拍照闪光行为；`enableTorch()` 控制持续照明。
7. **线程问题**：Controller 配置/控制通常在主线程调用；分析与耗时图像处理放后台 Executor，避免阻塞相机回调。

## 14. 本项目示例入口

- 相机页面示例：`app/src/main/java/com/android/grafika/day09/Day09Activity.kt`
- Controller 实现源码：`androidx/camera/view/CameraController.java`
- CameraX 依赖版本：`app/build.gradle` 中的 `cameraxVersion = '1.4.2'`

