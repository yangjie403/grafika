# 使用 GLSurfaceView 渲染 CameraX 相机画面

本文介绍如何使用 CameraX、GLSurfaceView 和 OpenGL ES 实现一个可交互的相机页面，包含实时滤镜、原始照片保存、滤镜截图、点击对焦、双指缩放、滑块缩放、摄像头切换、旋转和生命周期处理。

示例以本项目当前使用的 CameraX 1.4.2、Kotlin、OpenGL ES 2.0 为基准。CameraX 升级后，应重新核对 API 的版本、实验性标注和 UseCase 组合。

> 本文只新增 Markdown 文档，不直接修改现有 Activity 或 Renderer。项目中的 day09、day10、GlUtil 和相关文档可以作为对照。

## 1. 选择 CameraX 接入方式

### 1.1 GLSurfaceView 预览使用 ProcessCameraProvider

自定义 GLSurfaceView 时，推荐使用 ProcessCameraProvider，因为可以直接接管 Preview.SurfaceProvider：

```text
CameraX Preview
       │ SurfaceRequest
       ▼
SurfaceTexture ← Surface ← CameraX
       │
       │ GL_TEXTURE_EXTERNAL_OES
       ▼
GLSurfaceView.Renderer
       │ 顶点/片元 Shader
       ▼
屏幕或 FBO
```

应用自己管理 SurfaceTexture、OES 纹理、GL Program、FBO 和绘制线程，适合自定义 GLSL 滤镜、裁切、旋转、镜像和导出滤镜结果。

### 1.2 CameraController 适合 PreviewView

LifecycleCameraController 是高层 API，当前公开用法要求配合 PreviewView。它内部管理 Preview、ImageCapture、ImageAnalysis 和 VideoCapture，并提供拍照、对焦、缩放、切换摄像头等操作。

CameraX 1.4.2 中，CameraController 的 Preview SurfaceProvider 由 PreviewView 连接，应用没有面向普通调用者的公开 API 可以直接把它替换成 GLSurfaceView。因此：

- 最终画面由 GLSurfaceView 绘制：使用 ProcessCameraProvider。
- 快速完成标准相机页：使用 PreviewView 加 LifecycleCameraController。
- 预览和录像/拍照都经过 OpenGL：使用 CameraEffect 加 SurfaceProcessor。
- 把 GLSurfaceView 叠在 PreviewView 上只改变屏幕显示，ImageCapture 不会自动保存滤镜后的照片。

## 2. 依赖和权限

### 2.1 Gradle 依赖

本项目已经配置 CameraX 1.4.2。新工程可使用：

```groovy
dependencies {
    implementation "androidx.camera:camera-core:1.4.2"
    implementation "androidx.camera:camera-camera2:1.4.2"
    implementation "androidx.camera:camera-lifecycle:1.4.2"
    implementation "androidx.camera:camera-view:1.4.2"
}
```

只走 ProcessCameraProvider 时，camera-core、camera-camera2 和 camera-lifecycle 是核心依赖；camera-view 用于 PreviewView 和 CameraController 路线。OpenGL ES、EGL14、GLSurfaceView 和 SurfaceTexture 都是 Android 平台 API。

### 2.2 Manifest 和运行时权限

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature
    android:glEsVersion="0x00020000"
    android:required="true" />
```

保存到 filesDir 或 cacheDir 不需要存储权限。保存到公共相册时，Android 10 及以上使用 MediaStore 的 RELATIVE_PATH 和 IS_PENDING；Android 9 及以下写入公共外部存储通常还需要 WRITE_EXTERNAL_STORAGE。

必须在相机权限通过后再调用 bindToLifecycle。没有权限或页面正在退出时，应尽快完成或拒绝 SurfaceRequest，不能让 CameraX 一直等待。

## 3. GLSurfaceView 初始化

```kotlin
glSurfaceView.setEGLContextClientVersion(2)
glSurfaceView.setRenderer(renderer)
glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
glSurfaceView.preserveEGLContextOnPause = true
```

RENDERMODE_WHEN_DIRTY 配合 SurfaceTexture 的新帧通知使用，避免没有新帧时持续绘制。preserveEGLContextOnPause 只能降低上下文重建概率；上下文重建后仍必须重新创建纹理、Program、FBO 和 SurfaceTexture。

## 4. CameraX 到 OpenGL 的数据流

### 4.1 SurfaceTexture 和 OES 纹理

SurfaceTexture 是图像消费者。创建一个外部纹理，再创建包裹它的 Android Surface：

```kotlin
val textureId = createExternalTexture()
val surfaceTexture = SurfaceTexture(textureId)
val cameraSurface = Surface(surfaceTexture)
```

相机纹理必须使用 GL_TEXTURE_EXTERNAL_OES，片元 Shader 必须声明扩展并使用 samplerExternalOES：

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uCameraTexture;
```

不能把 OES 纹理当作 GL_TEXTURE_2D 使用，也不能把它直接作为普通 FBO 颜色附件。多遍处理时，先把 OES 纹理绘制到普通二维纹理，再继续处理二维纹理。

### 4.2 SurfaceRequest 生命周期

Preview.SurfaceProvider 收到的 SurfaceRequest 是 CameraX 对输出 Surface 的申请，不是一张图片。典型流程：

1. 读取 request.resolution。
2. 在 GL 线程调用 SurfaceTexture.setDefaultBufferSize。
3. 创建 Surface(surfaceTexture)。
4. 调用 request.provideSurface。
5. 在 resultListener 表示 CameraX 不再使用后释放 Surface。

请求可能因为切换镜头、旋转、UseCase 重建或分辨率变化而取消。每个请求只能完成一次；无法提供时调用 willNotProvideSurface。不要在 provideSurface 返回后立即释放 Surface。

### 4.3 新帧通知

新帧回调只代表“有新帧可以消费”，不代表纹理已经自动更新。必须在创建纹理的 GL 线程调用：

```kotlin
surfaceTexture.updateTexImage()
surfaceTexture.getTransformMatrix(textureMatrix)
```

回调线程可以只设置一个标志并调用 glSurfaceView.requestRender，不要在回调线程调用 updateTexImage。

## 5. GLSL 滤镜

### 5.1 顶点 Shader

```glsl
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTextureMatrix;
varying vec2 vTexCoord;

void main() {
    gl_Position = aPosition;
    vTexCoord = (uTextureMatrix * aTexCoord).xy;
}
```

aPosition 使用 (-1,-1) 到 (1,1) 的裁剪空间坐标。uTextureMatrix 来自 SurfaceTexture.getTransformMatrix，通常包含相机缓冲区需要的翻转或变换。

### 5.2 片元 Shader

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uCameraTexture;
uniform int uFilterMode;
uniform float uFilterIntensity;

void main() {
    vec4 source = texture2D(uCameraTexture, vTexCoord);
    vec3 color = source.rgb;

    if (uFilterMode == 1) {
        float gray = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(color, vec3(gray), uFilterIntensity);
    } else if (uFilterMode == 2) {
        vec3 sepia = vec3(
            dot(color, vec3(0.393, 0.769, 0.189)),
            dot(color, vec3(0.349, 0.686, 0.168)),
            dot(color, vec3(0.272, 0.534, 0.131))
        );
        color = mix(color, sepia, uFilterIntensity);
    } else if (uFilterMode == 3) {
        color = mix(color, vec3(1.0) - color, uFilterIntensity);
    }

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), source.a);
}
```

灰度权重是常见的显示 RGB 近似值。严格色彩工作流还需要考虑线性色彩空间、sRGB 和预乘 alpha。

## 6. Renderer 核心结构

下面的代码展示关键生命周期和线程关系；生产代码还应加入 FBO、方向矩阵和更严格的资源状态机。

```kotlin
class CameraGlRenderer(
    private val glView: GLSurfaceView
) : GLSurfaceView.Renderer {

    private val glExecutor = Executor { command ->
        glView.queueEvent(command)
    }

    @Volatile
    private var pendingRequest: SurfaceRequest? = null
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var hasNewFrame = false
    private var glReady = false
    private var outputWidth = 0
    private var outputHeight = 0

    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var textureMatrixLocation = -1
    private var textureLocation = -1
    private var filterModeLocation = -1
    private var filterIntensityLocation = -1
    private val textureMatrix = FloatArray(16)

    @Volatile
    var filterMode: Int = 0
        set(value) {
            field = value.coerceIn(0, 3)
            glView.requestRender()
        }

    @Volatile
    var filterIntensity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            glView.requestRender()
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        check(program != 0) { "创建 Shader Program 失败" }

        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureMatrixLocation =
            GLES20.glGetUniformLocation(program, "uTextureMatrix")
        textureLocation =
            GLES20.glGetUniformLocation(program, "uCameraTexture")
        filterModeLocation =
            GLES20.glGetUniformLocation(program, "uFilterMode")
        filterIntensityLocation =
            GLES20.glGetUniformLocation(program, "uFilterIntensity")

        oesTextureId = createExternalTexture()
        surfaceTexture = SurfaceTexture(oesTextureId).also { texture ->
            texture.setOnFrameAvailableListener {
                hasNewFrame = true
                glView.requestRender()
            }
        }
        glReady = true
        providePendingRequestIfNeeded()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        outputWidth = width
        outputHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture ?: return
        if (!hasNewFrame) return

        hasNewFrame = false
        texture.updateTexImage()
        texture.getTransformMatrix(textureMatrix)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCameraTexture()
    }

    // 可从主线程或 CameraX 回调线程调用，不能直接操作 GL 对象。
    fun onSurfaceRequested(request: SurfaceRequest) {
        val oldRequest = pendingRequest
        pendingRequest = request
        oldRequest?.willNotProvideSurface()
        glView.queueEvent { providePendingRequestIfNeeded() }
    }

    private fun providePendingRequestIfNeeded() {
        if (!glReady) return

        val request = pendingRequest ?: return
        val texture = surfaceTexture ?: run {
            request.willNotProvideSurface()
            pendingRequest = null
            return
        }

        pendingRequest = null
        val size = request.resolution
        texture.setDefaultBufferSize(size.width, size.height)

        val surface = Surface(texture)
        cameraSurface = surface
        request.provideSurface(surface, glExecutor) {
            glView.queueEvent {
                if (cameraSurface === surface) {
                    cameraSurface = null
                }
                surface.release()
            }
        }
    }

    private fun drawCameraTexture() {
        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )

        textureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer
        )

        GLES20.glUniformMatrix4fv(
            textureMatrixLocation,
            1,
            false,
            textureMatrix,
            0
        )
        GLES20.glUniform1i(filterModeLocation, filterMode)
        GLES20.glUniform1f(filterIntensityLocation, filterIntensity)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            oesTextureId
        )
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
    }

    // 必须在 CameraX 解绑并且 GL 线程仍然有效时调用。
    fun releaseOnGlThread() {
        glReady = false
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        cameraSurface?.release()
        cameraSurface = null

        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}
```

顶点和纹理缓冲区可以使用项目的 GlUtil.createFloatBuffer：

```kotlin
private val vertexBuffer = GlUtil.createFloatBuffer(
    floatArrayOf(
        -1f, -1f, 1f, -1f,
        -1f,  1f, 1f,  1f
    )
)

private val textureBuffer = GlUtil.createFloatBuffer(
    floatArrayOf(
        0f, 0f, 1f, 0f,
        0f, 1f, 1f, 1f
    )
)
```

所有 SurfaceTexture 和 GLES 调用必须在 GLSurfaceView 的 GL 线程执行。SurfaceRequest 回调只负责把请求投递到该线程。

### 6.1 创建 OES 纹理

```kotlin
private fun createExternalTexture(): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    check(ids[0] != 0) { "创建 OES 纹理失败" }

    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MIN_FILTER,
        GLES20.GL_LINEAR
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MAG_FILTER,
        GLES20.GL_LINEAR
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_S,
        GLES20.GL_CLAMP_TO_EDGE
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_T,
        GLES20.GL_CLAMP_TO_EDGE
    )
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    return ids[0]
}
```

## 7. 使用 ProcessCameraProvider 绑定预览和拍照

### 7.1 初始化和绑定

```kotlin
private lateinit var cameraProvider: ProcessCameraProvider
private lateinit var camera: Camera
private lateinit var imageCapture: ImageCapture

private fun startCamera() {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
        try {
            cameraProvider = future.get()
            bindCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (error: Exception) {
            Log.e(TAG, "CameraX 初始化失败", error)
        }
    }, ContextCompat.getMainExecutor(this))
}

private fun bindCamera(selector: CameraSelector) {
    val rotation = glSurfaceView.display?.rotation ?: Surface.ROTATION_0

    val preview = Preview.Builder()
        .setTargetRotation(rotation)
        .build()
    preview.setSurfaceProvider { request ->
        renderer.onSurfaceRequested(request)
    }

    imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setTargetRotation(rotation)
        .build()

    cameraProvider.unbindAll()
    camera = cameraProvider.bindToLifecycle(
        this,
        selector,
        preview,
        imageCapture
    )
}
```

绑定返回的 Camera 是对焦、缩放和手电筒的控制入口。实际预览尺寸以 SurfaceRequest.resolution 为准，不要假设它等于 GLSurfaceView 尺寸。

### 7.2 切换摄像头

```kotlin
private var lensFacing = CameraSelector.LENS_FACING_BACK

private fun switchCamera() {
    lensFacing =
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

    val selector = CameraSelector.Builder()
        .requireLensFacing(lensFacing)
        .build()
    bindCamera(selector)
}
```

切换会导致 CameraX 重新申请 Surface。Renderer 不能只处理第一次请求，必须能接收新请求并释放旧资源。

### 7.3 让预览和拍照使用一致视口

GL View 测量完成后，可以使用 UseCaseGroup 和 ViewPort：

```kotlin
val viewPort = ViewPort.Builder(
    Rational(glSurfaceView.width, glSurfaceView.height),
    rotation
)
    .setScaleType(ViewPort.FILL_CENTER)
    .setLayoutDirection(glSurfaceView.layoutDirection)
    .build()

val group = UseCaseGroup.Builder()
    .setViewPort(viewPort)
    .addUseCase(preview)
    .addUseCase(imageCapture)
    .build()

camera = cameraProvider.bindToLifecycle(this, selector, group)
```

ViewPort 负责 UseCase 之间的裁切关系；GL 顶点矩阵仍然需要按照相同的 FILL 或 FIT 策略绘制，否则屏幕和照片的取景范围可能不同。

## 8. 旋转、镜像和裁切

### 8.1 旋转

Preview 和 ImageCapture 都应设置当前显示方向。设备旋转时更新 targetRotation；不要同时对 SurfaceTexture 矩阵和顶点矩阵重复旋转。

SurfaceRequest.TransformationInfo 可提供 crop rect、rotation degrees、是否包含相机变换和镜像信息。直接采样 CameraX 写入的 SurfaceTexture 时，通常先使用 getTransformMatrix；需要跨 UseCase 坐标映射时，再结合 TransformationInfo。

### 8.2 前置镜像

预览镜像和保存照片镜像是两个独立策略：

- 预览：在顶点矩阵或纹理坐标中交换 u 坐标。
- 对焦：对焦点要转换到相机坐标，不能直接使用镜像后的屏幕坐标。
- 原始 ImageCapture：通过输出元数据或 CameraX 默认策略控制。
- 滤镜截图：如果要和预览一致，FBO 输出也要应用同样的镜像矩阵。

### 8.3 FILL 和 FIT

如果 View 和相机缓冲区宽高比不同，直接绘制全屏矩形会拉伸。以 FILL_CENTER 为例：

```text
scale = max(viewWidth / bufferWidth, viewHeight / bufferHeight)
scaledWidth  = bufferWidth  * scale
scaledHeight = bufferHeight * scale
```

把超出 View 的部分裁掉就是 FILL；使用 min 就是 FIT。对焦坐标和滤镜截图必须使用同一套裁切策略。

## 9. 保存照片

必须区分三种目标：

| 目标 | 推荐方式 | 特点 |
|---|---|---|
| 原始高质量照片 | ImageCapture.takePicture(OutputFileOptions, ...) | CameraX 直接生成照片，通常高于预览分辨率 |
| 当前 GLSurfaceView 滤镜画面 | FBO 加 glReadPixels | 与滤镜一致，但通常是预览/FBO 分辨率 |
| 高分辨率滤镜照片 | ImageCapture 内存回调后离屏 GPU 处理，或 CameraEffect/SurfaceProcessor | 需要额外图像处理管线 |

### 9.1 使用 ImageCapture 保存原始照片

```kotlin
private fun captureOriginalPhoto() {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "grafika_photo.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Grafika"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val options = ImageCapture.OutputFileOptions.Builder(
        contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ).build()

    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(this),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(
                result: ImageCapture.OutputFileResults
            ) {
                val uri = result.savedUri
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                    contentResolver.update(
                        uri,
                        ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        },
                        null,
                        null
                    )
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "保存照片失败", exception)
            }
        }
    )
}
```

takePicture 是异步操作。方法返回不代表文件已经写入，应在 onImageSaved 中更新 UI，在 onError 中处理相机关闭、IO 失败和无效 UseCase。

### 9.2 使用 ImageProxy 做高分辨率后处理

```kotlin
imageCapture.takePicture(
    ContextCompat.getMainExecutor(this),
    object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                // 根据 image.format 和 rotationDegrees 解码并处理。
            } finally {
                image.close()
            }
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "获取照片帧失败", exception)
        }
    }
)
```

无论处理成功还是失败，都必须关闭 ImageProxy。JPEG、YUV_420_888 和 JPEG_R 不能用同一套解码逻辑。

### 9.3 使用 FBO 保存滤镜结果

OES 纹理不能直接作为 FBO 附件。先创建普通二维纹理和 FBO：

```kotlin
GLES20.glGenTextures(1, textureIds, 0)
captureTextureId = textureIds[0]
GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTextureId)
GLES20.glTexParameteri(
    GLES20.GL_TEXTURE_2D,
    GLES20.GL_TEXTURE_MIN_FILTER,
    GLES20.GL_LINEAR
)
GLES20.glTexParameteri(
    GLES20.GL_TEXTURE_2D,
    GLES20.GL_TEXTURE_MAG_FILTER,
    GLES20.GL_LINEAR
)
GLES20.glTexImage2D(
    GLES20.GL_TEXTURE_2D,
    0,
    GLES20.GL_RGBA,
    width,
    height,
    0,
    GLES20.GL_RGBA,
    GLES20.GL_UNSIGNED_BYTE,
    null
)

GLES20.glGenFramebuffers(1, framebufferIds, 0)
captureFboId = framebufferIds[0]
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFboId)
GLES20.glFramebufferTexture2D(
    GLES20.GL_FRAMEBUFFER,
    GLES20.GL_COLOR_ATTACHMENT0,
    GLES20.GL_TEXTURE_2D,
    captureTextureId,
    0
)
check(
    GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
        GLES20.GL_FRAMEBUFFER_COMPLETE
)
```

每帧先把 OES 相机纹理和滤镜 Shader 绘制到 captureFboId，再把普通二维纹理绘制到窗口。用户点击拍照时读取 FBO：

```kotlin
glView.queueEvent {
    val pixels = ByteBuffer
        .allocateDirect(width * height * 4)
        .order(ByteOrder.nativeOrder())

    GLES20.glBindFramebuffer(
        GLES20.GL_FRAMEBUFFER,
        captureFboId
    )
    GLES20.glReadPixels(
        0, 0, width, height,
        GLES20.GL_RGBA,
        GLES20.GL_UNSIGNED_BYTE,
        pixels
    )
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

    pixels.rewind()
    val bitmap = Bitmap.createBitmap(
        width,
        height,
        Bitmap.Config.ARGB_8888
    )
    bitmap.copyPixelsFromBuffer(pixels)

    // glReadPixels 原点在左下角；翻转、压缩和写相册放到后台线程。
    backgroundExecutor.execute {
        val corrected = flipVertical(bitmap)
        saveBitmapToMediaStore(corrected)
        corrected.recycle()
        bitmap.recycle()
    }
}
```

glReadPixels 会同步 GPU，不能每帧调用。FBO 宽高决定保存分辨率，通常是预览分辨率；如果要求高分辨率滤镜照片，应对 ImageCapture 的 ImageProxy 做离屏处理。

## 10. 点击对焦和测光

### 10.1 使用 Camera.cameraControl

如果 GLSurfaceView 没有额外缩放、裁切和旋转，可以直接使用 DisplayOrientedMeteringPointFactory：

```kotlin
private fun focusAtViewPoint(x: Float, y: Float) {
    val factory = DisplayOrientedMeteringPointFactory(
        glSurfaceView.display,
        camera.cameraInfo,
        glSurfaceView.width.toFloat(),
        glSurfaceView.height.toFloat()
    )
    val point = factory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point)
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()

    val future = camera.cameraControl.startFocusAndMetering(action)
    future.addListener(
        {
            val success = runCatching {
                future.get().isFocusSuccessful
            }.getOrDefault(false)
            focusIndicator.show(success)
        },
        ContextCompat.getMainExecutor(this)
    )
}
```

如果使用 FILL、FIT、镜像或自定义矩阵，必须先把触摸点转换到完整相机视场，再创建 MeteringPoint。FILL_CENTER 的基本映射是：

```kotlin
val scale = max(
    viewWidth.toFloat() / bufferWidth,
    viewHeight.toFloat() / bufferHeight
)
val displayedWidth = bufferWidth * scale
val displayedHeight = bufferHeight * scale
val left = (viewWidth - displayedWidth) * 0.5f
val top = (viewHeight - displayedHeight) * 0.5f
val bufferX = ((touchX - left) / scale)
val bufferY = ((touchY - top) / scale)
```

然后使用 SurfaceOrientedMeteringPointFactory(bufferWidth, bufferHeight)。如果顶点 Shader 又旋转或镜像了画面，还要继续应用 GL 变换的逆矩阵。

## 11. 缩放交互

### 11.1 滑块缩放

CameraX 的线性缩放范围是 0 到 1，适合绑定 SeekBar：

```kotlin
zoomSeekBar.setOnSeekBarChangeListener(
    object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(
            seekBar: SeekBar?,
            progress: Int,
            fromUser: Boolean
        ) {
            if (fromUser) {
                camera.cameraControl.setLinearZoom(progress / 100f)
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
)
```

精确倍率必须读取 ZoomState.minZoomRatio 和 maxZoomRatio，不能假设所有设备的最大倍率相同：

```kotlin
camera.cameraInfo.zoomState.observe(this) { state ->
    val minRatio = state.minZoomRatio
    val maxRatio = state.maxZoomRatio
    val currentRatio = state.zoomRatio
}
camera.cameraControl.setZoomRatio(2.0f)
```

### 11.2 双指缩放

```kotlin
private val scaleDetector = ScaleGestureDetector(
    this,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val state = camera.cameraInfo.zoomState.value ?: return true
            val next = (
                state.linearZoom +
                    (detector.scaleFactor - 1f) * 0.5f
                ).coerceIn(0f, 1f)
            camera.cameraControl.setLinearZoom(next)
            return true
        }
    }
)

glSurfaceView.setOnTouchListener { _, event ->
    scaleDetector.onTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP &&
        !scaleDetector.isInProgress
    ) {
        focusAtViewPoint(event.x, event.y)
    }
    true
}
```

实际项目应区分单指点击、双指手势和滑动，避免双指结束时误触发一次对焦。

CameraX 变焦和 Shader UV 放大不是同一操作：前者改变相机输出视场，后者只是数字裁切。需要平滑变焦时可以组合两者，但要明确倍率关系。

## 12. CameraController 替代实现

不要求 GLSurfaceView 作为预览输出时，可以使用 PreviewView 和 LifecycleCameraController：

```kotlin
private lateinit var cameraController: LifecycleCameraController

private fun startWithController() {
    cameraController = LifecycleCameraController(this).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setTapToFocusEnabled(true)
        setPinchToZoomEnabled(true)
        bindToLifecycle(this@CameraActivity)
    }
    previewView.controller = cameraController
}
```

Controller 直接提供：

```kotlin
cameraController.setLinearZoom(0.35f)
cameraController.setZoomRatio(2.0f)
```

保存照片可调用 cameraController.takePicture；点击对焦和双指缩放由 PreviewView 处理。如果预览被自定义 GL 裁切，不能直接把 GLSurfaceView 的触摸坐标交给 Controller，应使用 Camera.cameraControl 配合坐标变换。

如果需要 Controller 管理生命周期，同时把滤镜应用到 CameraX 输出，使用 CameraEffect 和 SurfaceProcessor。项目中的 day09/GlSurfaceProcessor.kt 和《使用 CameraEffect 和 SurfaceProcessor 实现 OpenGL 相机滤镜》展示了这条路径。

## 13. 生命周期和资源释放

推荐的停止顺序：

```text
停止 UI 操作
    ↓
cameraProvider.unbindAll()
    ↓
等待 SurfaceRequest resultListener
    ↓
在 GL 线程释放 Surface、SurfaceTexture、OES 纹理、FBO 和 Program
    ↓
glSurfaceView.onPause()
```

Activity 销毁时：

```kotlin
override fun onDestroy() {
    if (::cameraProvider.isInitialized) {
        cameraProvider.unbindAll()
    }
    glSurfaceView.queueEvent {
        renderer.releaseOnGlThread()
    }
    glSurfaceView.onPause()
    super.onDestroy()
}
```

如果 Renderer 仍持有未完成的 SurfaceRequest，应调用 willNotProvideSurface。已经提供的 Surface 要等待 CameraX 的 resultListener 再释放。EGLContext 重建后必须重新创建所有 GL 对象，旧纹理 ID 不能跨上下文复用。

## 14. 常见问题和排查顺序

| 现象 | 优先检查 |
|---|---|
| 黑屏 | 是否调用 updateTexImage、requestRender、glUseProgram；OES target 和 sampler 类型是否匹配 |
| GL_INVALID_OPERATION | 是否在错误线程调用 updateTexImage，或没有当前 EGLContext |
| 上下翻转 | 是否应用 SurfaceTexture 矩阵，是否重复做 CPU/GL 翻转 |
| 画面拉伸 | 是否按相机缓冲区和 View 的宽高比处理 FILL/FIT |
| 切换镜头后黑屏 | 是否接收了新的 SurfaceRequest 并释放旧资源 |
| CameraX 一直等待 | 是否调用 provideSurface 或 willNotProvideSurface |
| 拍照没有滤镜 | ImageCapture 是独立输出，需要 FBO 截图、ImageProxy 后处理或 CameraEffect |
| 对焦偏移 | 触摸坐标是否经过裁切、旋转和镜像的逆变换 |
| 内存增长 | 是否释放 Surface、SurfaceTexture、Bitmap、FBO 和 ImageProxy |

建议按以下顺序调试：

1. 片元 Shader 固定输出红色，验证 EGL、GLSurfaceView 和顶点绘制。
2. 直接输出 OES 纹理，验证 CameraX Surface 和 updateTexImage。
3. 输出 UV 渐变，验证纹理矩阵和方向。
4. 再加入滤镜、FBO、保存、对焦和多指缩放。

Program 编译/链接失败查看 glGetShaderInfoLog 和 glGetProgramInfoLog；运行时状态错误使用 glGetError 或项目中的 GlUtil.checkGlError。

## 15. 实现选择和项目参考

| 需求 | 建议方案 |
|---|---|
| 自定义 GLSL 实时预览 | ProcessCameraProvider + Preview.SurfaceProvider + GLSurfaceView |
| 原始高质量拍照 | 上述方案加 ImageCapture |
| 当前滤镜画面截图 | FBO + glReadPixels |
| 高分辨率滤镜照片 | ImageCapture 内存回调后离屏 GPU 处理，或 CameraEffect/SurfaceProcessor |
| 快速标准相机页 | LifecycleCameraController + PreviewView |
| 预览和录像共享滤镜 | CameraEffect + SurfaceProcessor |

项目内参考：

- [着色器代码编写指南](./着色器代码编写指南.md)
- [着色器参数传递指南](./着色器参数传递指南.md)
- [ProcessCameraProvider API 使用详解](./ProcessCameraProvider_API使用详解.md)
- [CameraController API 使用详解](./CameraController_API使用详解.md)
- [使用 CameraEffect 和 SurfaceProcessor 实现 OpenGL 相机滤镜](./使用CameraEffect和SurfaceProcessor实现OpenGL相机滤镜.md)
- app/src/main/java/com/android/grafika/day10/Day10Activity.kt
- app/src/main/java/com/android/grafika/day09/GlSurfaceProcessor.kt
- app/src/main/java/com/android/grafika/gles/GlUtil.java

官方参考：

- https://developer.android.com/reference/androidx/camera/core/Preview
- https://developer.android.com/reference/androidx/camera/core/SurfaceRequest
- https://developer.android.com/reference/androidx/camera/lifecycle/ProcessCameraProvider
- https://developer.android.com/reference/androidx/camera/core/ImageCapture
- https://developer.android.com/reference/androidx/camera/core/CameraControl
- https://developer.android.com/reference/android/opengl/GLSurfaceView
- https://developer.android.com/reference/android/graphics/SurfaceTexture
