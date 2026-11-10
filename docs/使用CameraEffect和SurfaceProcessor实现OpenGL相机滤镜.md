# 使用 CameraEffect、SurfaceProcessor 和 OpenGL ES 实现相机滤镜

> 本文面向第一次实现 GPU 相机滤镜的 Android 开发者。示例使用 Kotlin、CameraX **1.4.2**、OpenGL ES 2.0、EGL14 和普通 View。以本项目的依赖版本为基准，不把较新版本 API 混入示例。示例限定为单相机、8 位 SDR；先实现灰度预览，再扩展到录像和多通道滤镜。
>
> 本文中的代码是供读者在独立示例工程中创建的完整教学实现，包名统一为 `com.example.camerafilter`。它不依赖 Grafika 的 `GlUtil`、`EglCore` 等工具类。项目现有 `day09` 代码可用来对照，但本文不直接改动那些源码。

校验范围：四个完整 Kotlin 类已使用 Android 35 平台类和 CameraX 1.4.2 依赖进行独立编译检查；录像、Controller 和分析坐标映射中使用的 API 也已编译核对。第 21 节的 100 个 Android 方法签名/重载已通过平台类编译核对，覆盖正文原有的全部 56 个 EGL/GLES 调用。未执行真机 GPU 渲染验证，shader 驱动编译、画面方向、录像与设备兼容性请按第 19 节实测。

## 阅读路线

1. 第 1～3 节：理解数据流、对象和 CameraX API。
2. 第 4～7 节：建立工程，复制三个核心类和 Activity，运行灰度预览。
3. 第 8～11 节：逐步解释 SurfaceProcessor、EGL、GLES、坐标矩阵和释放流程。
4. 第 12～16 节：动态滤镜、录像、FBO、多通道处理、LUT、水印与拍照。
5. 第 17～20 节：API 速查、排错、验证和源码查证。
6. 第 21 节：逐个查询 GL 相关 API 的签名、全部参数、返回值、使用步骤和错误条件。读代码时遇到不认识的调用，可以直接在这一节搜索方法名。

第一遍可以先复制完整类，让画面出现；第二遍按每帧的数据流回看 GLES 调用。真正需要记住的是对象的职责、线程和资源所有权，GL 函数名可以随时查表。

## 1. 这套方案到底在做什么

相机持续生产图像。我们把相机原本要输出的帧接入一个自己创建的 `Surface`，通过 `SurfaceTexture` 把帧交给 GPU 纹理，再用片元着色器修改颜色，最后将处理结果画到 CameraX 提供的输出 `Surface`。

```mermaid
flowchart LR
    A[相机或 CameraX 上游] --> B[处理器创建的输入 Surface]
    B --> C[SurfaceTexture 和 OES 纹理]
    C --> D[顶点着色器与片元着色器]
    D --> E[输出 EGLSurface]
    E --> F[CameraX 提供的输出 Surface]
    F --> G[预览或下游共享与录像链路]
```

“输入”“输出”以 **SurfaceProcessor** 为参照：

- 输入 Surface：处理器创建，CameraX 向它写原始帧。
- 输出 Surface：CameraX 提供，处理器向它写滤镜帧。

不要把 `onInputSurface()` 理解成“CameraX 给我一张输入图像”。它给的是**申请 Surface 的请求**。也不要把 `onOutputSurface()` 理解成“输出图像已经处理好了”。它给的是**你应该往哪里绘制的输出端**。

这种路径避免应用每帧把 YUV 数据读到 CPU 再上传 GPU。底层可能仍有格式转换、缓存复制和驱动同步，因此更准确的说法是“避免应用层每帧 CPU 像素搬运”，不能笼统保证全链路零复制。

### 1.1 三个核心对象的分工

| 对象 | 主要职责 | 你需要实现什么 |
|---|---|---|
| `CameraEffect` | 声明滤镜作用于哪些 UseCase，指定处理器、回调线程和错误监听 | 一个很薄的子类 |
| `SurfaceProcessor` | 接入输入、管理输出、接收帧、渲染、处理 Surface 生命周期 | 本文的重点 |
| OpenGL ES / EGL | GPU 采样、颜色计算和绘制；把 GL 连接到 Android Surface | 着色器、纹理、EGL 环境和 draw 方法 |

`CameraEffect` 不负责自动执行你的 shader；`SurfaceProcessor` 也没有要求你一定用 OpenGL。它定义的是输入/输出 Surface 的处理契约，OpenGL 是一种实现方式。

### 1.2 为什么还需要 EGL

OpenGL ES 能描述“画什么”，但需要一个上下文保存程序、纹理和 GL 状态，也需要一个实际绘制目标。EGL 把这些条件准备好：

```text
EGLDisplay：与设备图形系统的连接
EGLConfig：颜色位数、ES 版本、Surface 能力等配置
EGLContext：GL 对象和状态所属的上下文
EGLSurface：GL 实际绘制的目标
```

Android 的 `Surface` 和 EGL 的 `EGLSurface` 是两个对象。前者连接 Android 图像队列；后者是通过 `eglCreateWindowSurface()` 包装前者后得到的 EGL 绘制目标。

## 2. 先掌握五个基础概念

### 2.1 Surface 是图像队列的一端

`Surface` 不是一个 `Bitmap`，也不是一个 GL 纹理 ID。它代表生产图像的接口，其后通常有 BufferQueue。相机把图像写入输入 Surface，消费者从队列取得帧。

### 2.2 SurfaceTexture 是消费者

`SurfaceTexture(textureId)` 将接收到的图像关联到一个外部纹理。新帧到达时会收到 `OnFrameAvailableListener` 通知；GL 线程调用 `updateTexImage()`，才把最新可用图像更新到纹理。

通知的含义是“有帧可以消费”，不是“纹理已经自动换成新帧”。

### 2.3 OES 纹理与普通二维纹理不同

普通图片通常上传到 `GL_TEXTURE_2D`，用 `sampler2D` 采样。相机 `SurfaceTexture` 使用 `GL_TEXTURE_EXTERNAL_OES`，用 `samplerExternalOES` 采样。

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
```

对应的纹理 target、GLSL sampler 类型和扩展声明必须配套。不要对相机 OES 纹理调用 `glTexImage2D()` 上传像素，也不要把它直接当成 FBO 的颜色附件。多通道处理时先把它绘制到普通二维纹理。

### 2.4 顶点着色器决定覆盖范围和采样位置

为了处理整张画面，画一个铺满输出面的矩形。四个顶点经过光栅化形成大量片元；每个片元得到插值后的纹理坐标。

### 2.5 片元着色器决定每个输出像素的颜色

灰度滤镜只需采样一次，再计算亮度并输出：

```glsl
vec4 color = texture2D(uTexture, vTexCoord);
float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
gl_FragColor = vec4(vec3(gray), color.a);
```

`dot()` 是三个通道与权重的乘积之和。这里是常见的显示编码 RGB 灰度近似，不是严格线性光空间的物理亮度计算。视觉调色通常可以从它开始，色彩精度要求高时再引入线性化和正确的色彩空间。

## 3. CameraX API 和必须遵守的契约

### 3.1 CameraEffect 的构造函数

本文使用的 Java 签名如下。由于是 `protected`，需要继承后调用：

```java
protected CameraEffect(
        int targets,
        Executor executor,
        SurfaceProcessor surfaceProcessor,
        androidx.core.util.Consumer<Throwable> errorListener);
```

| 参数 | 含义 | 本文选择 |
|---|---|---|
| `targets` | 目标 UseCase 的位标志组合 | 先用 `PREVIEW` |
| `executor` | 调用处理器入口和错误监听的执行器 | 专用 GL 线程 |
| `surfaceProcessor` | 自定义 GPU 处理器 | `TutorialSurfaceProcessor` |
| `errorListener` | 入口处理发生不可恢复错误时的通知 | 日志和应用回调 |

Kotlin 使用 `or` 组合标志，例如 `PREVIEW or VIDEO_CAPTURE`。这是位标志，不是普通加法，也不是布尔逻辑。

**经 CameraX 1.4.2 的 `CameraEffect.java` 源码核对，SurfaceProcessor 构造路径允许下列组合：**

| 组合 | 用途 |
|---|---|
| `PREVIEW` | 预览滤镜 |
| `VIDEO_CAPTURE` | 录像滤镜，仍需实际绑定支持该目标的 UseCase |
| `PREVIEW or VIDEO_CAPTURE` | 预览和录像共同处理 |
| `PREVIEW or VIDEO_CAPTURE or IMAGE_CAPTURE` | 允许的声明组合，拍照是否走该链路还取决于实际绑定和内部拓扑 |

不能使用 `IMAGE_CAPTURE` 单独作为这个 SurfaceProcessor 构造函数的目标，也不能任意拼出 `PREVIEW or IMAGE_CAPTURE`。本文后面专门讨论拍照，避免把“构造函数允许”误认为“所有绑定组合都会得到滤镜照片”。

**不是所有 SurfaceProcessor 目标都必须含 `VIDEO_CAPTURE`：单独 `PREVIEW` 在 1.4.2 就有效。** 目标标志也不会自动帮你创建或启用录像 UseCase。

多个 CameraEffect 的目标不能互相重叠。需要给同一帧连续加灰度、模糊、水印时，把它们组织在一个处理器的渲染链里，而不是向同一个 UseCase 挂几个重叠目标的 Effect。

### 3.2 SurfaceProcessor 的两个入口

```java
void onInputSurface(SurfaceRequest request) throws ProcessingException;
void onOutputSurface(SurfaceOutput surfaceOutput) throws ProcessingException;
```

二者是**管线创建或重建回调**，不是每帧回调。不能假设它们只执行一次，也不能依赖“输入一定先来”“输出一定先来”。切换摄像头、重新绑定或输出视图重建都可能引起新的回调。

每帧通知来自 `SurfaceTexture.OnFrameAvailableListener`。

### 3.3 SurfaceRequest：回答输入申请

| API | 用法和注意事项 |
|---|---|
| `getResolution()` / Kotlin `resolution` | CameraX 请求的输入缓冲尺寸，用于 `setDefaultBufferSize()` |
| `getDynamicRange()` / `dynamicRange` | 请求的动态范围；本文只接受 `DynamicRange.SDR` |
| `provideSurface(surface, executor, resultListener)` | 提交输入 Surface；**此回调返回后才回收对应输入资源** |
| `willNotProvideSurface()` | 不能提供或正在退出时，明确拒绝请求，避免请求一直悬空 |
| `addRequestCancellationListener()` | 监听未完成/被取消的请求，适合取消准备任务；不是所有已提交输入的统一释放信号 |
| `invalidate()` | 告知输入失效并要求重建；不是直接释放输入的授权 |
| `setTransformationInfoListener()` | 获取裁剪、旋转等元信息；自绘几何、坐标映射时有用 |

`provideSurface()` 的 resultListener 不是“每帧结果”，也不是“提供成功后立即可以释放”。对于正常使用的 Surface，它通常等到相机不再使用该 Surface 时才调用。

| `SurfaceRequest.Result` | 含义 |
|---|---|
| `RESULT_SURFACE_USED_SUCCESSFULLY` | 曾成功使用，现已脱离使用，可以回收 |
| `RESULT_REQUEST_CANCELLED` | 未被成功使用，请求已取消 |
| `RESULT_INVALID_SURFACE` | Surface 无效，例如尺寸不符合请求或过早关闭 |
| `RESULT_SURFACE_ALREADY_PROVIDED` | 这个请求已有提交，当前这次提交没有接入 |
| `RESULT_WILL_NOT_PROVIDE_SURFACE` | 请求已被拒绝，当前提交没有接入 |

本文对每个请求只提交一次，创建新资源，不复用旧请求中的 Surface。因此这一次提交的 resultListener 可以作为对应资源的收尾入口。

### 3.4 SurfaceOutput：领取输出和关闭协议

| API | 用法和注意事项 |
|---|---|
| `getSurface(executor, listener)` | 取得下游 Surface，并注册停止使用通知；多次调用只有最后一次 listener 有效 |
| `getSize()` / `size` | **这个输出**的缓冲尺寸，用于 viewport；不能用输入尺寸或 View 尺寸代替 |
| `getTargets()` / `targets` | 输出用途的位标志，可能代表共享链路；用位运算判断 |
| `updateTransformMatrix(updated, original)` | 把 SurfaceTexture 原始矩阵与 CameraX 附加变换组合到 `updated` |
| `close()` | 告诉 CameraX“我已停止写这个输出”，后续不能继续画 |
| `getSensorToBufferTransform()` | 传感器到输出缓冲区的二维变换，用于分析结果叠加；不是 shader 的 4×4 矩阵 |

收到 `EVENT_REQUEST_CLOSE` 后：停止对此输出绘制，解绑它作为当前绘制目标，销毁自己创建的 EGLSurface，调用 `SurfaceOutput.close()`。

**不要对 `getSurface()` 得到的 Surface 调用 `release()`。** 它由 CameraX/下游拥有。我们拥有的是包装它的 EGLSurface；输入 Surface 则由我们创建，确实需要我们回收。

### 3.5 两个入口都可能出现新旧交叠

官方契约允许新输入到达时旧输入仍未释放，也允许新输出到达时旧输出还未收到关闭请求。正确做法是：

- 一个集合保存所有尚未归还的输入资源；另有 `currentInput` 决定当前显示哪一路。
- 一个集合或 Map 保存所有尚未关闭的输出资源。
- 关闭回调携带哪个对象，就关闭那个对象。
- 旧输入归还时，不能顺带把新输入置空或释放。

本文允许同时保留多个输出，并用同一最新输入绘制它们。公共构造函数下实际输出可能是一个共享中间 Surface；这里支持多个对象也解决新旧输出交接，**不意味着 CameraX 必定逐个给你 Preview 和 Recorder 的最终 Surface**。

## 4. 创建最小工程

建议新建 Empty Views Activity 工程，包名 `com.example.camerafilter`，`minSdk = 24`，`compileSdk = 35` 或以上。使用 Kotlin 和 AndroidX。Kotlin/AGP 版本沿用工程本身能正常编译的配置。

### 4.1 依赖

以下为模块 `build.gradle.kts` 的依赖片段，仓库应已包含 `google()` 和 `mavenCentral()`：

```kotlin
dependencies {
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
```

所有 CameraX 模块使用相同版本。OpenGL ES 和 EGL 类来自 Android 平台，无须第三方 GL 依赖。只做预览可不引入 `camera-video`，本文列出它方便后面扩展录像。

### 4.2 权限与 GLES 能力声明

把以下两项加到 Manifest 的 `<application>` 外：

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:glEsVersion="0x00020000" android:required="true" />
```

在 `<application>` 内注册本文 Activity；若替换工程原来的 launcher，删除原 launcher 的 intent-filter，避免留下两个启动入口：

```xml
<activity
    android:name=".FilterActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

Activity 继承 `AppCompatActivity`，使用工程生成的 AppCompat/Material 兼容主题。这里只做预览，不需要存储权限，也不需要录音权限。

### 4.3 最终文件列表

```text
app/src/main/java/com/example/camerafilter/
├── FilterGlRenderer.kt           EGL 与 GLES
├── TutorialSurfaceProcessor.kt   CameraX Surface 接入及生命周期
├── TutorialCameraEffect.kt       Effect 声明
└── FilterActivity.kt             权限与相机绑定
```

下面四个完整代码块分别对应四个文件。类名特意与本项目现有类区分，避免复制时混用不同版本实现。

## 5. 完整 EGL / GLES 渲染器

先创建 `FilterGlRenderer.kt`。它只负责 GPU：初始化上下文，创建纹理和输出 EGLSurface，绘制一帧，删除 GPU 资源。它不知道 Activity 或 CameraX 请求的生命周期。

```kotlin
package com.example.camerafilter

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class FilterGlRenderer {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var matrixLocation = -1
    private var samplerLocation = -1
    private var intensityLocation = -1

    private val vertices = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val texCoords = floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )

    init {
        try {
            initializeEgl()
            makePbufferCurrent()
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            check(extensions.split(' ').contains("GL_OES_EGL_image_external")) {
                "设备不支持外部 OES 纹理"
            }
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionLocation = attribute("aPosition")
            texCoordLocation = attribute("aTexCoord")
            matrixLocation = uniform("uTexMatrix")
            samplerLocation = uniform("uTexture")
            intensityLocation = uniform("uIntensity")
        } catch (e: Exception) {
            // 初始化进行到一半也需要清理已创建的资源。
            release()
            throw e
        }
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { eglMessage("eglGetDisplay") }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            eglMessage("eglInitialize")
        }
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        check(extensions.split(' ').contains("EGL_ANDROID_presentation_time")) {
            "设备不支持 EGL 时间戳扩展"
        }
        check(extensions.split(' ').contains("EGL_ANDROID_recordable")) {
            "设备不支持可录像 EGL 配置"
        }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, EGL14.EGL_TRUE,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            eglMessage("eglChooseConfig")
        }
        check(count[0] > 0 && configs[0] != null) { "没有符合条件的 EGLConfig" }
        config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { eglMessage("eglCreateContext") }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { eglMessage("eglCreatePbufferSurface") }
    }

    fun makePbufferCurrent() = makeCurrent(pbuffer)

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            eglMessage("eglMakeCurrent")
        }
    }

    fun createInputTexture(): Int {
        makePbufferCurrent()
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        try {
            check(names[0] != 0) { "创建输入纹理失败" }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            checkGl("配置 OES 纹理")
            return names[0]
        } catch (e: Exception) {
            GLES20.glDeleteTextures(1, names, 0)
            throw e
        } finally {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }
    }

    fun deleteInputTexture(textureId: Int) {
        makePbufferCurrent()
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        checkGl("删除输入纹理")
    }

    fun createOutputSurface(surface: Surface): EGLSurface {
        val result = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(result != EGL14.EGL_NO_SURFACE) { eglMessage("eglCreateWindowSurface") }
        return result
    }

    fun destroyOutputSurface(surface: EGLSurface) {
        // 当前目标可能就是将要销毁的输出，先切回自己的离屏目标。
        try {
            makePbufferCurrent()
        } finally {
            check(EGL14.eglDestroySurface(display, surface)) {
                eglMessage("eglDestroySurface")
            }
        }
    }

    fun draw(
        output: EGLSurface,
        width: Int,
        height: Int,
        textureId: Int,
        textureMatrix: FloatArray,
        timestampNs: Long,
        intensity: Float
    ) {
        makeCurrent(output)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        vertices.position(0)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices
        )
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoords
        )
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
        GLES20.glUniform1f(intensityLocation, intensity)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("绘制滤镜帧")

        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        check(EGLExt.eglPresentationTimeANDROID(display, output, timestampNs)) {
            eglMessage("eglPresentationTimeANDROID")
        }
        check(EGL14.eglSwapBuffers(display, output)) { eglMessage("eglSwapBuffers") }
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        // 初始化失败或上下文丢失时不一定还能 makeCurrent；销毁上下文也会回收其 GL 对象。
        if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
            if (EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                if (program != 0) GLES20.glDeleteProgram(program)
            }
        }
        program = 0
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun attribute(name: String): Int =
        GLES20.glGetAttribLocation(program, name).also {
            check(it >= 0) { "找不到 attribute：$name" }
        }

    private fun uniform(name: String): Int =
        GLES20.glGetUniformLocation(program, name).also {
            check(it >= 0) { "找不到 uniform：$name" }
        }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "无法创建 Shader" }
        try {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Shader 编译失败：${GLES20.glGetShaderInfoLog(shader)}"
            }
            return shader
        } catch (e: Exception) {
            GLES20.glDeleteShader(shader)
            throw e
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        var fragment = 0
        var result = 0
        try {
            fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            result = GLES20.glCreateProgram()
            check(result != 0) { "无法创建 Program" }
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "Program 链接失败：${GLES20.glGetProgramInfoLog(result)}"
            }
            return result
        } catch (e: Exception) {
            if (result != 0) GLES20.glDeleteProgram(result)
            throw e
        } finally {
            GLES20.glDeleteShader(vertex)
            if (fragment != 0) GLES20.glDeleteShader(fragment)
        }
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation：GL 错误 0x${Integer.toHexString(error)}"
        }
    }

    private fun eglMessage(operation: String): String =
        "$operation：EGL 错误 0x${Integer.toHexString(EGL14.eglGetError())}"

    companion object {
        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float uIntensity;
            varying vec2 vTexCoord;

            void main() {
                vec4 source = texture2D(uTexture, vTexCoord);
                float gray = dot(source.rgb, vec3(0.299, 0.587, 0.114));
                vec3 filtered = mix(source.rgb, vec3(gray), uIntensity);
                gl_FragColor = vec4(filtered, source.a);
            }
        """.trimIndent()
    }
}
```

这个版本主动要求 `EGL_ANDROID_recordable` 和 `EGL_ANDROID_presentation_time`，以便后面复用到录像。纯预览且目标设备缺少这些扩展时，可分别移除 recordable 配置和时间戳调用；应在初始化时确定能力，而不是每帧忽略错误。普通现代 Android 真机通常具备它们，模拟器需要单独检查。

这里每个 Renderer 独占自己的 EGLContext，便于解释状态。后面若引入共享上下文或第三方渲染器，需要增加状态隔离和同步，不能认为 GL 状态永远保持为本类上一次设置的值。

## 6. 完整 SurfaceProcessor

创建 `TutorialSurfaceProcessor.kt`。先通读成员变量，再看三个关键路径：`onInputSurface()` 建输入、`onFrameAvailable()` 消费帧、`onOutputSurface()` 建输出。

```kotlin
package com.example.camerafilter

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.core.DynamicRange
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class TutorialSurfaceProcessor(
    private val onFatalError: (Throwable) -> Unit
) : SurfaceProcessor {
    private val thread = HandlerThread("CameraFilterGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { command ->
        if (!handler.post(command)) {
            throw RejectedExecutionException("相机滤镜 GL 线程已停止")
        }
    }

    // AtomicBoolean 只负责跨线程阻止重复 release 和退出后的参数更新。
    private val releaseStarted = AtomicBoolean(false)
    private val releasedFuture = CompletableFuture<Void>()

    // 以下状态仅由 GL 线程访问。
    private var closing = false
    private var fatalError: Throwable? = null
    private var renderer: FilterGlRenderer? = null
    private val inputs = linkedSetOf<Input>()
    private val outputs = linkedMapOf<SurfaceOutput, Output>()
    private var currentInput: Input? = null
    private var intensity = 1f
    private val inputMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)

    private class Input(
        val request: SurfaceRequest,
        val textureId: Int,
        val texture: SurfaceTexture,
        val surface: Surface
    ) {
        var submitted = false
        var hasFrame = false
        var lastTimestampNs: Long? = null
    }

    private class Output(
        val cameraOutput: SurfaceOutput,
        val eglSurface: EGLSurface
    )

    override fun onInputSurface(request: SurfaceRequest) {
        checkGlThread()
        if (closing || releaseStarted.get()) {
            request.willNotProvideSurface()
            return
        }
        fatalError?.let {
            request.willNotProvideSurface()
            throw processingException(it)
        }
        var input: Input? = null
        try {
            check(request.dynamicRange == DynamicRange.SDR) {
                "教学处理器只支持 8 位 SDR，请关闭 HDR"
            }
            input = createInput(request)
            inputs += input
            currentInput = input
            val ownedInput = input
            ownedInput.texture.setOnFrameAvailableListener(
                { onFrameAvailable(ownedInput) }, handler
            )
            // 只提交一次。提交后必须等这个回调，不能在 release 中抢先销毁。
            ownedInput.submitted = true
            request.provideSurface(ownedInput.surface, executor) { result ->
                Log.d(TAG, "输入归还：${result.resultCode}")
                releaseInput(ownedInput)
            }
        } catch (e: Exception) {
            if (input != null && !input.submitted) releaseInput(input)
            request.willNotProvideSurface()
            fail(e)
            throw processingException(e)
        }
    }

    private fun createInput(request: SurfaceRequest): Input {
        val gl = ensureRenderer()
        gl.makePbufferCurrent()
        val textureId = gl.createInputTexture()
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        try {
            val createdTexture = SurfaceTexture(textureId)
            texture = createdTexture
            val size = request.resolution
            createdTexture.setDefaultBufferSize(size.width, size.height)
            val createdSurface = Surface(createdTexture)
            surface = createdSurface
            return Input(request, textureId, createdTexture, createdSurface)
        } catch (e: Exception) {
            surface?.release()
            texture?.release()
            gl.deleteInputTexture(textureId)
            throw e
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        checkGlThread()
        if (closing || releaseStarted.get()) {
            surfaceOutput.close()
            return
        }
        fatalError?.let {
            surfaceOutput.close()
            throw processingException(it)
        }
        try {
            val gl = ensureRenderer()
            check(!outputs.containsKey(surfaceOutput)) { "重复提供同一个输出对象" }
            val surface = surfaceOutput.getSurface(executor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    releaseOutput(event.surfaceOutput)
                }
            }
            val output = Output(surfaceOutput, gl.createOutputSurface(surface))
            outputs[surfaceOutput] = output
            // 输出可能晚于第一帧到达，立即把已经取得的最新帧画过去。
            // 只补画新输出，避免旧录像输出重复接收同一时间戳。
            currentInput?.takeIf { it.hasFrame }?.let { drawToOutput(it, output) }
        } catch (e: Exception) {
            if (outputs.containsKey(surfaceOutput)) releaseOutput(surfaceOutput)
            else surfaceOutput.close()
            fail(e)
            throw processingException(e)
        }
    }

    private fun onFrameAvailable(input: Input) {
        checkGlThread()
        if (closing || fatalError != null || input !in inputs) return
        try {
            val gl = requireNotNull(renderer)
            // 没有输出时也能取得帧；不要把 updateTexImage 绑死在输出存在的条件上。
            gl.makePbufferCurrent()
            input.texture.updateTexImage()
            // 排队通知可能多于实际取得的新帧，不重复提交同一时间戳。
            val timestampNs = input.texture.timestamp
            if (input.lastTimestampNs == timestampNs) return
            input.lastTimestampNs = timestampNs
            input.hasFrame = true
            // 旧输入的通知仍可能排在队列里，消费后不覆盖新输入的显示。
            if (input !== currentInput) return
            drawToAllOutputs(input)
        } catch (e: Exception) {
            // 帧回调不在 CameraX 的两个入口中，不能只抛异常使 HandlerThread 崩溃。
            fail(e)
        }
    }

    private fun drawToAllOutputs(input: Input) {
        for (output in outputs.values) drawToOutput(input, output)
    }

    private fun drawToOutput(input: Input, output: Output) {
        val gl = requireNotNull(renderer)
        input.texture.getTransformMatrix(inputMatrix)
        val timestampNs = input.texture.timestamp
        output.cameraOutput.updateTransformMatrix(outputMatrix, inputMatrix)
        val size = output.cameraOutput.size
        gl.draw(
            output.eglSurface, size.width, size.height,
            input.textureId, outputMatrix, timestampNs, intensity
        )
    }

    fun setIntensity(value: Float) {
        if (releaseStarted.get()) return
        executor.execute {
            if (!closing && fatalError == null) intensity = value.coerceIn(0f, 1f)
        }
    }

    private fun ensureRenderer(): FilterGlRenderer =
        renderer ?: FilterGlRenderer().also { renderer = it }

    private fun releaseInput(input: Input) {
        checkGlThread()
        // 从集合移除既是幂等保护，也让旧输入回调不影响新输入。
        if (!inputs.remove(input)) return
        if (currentInput === input) currentInput = null
        cleanup("移除帧监听") { input.texture.setOnFrameAvailableListener(null) }
        cleanup("释放输入 Surface") { input.surface.release() }
        cleanup("释放 SurfaceTexture") { input.texture.release() }
        cleanup("删除 OES 纹理") { renderer?.deleteInputTexture(input.textureId) }
        finishReleaseIfReady()
    }

    private fun releaseOutput(surfaceOutput: SurfaceOutput) {
        checkGlThread()
        val output = outputs.remove(surfaceOutput) ?: return
        try {
            renderer?.destroyOutputSurface(output.eglSurface)
        } catch (e: Exception) {
            Log.e(TAG, "销毁输出 EGLSurface 失败", e)
        } finally {
            // 不释放 CameraX 拥有的 Android Surface。
            output.cameraOutput.close()
        }
    }

    private fun fail(error: Throwable) {
        if (fatalError != null) return
        fatalError = error
        Log.e(TAG, "滤镜处理失败", error)
        outputs.keys.toList().forEach { releaseOutput(it) }
        // 请求重建；下一次入口会抛 ProcessingException。此处仍不提前回收输入。
        inputs.toList().forEach { it.request.invalidate() }
        try {
            onFatalError(error)
        } catch (listenerError: Exception) {
            Log.e(TAG, "应用错误监听失败", listenerError)
        }
    }

    // 所有者先解绑相机，再请求释放；方法异步返回，不能在主线程阻塞等待。
    fun release(): CompletableFuture<Void> {
        if (releaseStarted.compareAndSet(false, true)) {
            executor.execute {
                closing = true
                outputs.keys.toList().forEach { releaseOutput(it) }
                finishReleaseIfReady()
            }
        }
        return releasedFuture
    }

    private fun finishReleaseIfReady() {
        if (!closing || inputs.isNotEmpty()) return
        cleanup("释放 EGL") { renderer?.release() }
        renderer = null
        releasedFuture.complete(null)
        // 最后一个输入已归还，才可以停止接收释放回调的线程。
        thread.quitSafely()
    }

    private fun cleanup(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Log.e(TAG, operation, e)
        }
    }

    private fun checkGlThread() {
        check(Looper.myLooper() === thread.looper) { "必须在 CameraFilterGL 线程执行" }
    }

    private fun processingException(cause: Throwable): ProcessingException =
        ProcessingException().apply { initCause(cause) }

    companion object {
        private const val TAG = "TutorialProcessor"
    }
}
```

`ProcessingException` 在 1.4.2 的类定义只有默认无参构造函数，所以这里使用 `ProcessingException().apply { initCause(cause) }`，不要照其他版本或示意代码写出不存在的带 cause 构造函数。

本例采用明确的失败策略：GL/EGL 错误后停止整个滤镜管线，通知所有者解绑并退出。它没有自动恢复 EGL_CONTEXT_LOST，也没有让一个失效输出拖着其余输出继续运行；第 18 节说明如何演进这些策略。

## 7. Effect 和 Activity 接入

### 7.1 Effect 是一个薄封装

创建 `TutorialCameraEffect.kt`：

```kotlin
package com.example.camerafilter

import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer

class TutorialCameraEffect(
    processor: TutorialSurfaceProcessor,
    targets: Int = CameraEffect.PREVIEW,
    onError: (Throwable) -> Unit
) : CameraEffect(
    targets,
    processor.executor,
    processor,
    Consumer<Throwable> { onError(it) }
)
```

处理器入口、输入归还回调、输出关闭回调、帧监听都使用同一 GL 线程。这比在普通线程池里临时切换 EGLContext 更容易保证正确性。

### 7.2 最小可运行 Activity

创建 `FilterActivity.kt`。为了让示例独立，这里用代码创建 `PreviewView`，无须 XML 布局。相机权限采用平台回调，避免依赖额外 Activity 扩展。

```kotlin
package com.example.camerafilter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class FilterActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var processor: TutorialSurfaceProcessor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        setContentView(previewView)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else {
            Toast.makeText(this, "需要相机权限才能显示滤镜", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val mainExecutor = ContextCompat.getMainExecutor(this)
        val future = ProcessCameraProvider.getInstance(applicationContext)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val reportError: (Throwable) -> Unit = { error ->
                    // 此回调可能来自 GL 线程，UI 操作必须切回主线程。
                    mainExecutor.execute {
                        Log.e("FilterActivity", "相机滤镜失败", error)
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this, "滤镜失败，请查看日志", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
                val glProcessor = TutorialSurfaceProcessor(reportError)
                processor = glProcessor
                val cameraPreview = Preview.Builder()
                    .setDynamicRange(DynamicRange.SDR)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                preview = cameraPreview
                val effect = TutorialCameraEffect(glProcessor, onError = reportError)
                val group = UseCaseGroup.Builder()
                    .addUseCase(cameraPreview)
                    .addEffect(effect)
                    .build()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, group
                )
            } catch (e: Exception) {
                Log.e("FilterActivity", "相机初始化或绑定失败", e)
                stopCamera()
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_LONG).show()
                finish()
            }
        }, mainExecutor)
    }

    private fun stopCamera() {
        // 只解绑本页面拥有的 UseCase。
        preview?.let { provider?.unbind(it) }
        preview = null
        processor?.release()
        processor = null
    }

    override fun onDestroy() {
        stopCamera()
        super.onDestroy()
    }
}
```

运行后应看到灰度实时预览。默认 `intensity = 1f` 为完全灰度；调用 `processor?.setIntensity(0f)` 后恢复原色，`0.5f` 为一半强度。此示例为清晰展示算法选择 `FIT_CENTER`；它可能留黑边，不代表 GL 未铺满输出缓冲区。

`COMPATIBLE` 通常选择 TextureView 路径，方便教学时排查视图层变换；此方案不以 TextureView 为必需条件，也可以评估 `PERFORMANCE` 模式。

### 7.3 已使用 LifecycleCameraController 的项目怎么接

如果项目原来就使用 Controller，无须切换到 Provider。下面是已有 `LifecycleOwner` 和 `PreviewView` 时的替代绑定片段，**不要与上面的 Provider 同时操作同一预览**：

```kotlin
val glProcessor = TutorialSurfaceProcessor { error ->
    Log.e("CameraFilter", "处理失败", error)
    // 应用在主线程解绑 Controller，并请求 glProcessor.release()。
}
val effect = TutorialCameraEffect(glProcessor) { error ->
    Log.e("CameraFilter", "Effect 入口失败", error)
}
val controller = LifecycleCameraController(context).apply {
    // Preview 总会启用。0 表示关闭额外的拍照、分析、录像 UseCase。
    setEnabledUseCases(0)
    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    setEffects(setOf(effect))
}
previewView.controller = controller
controller.bindToLifecycle(lifecycleOwner)

// 页面真正结束时，在主线程执行：
// controller.unbind()
// glProcessor.release()
```

这个片段需要导入 `androidx.camera.view.LifecycleCameraController`、`androidx.camera.core.CameraSelector`、`android.util.Log`。Controller 的 `setEffects()` 是 CameraX Effect；不要把 Media3 普通 `Effect` 列表直接传给它。

## 8. SurfaceProcessor 为什么要这样实现

### 8.1 专用 GL 线程是正确性的基础

`SurfaceTexture.updateTexImage()` 必须在持有对应 EGLContext 的线程上执行。`glCreateProgram()`、纹理创建、draw 和 GL 对象删除也需要正确上下文。

EGLContext 在某一时刻只能作为一个线程的 current context。直接使用 `Executors.newFixedThreadPool()` 可能导致输入回调在 A 线程创建纹理、帧通知在 B 线程更新纹理、输出回调在 C 线程绘制，结果就会黑屏或出现 `GL_INVALID_OPERATION`。

本例建立如下关系：

```text
CameraEffect.executor ────────────────┐
provideSurface 的 result executor ──┤
getSurface 的 event executor ────────┤──> CameraFilterGL / 同一个 Looper
SurfaceTexture 帧监听 Handler ──────┤
UI 参数更新任务 ─────────────────────┘
```

执行器始终 `post()`，即使调用者就在 GL 线程上也不立即内联执行。这避免 `provideSurface()` 或 `getSurface()` 内部同步触发通知时，在资源尚未登记完毕前重入释放方法。

`executor` 是入口调度器，不是“执行一次 GPU draw 的魔法对象”。CameraX 把回调放到它上面；你仍然必须 `eglMakeCurrent()`。

### 8.2 输入创建的先后顺序

```text
确认请求可以接收且是 SDR
  → 初始化 EGLContext，并 makeCurrent
  → glGenTextures 创建 OES 纹理
  → SurfaceTexture(textureId)
  → setDefaultBufferSize(request.resolution)
  → Surface(surfaceTexture)
  → 注册帧监听并保存资源记录
  → request.provideSurface(...)
```

为什么这样排列：

- 没有 current EGLContext，无法正确创建 GL 纹理。
- SurfaceTexture 要与已经创建的纹理关联。
- 缓冲区尺寸需要与 CameraX 请求匹配，不能随意使用屏幕尺寸。
- 帧监听应在提交前准备好，避免相机开始生产时错过初始化阶段。
- 完成提交后，资源的寿命由请求的结果回调约束。

`setDefaultBufferSize()` 设置的是期望缓冲尺寸，不代表所有 Surface 生产者都只会服从这个默认值。但对本文 CameraX 输入，按请求尺寸设置是必要的接入步骤。

### 8.3 收到一帧后的先后顺序

```text
在 GL 线程收到通知
  → 确认输入仍有效
  → makePbufferCurrent()
  → updateTexImage()
  → 标记已有帧
  → getTransformMatrix()
  → 读取同一帧的 timestamp
  → 对每个输出组合矩阵并绘制
  → 设置输出时间戳
  → eglSwapBuffers()
```

`updateTexImage()` 放在取得矩阵和时间戳前面，是因为矩阵和时间戳应描述**刚取得的那一帧**。顺序颠倒会拿到上一帧信息。

一次通知不保证对应一次必须保留的帧。SurfaceTexture 的 `updateTexImage()` 可以选择最新图像并跳过较旧图像，适合低延迟预览。排队通知还可能取得同一个最新时间戳，因此本例按输入记录上一次时间戳，避免重复提交。不要将通知次数当作严格录像帧数，也不要承诺无丢帧。

本例即使没有输出，也在 Pbuffer 上消费输入。否则，在视图稍后创建或暂时移除时，你可能长期不消费输入，使生产端受到 BufferQueue 的回压。

旧输入尚未归还时仍可能送来通知。代码捕获 `ownedInput`，消费对应纹理，但只有 `input === currentInput` 才展示它。这样不会因为一个旧输入的排队通知，错误地去更新新输入纹理。

### 8.4 为何使用 Input / Output 资源记录

一个输入不是只有 `textureId`，而是一组一起生存和销毁的资源：

```text
Input = SurfaceRequest + OES textureId + SurfaceTexture + Surface
Output = SurfaceOutput + 我们创建的 EGLSurface
```

资源记录让关闭事件能精确找到对应资源。`inputs.remove(input)` 和 `outputs.remove(surfaceOutput)` 是幂等门：同一对象再次关闭不会重复删纹理或重复销毁 EGLSurface。

如果只保存 `currentInput`，新请求覆盖它后，旧请求的 resultListener 就可能删除新纹理；如果只保存 `currentOutput`，旧输出的关闭事件可能把新 EGLSurface 销毁。

### 8.5 初始化与帧处理的异常不同

`onInputSurface()` / `onOutputSurface()` 入口应将创建失败转换成 `ProcessingException`。CameraX 1.4.2 的 `SurfaceProcessorWithExecutor` 捕获这种异常并调用 Effect 的错误监听；它不会替你捕获所有随意抛出的 RuntimeException。

帧监听是 SurfaceTexture 的回调，**不在这个包装器的 try/catch 里面**。因此本例在帧回调里捕获异常，调用应用监听，并 `invalidate()` 输入让下一次入口明确报告失败。

发生不可恢复错误时应用仍需要决定解绑、重试或关闭页面。不能只打印一条日志继续向失效 Surface 写数据。本文使用退出页面的清晰策略，方便新手定位第一处错误。

## 9. EGL 每一段代码为什么这样写

### 9.1 eglChooseConfig 的属性

```kotlin
EGL14.EGL_SURFACE_TYPE,
EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT
```

这个 renderer 同时创建窗口 Surface 和 Pbuffer，所以配置必须明确支持两者。只写 `EGL_WINDOW_BIT` 然后创建 Pbuffer，在部分驱动上会失败；不能因为某台手机刚好能跑就省略。

| 属性 | 本文值 | 理由 |
|---|---|---|
| `EGL_RED/GREEN/BLUE/ALPHA_SIZE` | 每通道 8 位 | 8 位 SDR 的起点，支持 RGBA 输出 |
| `EGL_RENDERABLE_TYPE` | `EGL_OPENGL_ES2_BIT` | shader 使用 ES 2.0 / GLSL ES 1.00 语法 |
| `EGL_SURFACE_TYPE` | Window 与 Pbuffer | 既能画到下游，也能无输出时管理 GL |
| `EGL_RECORDABLE_ANDROID` | `EGL_TRUE` | 选择适合录像 Surface 使用的配置 |
| `EGL_NONE` | 结束标记 | EGL 属性列表是 key/value 列表，需要终止符 |

`eglChooseConfig()` 返回 true 只表示查询本身成功，仍要检查 `count[0] > 0` 和 `configs[0] != null`。

### 9.2 为什么创建 1×1 Pbuffer

初始化时输出可能还没来。删除一个窗口 Surface 后也需要另一个 current 绘制目标。1×1 Pbuffer 提供一个很小的离屏落脚点：

- 在输出到达前编译 shader 和创建输入纹理。
- 没有输出时调用 `updateTexImage()`。
- 删除输出前切回 Pbuffer，避免销毁仍作为 current 目标的 Surface。
- 回收纹理和 program 时保持上下文可用。

它不是保存相机每一帧的滤镜结果，也不是后面多通道滤镜中的 FBO。这里不往它画相机画面。

### 9.3 eglMakeCurrent 的四个实参

```kotlin
EGL14.eglMakeCurrent(display, drawSurface, readSurface, context)
```

本文将 draw 和 read 都设为同一个 EGLSurface。它指定“当前线程之后的 GL 调用，在这个上下文中，以这个目标绘制/读取”。它不上传图像，也不提交帧。

一个上下文可以先画输出 A，再切换到输出 B。纹理和 program 仍属于同一个上下文，不需要重复创建；viewport 和每个输出的变换矩阵要重新设置。

### 9.4 时间戳和 swap 的关系

```kotlin
EGLExt.eglPresentationTimeANDROID(display, output, timestampNs)
EGL14.eglSwapBuffers(display, output)
```

第一句设置下一次提交的帧的展示时间，单位是**纳秒**。第二句把绘制结果交给 Surface 队列下游。对录像及经过共享处理的下游，保留输入时间戳很重要；预览也可以沿用这条链路。

使用 `SurfaceTexture.timestamp`，不要用 `System.currentTimeMillis()`：墙上时间单位是毫秒，可能跳变，而且不是这张相机帧的生产时间。

同一帧绘制到多个输出应沿用同一个 timestamp。参数调整时，本例等下一张相机帧再显示新强度，避免为录像反复提交同一帧的重复时间戳。如果只做预览并想暂停时重绘，可以单独设计预览重绘路径。

`eglSwapBuffers()` 不保证 GPU 已完全执行完所有工作，也不能当作 `glFinish()`。它可能因下游消费慢而阻塞，影响 GL 线程处理下一帧。

## 10. GLES 渲染代码逐项解释

### 10.1 顶点坐标为何是 -1 到 1

```kotlin
floatArrayOf(
    -1f, -1f,  // 左下
     1f, -1f,  // 右下
    -1f,  1f,  // 左上
     1f,  1f   // 右上
)
```

这四点经过 vertex shader 写入 `gl_Position`。这里 z=0、w=1，所以经过透视除法后的 NDC 正好覆盖横纵 -1～1，映射到整个 viewport。

GL 坐标不等于 Android View 的像素坐标。GL 顶点的范围描述相对位置，viewport 再将它映射成实际像素。

### 10.2 纹理坐标为何是 0 到 1

```kotlin
floatArrayOf(
    0f, 0f,  // 左下顶点对应的基础采样坐标
    1f, 0f,  // 右下
    0f, 1f,  // 左上
    1f, 1f   // 右上
)
```

这些是**变换前**的归一化采样坐标。顶点坐标决定往哪里画，纹理坐标决定从相机图像哪里取色。它们是一一配对的两套数组，不能把其中一个当成另一个。

采样坐标最终还会乘 `uTexMatrix`，所以不需要为了不同手机随意交换四组基础坐标来纠正朝向。

### 10.3 为什么 allocateDirect、nativeOrder 和 position(0)

Android 的 GLES Buffer 接口需要可以访问的原生内存，使用 direct buffer 是可靠的方式。`Float` 占 4 字节，`values.size * 4` 才是正确容量。`nativeOrder()` 保证 float 的字节序按本机解释。

`put()` 会把 buffer 的当前位置推进到末尾，之后要 `position(0)`，让 `glVertexAttribPointer()` 从第一个数据读取。它不是复制完数据后自动归零。

本例 buffers 和矩阵数组都只创建一次，避免每帧产生垃圾和触发 GC。不要在 draw 中不断创建新的 `FloatArray`、ByteBuffer 或重新编译 shader。

### 10.4 shader 编译和 program 链接为什么分两步

```text
字符串
  → glShaderSource
  → glCompileShader
  → 检查 GL_COMPILE_STATUS / shader info log
顶点 shader + 片元 shader
  → glAttachShader
  → glLinkProgram
  → 检查 GL_LINK_STATUS / program info log
```

shader 编译检查单个阶段的语法。program 链接检查两个阶段能否配套，例如 varying 的名字和类型是否一致。二者都必须检查。

链接完成后可以删除 shader 对象；已经链接的 program 仍可使用。删除 program 则应等到它不再被用来绘制。

`glGetAttribLocation()` 和 `glGetUniformLocation()` 返回的是当前 program 中的变量位置。它们不是纹理 ID，也不是数组下标。未使用的变量可能被编译器优化掉，导致 location = -1；更换 program 后必须重新查询位置。

### 10.5 attribute、uniform、varying 分别是什么

| GLSL ES 1.00 关键字 | 数据变化范围 | 本文变量 |
|---|---|---|
| `attribute` | 每个顶点可不同，由 CPU 顶点数组提供 | `aPosition`、`aTexCoord` |
| `uniform` | 同一次 draw 中保持一致 | `uTexMatrix`、`uTexture`、`uIntensity` |
| `varying` | 顶点阶段输出，光栅化后插值交给片元阶段 | `vTexCoord` |

片元 shader 需要显式声明 float 精度。`mediump` 对本例的基础颜色混合通常足够；大尺寸 LUT 或复杂坐标运算可能需要 `highp`，ES 2.0 片元 highp 的设备能力需要查询或准备回退。

迁移 ES 3.x 后 shader 语法改为 `in` / `out` 等，而且 OES 扩展也可能需要 `GL_OES_EGL_image_external_essl3`。不能仅把 Context 版本改成 3，就混用所有语法。

### 10.6 glVertexAttribPointer 每个参数的含义

```kotlin
GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, buffer)
```

| 参数 | 本文值 | 含义 |
|---|---|---|
| index | 查询到的 location | 哪个 attribute 使用这组数据 |
| size | 2 | 每个顶点提供 x、y 或 s、t 两个分量 |
| type | `GL_FLOAT` | 数据元素是 32 位浮点 |
| normalized | false | float 数据不需要整数归一化 |
| stride | 0 | 紧密排列，下一顶点紧接前一个顶点 |
| buffer | direct FloatBuffer | 顶点属性的数据来源 |

为什么 `aPosition` 是 `vec4`，CPU 却只传 2 个分量？未提供的 z 默认补 0、w 默认补 1，所以得到 `(x, y, 0, 1)`。

`glEnableVertexAttribArray()` 启用“从数组取值”，否则 shader 会使用常量属性值，矩形可能退化为一点。

本例没使用 VBO，`GL_ARRAY_BUFFER` 保持独占上下文的默认绑定 0。将它嵌入有 VBO 的渲染系统时，要先显式 `glBindBuffer(GL_ARRAY_BUFFER, 0)` 或改成 VBO offset 重载，不能混淆 Java Buffer 和 GPU 缓冲偏移。

### 10.7 GL_TRIANGLE_STRIP 为什么只需要四个顶点

按左下、右下、左上、右上排列，triangle strip 用相邻的三个顶点组成两个三角形，合起来覆盖矩形。

```text
左上(2) ───────── 右上(3)
  │          ／      │
  │       ／         │
  │    ／            │
左下(0) ───────── 右下(1)
```

`glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)` 的 0 是首个顶点，4 是顶点数，不是三角形数，也不是数组 float 数。

若使用 `GL_TRIANGLES`，需要 6 个顶点或索引表达两个三角形。渲染模式和顶点排列应一起设计。

### 10.8 textureId、纹理单元和 sampler location 是三件事

```kotlin
GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
GLES20.glUniform1i(samplerLocation, 0)
```

- `textureId`：`glGenTextures()` 创建的纹理对象名称。
- `GL_TEXTURE0`：选择纹理单元 0，让后续 bind 作用于这个单元。
- `samplerLocation`：program 中 `uTexture` 的位置。
- `glUniform1i(..., 0)`：告诉 shader 的 sampler 去**纹理单元 0**取纹理。

最后一个参数必须是整数单元编号 0，不能传 `textureId`，也不能传枚举值 `GL_TEXTURE0`。添加 LUT 时可将它绑定到单元 1，并向 LUT sampler 传 1。

### 10.9 为什么 GL_LINEAR 和 GL_CLAMP_TO_EDGE

放大、缩小和坐标变换后，采样点不一定落在像素中心，`GL_LINEAR` 做临近采样插值，让画面平滑。`GL_NEAREST` 用于有意保留像素块的效果。

`GL_CLAMP_TO_EDGE` 把边界外采样限制在边缘，避免把画面另一侧卷过来。外部 OES 纹理的 wrapping 有限制，也不适合使用 mipmap 采样模式；本例明确设置 MIN/MAG 为 LINEAR，避免默认 min filter 对 mipmap 的要求。

### 10.10 viewport、clear、状态和 framebuffer

`glViewport(0, 0, outputWidth, outputHeight)` 选择当前输出的完整缓冲区。新的 EGLSurface 并不保证你的旧 viewport 自动调整成新尺寸。

`glBindFramebuffer(GL_FRAMEBUFFER, 0)` 指向当前 EGLSurface 的默认 framebuffer。后面使用 FBO 后，如果忘记恢复 0，可能一直画在离屏纹理里，屏幕保持黑色。

clear 把当前颜色缓冲清为不透明黑。本文矩形正常会覆盖整个 viewport，clear 更多是教学和扩展时的安全背景；GL 状态稳定且确定完整覆盖时，可评估省略以减少工作。

关闭 blend、depth test、scissor test，使当前 draw 不被前一段处理遗留状态干扰。以后加水印时要明确开启 blending，再为下一帧相机底图恢复状态。

通常不需要每帧 `glFinish()`，它强制 CPU 等待 GPU，容易降低吞吐。单上下文中 GL 命令天然有顺序；跨上下文、跨 API 或读回数据时才需要认真设计 fence 与同步。

## 11. 坐标变换：最容易出现倒置的地方

### 11.1 三套坐标分别负责什么

| 坐标 | 范围与用途 | 本文数据 |
|---|---|---|
| 顶点 / NDC | -1～1，决定输出几何覆盖 | `vertices` |
| 基础 UV | 0～1，表示输出位置对应的标准采样位置 | `texCoords` |
| 实际采样 UV | 经变换后的相机纹理坐标 | `uTexMatrix * vec4(uv, 0, 1)` |

相机帧可能带有缓冲变换，CameraX 还需要根据旋转、镜像、ViewPort、输出尺寸和设备修正计算附加变换。两个来源都要考虑。

### 11.2 正确矩阵流程

```kotlin
surfaceTexture.updateTexImage()
surfaceTexture.getTransformMatrix(inputMatrix)
surfaceOutput.updateTransformMatrix(outputMatrix, inputMatrix)
GLES20.glUniformMatrix4fv(matrixLocation, 1, false, outputMatrix, 0)
```

vertex shader 再使用组合结果：

```glsl
vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
```

**`updateTransformMatrix()` 已包含传入的原始矩阵。** 不要先在 shader 乘一次 `inputMatrix`，再乘一次组合结果；那会重复应用。

CameraX 1.4.2 的 `SurfaceOutputImpl` 内部组合顺序是 `output = input × additional`。按列向量理解，基础 UV 先经过 additional，再经过 input。通常不需要自己重新乘这两个矩阵，直接使用 API 返回值最可靠。

### 11.3 为什么是 4×4 而不是 2×2

二维坐标除了旋转、缩放，还需要平移来完成裁剪偏移。普通 2×2 矩阵不能表达平移，因此使用齐次坐标 `(s, t, 0, 1)` 和 4×4 矩阵，方便直接与 Android GL API 配套。

数组采用列主序。`glUniformMatrix4fv(..., false, ...)` 的 false 表示不转置；OpenGL ES 的 transpose 参数应为 false。不要把 `android.graphics.Matrix` 的 3×3 数组直接塞进这里。

### 11.4 不要给所有设备统一补一个 Y 翻转

Android 图像和 OpenGL 坐标原点不同，常见教程因此手写 `1.0 - uv.y`。但 SurfaceTexture 和 CameraX 的矩阵已经处理它们所需的变换；再统一补翻转可能导致原来正确的设备倒置。

前置相机预览的镜像和录像保存的镜像也可能是不同策略。先确认 `SurfaceOutput` 返回矩阵和目标配置，再决定是否增加用户自定义镜像，不要用“前置相机”作为无条件双重镜像开关。

### 11.5 ViewPort 和 PreviewView.scaleType 是不同层次

`PreviewView.scaleType` 控制滤镜输出如何在 View 中显示。`UseCaseGroup.setViewPort()` 用于对齐多个 UseCase 的可见区域，影响 CameraX 管线中的裁剪。

需要预览、录像、拍照区域一致时，可在 PreviewView 完成布局且宽高非零后获取 `previewView.viewPort`，然后在构建 group 时设置：

```kotlin
val groupBuilder = UseCaseGroup.Builder()
    .addUseCase(preview)
    .addEffect(effect)
previewView.viewPort?.let { groupBuilder.setViewPort(it) }
val group = groupBuilder.build()
```

这是替换 Activity 内 group 构建部分的片段。屏幕方向、布局比例变化后是否需要重建 group，应按产品设计处理。不要每一帧读取 View 属性或重新绑定相机。

### 11.6 如何添加自己的 UV 变换

例如围绕中心进行用户控制的放大，可定义一个输出 UV 空间的自定义矩阵 C，组合为 `final = outputMatrix × C`。它表示先改变“我要取哪一块输出区域”，再映射到实际相机纹理。

```kotlin
val custom = FloatArray(16)
val finalMatrix = FloatArray(16)
android.opengl.Matrix.setIdentityM(custom, 0)
android.opengl.Matrix.translateM(custom, 0, 0.5f, 0.5f, 0f)
android.opengl.Matrix.scaleM(custom, 0, 0.5f, 0.5f, 1f)
android.opengl.Matrix.translateM(custom, 0, -0.5f, -0.5f, 0f)
android.opengl.Matrix.multiplyMM(finalMatrix, 0, outputMatrix, 0, custom, 0)
```

缩小采样 UV 区域到中央一半，会让内容看起来放大。这里的数组应作为成员复用，片段只是演示组合。若希望修改相机变焦，优先使用 CameraControl 的 zoom；那与滤镜中的 UV 裁剪是不同能力。

## 12. 动态滤镜和更多颜色效果

### 12.1 参数从 UI 到 GPU 的完整路径

```text
滑块事件
  → processor.setIntensity(value)
  → GL Executor 排队
  → 更新 GL 线程拥有的 intensity
  → 下一帧 glUseProgram
  → glUniform1f
  → shader 使用参数
```

`setIntensity()` 只修改 CPU 状态，`glUniform1f()` 才把值写到当前 program 的 uniform。uniform 值并不会因为 Kotlin 成员变量改变而自动更新。

UI 不应直接调用 `glUniform1f()`：UI 线程没有本例 current EGLContext，而且当前 program 也可能不对。

灰度强度使用 `mix(source.rgb, grayColor, intensity)`：0 保留原色，1 完全灰度，之间做线性插值。`coerceIn()` 限制合法区间。

### 12.2 一个 program 支持多个滤镜模式

新增 `uniform int uMode`，片元颜色计算可以写成如下 GLSL **函数片段**。需将函数放在 shader 的 `main()` 前，并在 main 内调用，同时在 Renderer 查询位置、每帧 `glUniform1i()` 上传模式：

```glsl
vec3 applyFilter(vec3 rgb, int mode) {
    if (mode == 1) {
        float gray = dot(rgb, vec3(0.299, 0.587, 0.114));
        return vec3(gray);
    }
    if (mode == 2) {
        return vec3(1.0) - rgb;
    }
    if (mode == 3) {
        return clamp(vec3(
            dot(rgb, vec3(0.393, 0.769, 0.189)),
            dot(rgb, vec3(0.349, 0.686, 0.168)),
            dot(rgb, vec3(0.272, 0.534, 0.131))
        ), 0.0, 1.0);
    }
    return rgb;
}
```

主函数的计算部分改为：

```glsl
vec4 source = texture2D(uTexture, vTexCoord);
vec3 filtered = applyFilter(source.rgb, uMode);
gl_FragColor = vec4(mix(source.rgb, filtered, uIntensity), source.a);
```

统一的 mode 在同一次 draw 中相同，这种少量分支适合简单滤镜。滤镜数量多或每个算法采样次数差别大时，可缓存多个 program 切换；不要在每帧按模式重新编译。

### 12.3 亮度、对比度、饱和度

在 shader 中增加对应 uniform，可以使用如下计算片段：

```glsl
vec3 rgb = source.rgb;
rgb += vec3(uBrightness);
rgb = (rgb - vec3(0.5)) * uContrast + vec3(0.5);
float gray = dot(rgb, vec3(0.299, 0.587, 0.114));
rgb = mix(vec3(gray), rgb, uSaturation);
gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
```

建议初始值：brightness=0、contrast=1、saturation=1。参数范围是产品选择，需在 UI 限制。

顺序会改变结果：先调亮度再调对比度，与反过来不同。多个点颜色运算可以合并进一个 shader，节约多次全图读写。要严格色彩处理时，在适当的线性/显示编码空间执行相应算法。

### 12.4 扩展参数时需要一起改的地方

1. shader 声明 uniform 并参与输出，避免被优化掉。
2. Renderer 初始化时查询新 program 的 location。
3. Processor 保存参数，只在 GL 线程更新。
4. draw 的签名接收参数，在 `glUseProgram()` 后上传。
5. UI 只调用 Processor 的参数方法。

新增一个 uniform 而忘记查询或上传，可能看似“算法没生效”；新增查询却没在 shader 使用，会得到 location=-1。

## 13. 扩展到预览与录像

### 13.1 先明确 Effect 不会自动开启录像

将目标换为 `PREVIEW or VIDEO_CAPTURE`，并将实际 `VideoCapture<Recorder>` 与 Preview 一起绑定。本例 Renderer 已包含 recordable 配置和输入时间戳透传。

下列片段替换 Activity 中创建 Preview 后、绑定之前的 group 代码。需将 `recorder`、`videoCapture` 保存为 Activity 成员：

```kotlin
val recorder = Recorder.Builder()
    .setQualitySelector(QualitySelector.from(Quality.HD))
    .build()
val videoCapture = VideoCapture.Builder(recorder)
    .setDynamicRange(DynamicRange.SDR)
    .build()
val effect = TutorialCameraEffect(
    glProcessor,
    targets = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
    onError = reportError
)
val group = UseCaseGroup.Builder()
    .addUseCase(cameraPreview)
    .addUseCase(videoCapture)
    .addEffect(effect)
    .build()
cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
```

导入 `androidx.camera.video.Recorder`、`QualitySelector`、`Quality`、`VideoCapture` 和 `androidx.camera.core.CameraEffect`。示例选择 HD；需要设备降级策略时使用 `QualitySelector` 的 fallback 配置，并先查询支持质量。

### 13.2 开始和停止录制

以下是 Activity 中的录制片段：保存 `activeRecording: Recording?` 为成员，按钮调用这段逻辑。导入 `File`、`Recording`、`FileOutputOptions`、`VideoRecordEvent`：

```kotlin
if (activeRecording == null) {
    val options = FileOutputOptions.Builder(
        File(filesDir, "filter-${System.currentTimeMillis()}.mp4")
    ).build()
    activeRecording = recorder.prepareRecording(this, options)
        .start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> Log.d("FilterVideo", "录制已开始")
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    if (event.hasError()) {
                        Log.e("FilterVideo", "录制结束有错误：${event.error}", event.cause)
                    } else {
                        Log.d("FilterVideo", "保存成功：${event.outputResults.outputUri}")
                    }
                }
            }
        }
} else {
    activeRecording?.stop()
    // stop 是异步的，等待 Finalize 后再允许下一次录制。
}
```

这是无音频、应用私有目录的示例，不需要录音或公共存储权限。需要相册可见时改用 `MediaStoreOutputOptions`。需要音频时请求 `RECORD_AUDIO`，且保留 `withAudioEnabled()` 返回值，例如链式调用 `prepareRecording(...).withAudioEnabled().start(...)`。

实际按钮应区分 idle / starting / recording / stopping，避免连续点击反复 stop 或在 Finalize 前开始新录制。页面正常退出时，先请求 stop，按产品需要等 Finalize 完成保存，然后解绑两个 UseCase，最后释放处理器；不要在 GL 线程等待录制结束。

修改原 Activity 的 `stopCamera()`，将 Preview 和 VideoCapture 都解绑；只解绑 Preview 会让录像继续使用处理器输入，导致 release future 一直等待。

### 13.3 关于多输出和 StreamSharing

CameraX 可能通过 StreamSharing 先处理一个共享流，再分发预览和编码器。使用公共 CameraEffect 构造函数时，输出对象不保证一一对应最终的 PreviewView 和编码器 Surface。

因此：

- 按每个 `SurfaceOutput.size` 设置 viewport，不按你猜测的录像质量写死尺寸。
- 总是透传输入时间戳，不把输出判断为 PREVIEW 后就省略时间戳。
- `targets` 是位标志；判断是否包含录像用 `targets and VIDEO_CAPTURE != 0`，不是只比较相等。
- API 中标记 `@RestrictTo` 的 output option 构造路径不应作为普通应用的公开扩展接口使用。

需要“预览带水印、录像不带水印”这种不同输出算法时，先核实目标版本能公开提供哪些独立链路。单个共享流已经叠上的水印，下游无法自动去掉。

## 14. 多通道滤镜：从 OES 到 FBO

灰度、亮度和对比度只修改当前采样颜色。模糊、磨皮、边缘检测需要读取周围像素；多个复杂滤镜还需要将某一步的结果交给下一步。

### 14.1 FBO 解决什么问题

```mermaid
flowchart LR
    A[OES 相机纹理] --> B[Pass 0 规范化到二维纹理 A]
    B --> C[Pass 1 横向模糊到二维纹理 B]
    C --> D[Pass 2 纵向模糊到二维纹理 A]
    D --> E[Pass 3 调色并画到输出 EGLSurface]
```

FBO 是 framebuffer object，指定“接下来的 draw 写到哪个附件”。纹理是保存图像的存储对象，FBO 是组织绘制目标的对象，它们不是同一个 ID。

OES 纹理是相机输入，不能像普通 RGBA 纹理那样自由分配存储或直接充当颜色附件。先用 `samplerExternalOES` shader 把它绘制到 `GL_TEXTURE_2D`，后续 pass 改用 `sampler2D`。

### 14.2 创建一个颜色纹理和 FBO

下面是 Renderer 内部的完整辅助函数片段，必须在 GL 线程且上下文 current 时调用。示例只演示一组中间目标，尺寸改变时重新分配，最后删除 FBO 和纹理：

```kotlin
private data class RenderTarget(
    val textureId: Int,
    val framebufferId: Int,
    val width: Int,
    val height: Int
)

private fun createRenderTarget(width: Int, height: Int): RenderTarget {
    require(width > 0 && height > 0)
    val textures = IntArray(1)
    val framebuffers = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    GLES20.glGenFramebuffers(1, framebuffers, 0)
    try {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textures[0], 0
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "FBO 不完整"
        }
        return RenderTarget(textures[0], framebuffers[0], width, height)
    } catch (e: Exception) {
        GLES20.glDeleteFramebuffers(1, framebuffers, 0)
        GLES20.glDeleteTextures(1, textures, 0)
        throw e
    } finally {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
}
```

`glTexImage2D(..., null)` 只分配普通纹理存储，不填充像素。`glFramebufferTexture2D()` 将它附加到 FBO。绑定 FBO 以后 draw 的颜色输出才写进去；创建 FBO 并不会自动执行滤镜。

为中间 pass 设置 `glViewport(0, 0, target.width, target.height)`。最终输出 pass 切回当前窗口 EGLSurface、绑定 framebuffer=0、恢复输出尺寸的 viewport。

**禁止在一个 draw 中采样同时作为当前 FBO 颜色附件的同一纹理。** 这是读写反馈，结果未定义。使用 A/B 两组目标交替读写，也叫 ping-pong；如果最终合成需要保留原图，应另外保留原图纹理，不能让它被后续 pass 覆盖。

### 14.3 第一遍 OES 转换怎样处理矩阵

先给出容易正确实现的路线：**按一个输出的尺寸和方向处理**。

1. 每个输出取得自己的 `outputMatrix`。
2. Pass 0 用完整 `outputMatrix` 将 OES 转成这个输出方向、裁剪区域的二维纹理。
3. Pass 1～N 对规范化后的二维纹理使用单位 UV 矩阵。
4. 最终画到对应输出时继续使用单位 UV，不再应用同一个 CameraX 矩阵。

这样“矩阵只用一次”，容易验证。缺点是不同输出可能重复执行模糊。

想共享一次昂贵处理时，定义一个**明确的中间纹理坐标空间**，通常先保留未进行输出特定裁剪的完整图像，做与输出无关的处理，最后按输出单独映射。假设已应用的映射为 P、目标映射为 M，则还需保留 `P⁻¹ × M` 这部分采样映射；不要凭感觉给最后一遍传单位矩阵。

把单位矩阵传给 `SurfaceOutput.updateTransformMatrix()` 可以取得附加变换；但它只适用于你清楚知道 SurfaceTexture 原始变换在哪一遍已被处理的情况。FBO 自己定义的 UV 方向也要记录，不能因为“已经转成 2D”就忽略翻转和裁剪。

### 14.4 一个可分离的五采样模糊 shader

下列是处理普通二维纹理的**完整片元 shader**，可与使用单位 UV 的顶点 shader 配套：

```glsl
precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uStep;
varying vec2 vTexCoord;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord) * 0.40;
    color += texture2D(uTexture, vTexCoord + uStep) * 0.24;
    color += texture2D(uTexture, vTexCoord - uStep) * 0.24;
    color += texture2D(uTexture, vTexCoord + 2.0 * uStep) * 0.06;
    color += texture2D(uTexture, vTexCoord - 2.0 * uStep) * 0.06;
    gl_FragColor = color;
}
```

横向 pass 上传 `uStep = (1 / sourceWidth, 0)`，纵向 pass 上传 `(0, 1 / sourceHeight)`。这里宽高是**被采样的普通纹理尺寸**，不是 View 尺寸；`uStep` 表示沿某方向移动一个 texel 的归一化距离。

权重之和是 1，均匀区域不会因为模糊整体变亮。先横后纵利用核可分离性，将二维邻域采样拆为两遍。这个核是教学用平滑核，实际高斯模糊可按 sigma 生成并归一化权重。

若直接在带旋转/缩放矩阵的 OES UV 上固定加 `(1/width, 0)`，这个方向未必等于输出的水平方向。先规范化到二维纹理，再按 texel 偏移，数学和扩展都更容易清楚。

### 14.5 磨皮不是简单把画面模糊

通常保留原图，再获取平滑图和肤色/人脸 mask，在目标区域混合：

```glsl
vec3 result = mix(originalRgb, smoothRgb, skinMask * uStrength);
```

`skinMask` 可以来自肤色规则、分割模型或人脸区域；边缘保持平滑可以采用双边滤波等算法。检测结果要按时间戳和坐标变换对应到当前帧，单纯放大模糊强度会把头发、眼睛和背景一起糊掉。

## 15. LUT、水印和分析结果叠加

### 15.1 LUT 是颜色到颜色的映射

LUT 以输入 RGB 为位置，查出输出 RGB。ES 2.0 通常将 3D LUT 的蓝通道切片排成二维 atlas，再用 `sampler2D` 查询。

扩展步骤：

1. 在 GL 线程上传 atlas 为普通 `GL_TEXTURE_2D`，设置线性采样和边缘限制。
2. shader 同时有 OES/二维原图 sampler 与 `sampler2D uLut`。
3. 将原图绑定单元 0、LUT 绑定单元 1，分别上传 sampler 整数 0、1。
4. 根据蓝通道定位两个相邻切片，再按红绿通道定位切片内部坐标。
5. 对两个切片的查询结果插值，最后用强度与原图混合。

设每轴有 N 个样本，输入 RGB 限制在 0～1，`blue = b × (N-1)`。低切片为 floor(blue)，高切片为 min(低切片+1, N-1)，插值权重为 fract(blue)。

假设一行有 C 个 tile、每个 tile 是 N×N，某切片索引 k 的 tile 坐标为 `(k % C, floor(k / C))`。atlas 像素采样位置可以从下面的公式推导：

```text
pixelX = tileX × N + 0.5 + r × (N - 1)
pixelY = tileY × N + 0.5 + g × (N - 1)
uv = (pixelX / atlasWidth, pixelY / atlasHeight)
```

0.5 是像素中心偏移，避免采到 tile 外边界。文件上下方向、是否有 padding、tile 排列顺序都要按素材约定处理；不能随便拿一张“滤镜 PNG”配任何 atlas shader。

LUT 输入色彩空间必须与素材生成空间一致，不能将按 sRGB 制作的 LUT 未经转换地用于任意 HDR 纹理。

### 15.2 水印的绘制位置决定效果

把水印 Bitmap 在初始化或内容变化时上传为普通二维纹理，不要每帧上传同一张图。先画相机底图，再画水印矩形。

| 叠加时机 | 结果 |
|---|---|
| 模糊/调色之前 | 水印也受到后续滤镜影响 |
| 最终滤镜之后 | 水印保持自己的颜色和清晰度 |
| 只加在 PreviewView 上的普通 Android View | 用户看得到，编码输出里通常没有这层 View |

Android Bitmap 上传后常见的是预乘 alpha 数据，采用匹配的 blending：

```kotlin
GLES20.glEnable(GLES20.GL_BLEND)
GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
// 使用水印 program、二维纹理和水印顶点执行 draw。
GLES20.glDisable(GLES20.GL_BLEND)
```

若 shader 输出的是未预乘颜色，使用 `GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`。不要重复乘 alpha，否则半透明边缘变暗。

水印位置可以按输出像素定义，再换算 NDC：`xNdc = 2*x/width - 1`；Android 顶部原点的 y 可换为 `yNdc = 1 - 2*y/height`。水印若要跟随相机内容旋转，和固定在显示右下角的水印是两种不同坐标策略。

### 15.3 ImageAnalysis 检测结果如何画回去

CPU 的 ImageAnalysis 不会自动变成你 SurfaceProcessor shader 的结果。检测可以并行读取另外的分析流，再将结果传到 GL 线程。

分析坐标和滤镜输出坐标可能有不同尺寸、裁剪、旋转和镜像。按 1.4.2 的 API，可以使用以下映射片段：

```kotlin
val analysisToOutput = android.graphics.Matrix()
val sensorToAnalysis = imageProxy.imageInfo.sensorToBufferTransformMatrix
check(sensorToAnalysis.invert(analysisToOutput)) { "分析坐标矩阵不可逆" }
analysisToOutput.postConcat(surfaceOutput.sensorToBufferTransform)
val point = floatArrayOf(faceX, faceY)
analysisToOutput.mapPoints(point)
```

CameraX 1.4.2 的 `getSensorToBufferTransformMatrix()` 在 `ImageInfo` 上，所以先访问 `imageProxy.imageInfo`。这是 `sensor → analysis` 的逆变换，再接 `sensor → output`，得到 `analysis → output`。这里 faceX/faceY 必须先还原到 ImageProxy 的缓冲坐标；若模型接收的是裁剪、缩放或旋转后的图，先撤销模型预处理变换。

把得到的输出像素坐标转换到水印/标记绘制的坐标空间。关注分析和显示时间差，避免人脸框拖尾；分析线程只传轻量结果，尽快 `imageProxy.close()`。

## 16. 拍照：声明目标与保存滤镜图不是同一件事

### 16.1 只用 PREVIEW，正常 ImageCapture 不会自动带滤镜

第 7 节的 `targets = PREVIEW` 只承诺处理预览。即使额外绑定 ImageCapture，普通拍照流也可能完全不经过这条 Effect，因此预览灰度、照片原色是符合配置的结果。

### 16.2 三目标组合的版本注意事项

CameraX 1.4.2 接受 `PREVIEW or VIDEO_CAPTURE or IMAGE_CAPTURE` 这个 SurfaceProcessor 目标组合，但应用是否实际把 ImageCapture 纳入共享流，取决于绑定组合和 CameraX 的内部拓扑。

该版本在一些 Preview + VideoCapture + ImageCapture 的共享链路中由内部 StreamSharing / DefaultSurfaceProcessor 完成 JPEG snapshot；公共 SurfaceProcessor 接口本身只有两个 Surface 回调，没有可由应用实现的 JPEG snapshot 方法。1.4.2 的外部处理器包装器 `SurfaceProcessorWithExecutor.snapshot()` 还返回不支持的失败 future。

这几件事可以同时成立：构造函数接受三目标、某些共享路径可以生成滤镜流快照、你的外部处理器却不能被当成任意 JPEG 处理器使用。

因此不要仅把目标改成三个标志，就宣称所有拍照组合均可正确工作。应在实际版本和绑定组合上检查照片是否经过 Effect、拍照回调是否成功、分辨率和方向是否符合需求。共享流快照也不能默认视为独立高分辨率、完整计算摄影的拍照流。

### 16.3 自己保存当前滤镜帧的通用路线

如果明确要保存“当前滤镜流里的这一帧”，可以走独立快照：

```text
用户请求拍照
  → GL 线程记录待快照任务
  → 下一帧按选定输出矩阵绘制到专用二维纹理/FBO
  → glReadPixels 读回 RGBA
  → 修正行方向、检查通道和 Bitmap 转换
  → 工作线程压缩 JPEG/PNG 并写文件或 MediaStore
  → 主线程通知完成
```

这里的保存分辨率受当前输入流和快照目标决定，将 FBO 放大到 4000×3000 不能凭空增加输入细节。

`glReadPixels()` 是 GPU→CPU 同步点，可能明显卡顿，只在请求快照时使用。纹理 ID 是 GPU 对象标识，不能作为图像传给工作线程；应在 GL 线程完成读回后传字节数据。后台线程做压缩和磁盘写入，别堵住相机 GL 线程。

RGBA、Bitmap ARGB 和本机字节序的区别需要按转换方法验证；同时处理 GL 底部起始行与常见图片顶部起始行的差异。建议先拍含红绿蓝色块和顶部文字的测试图，不要只凭一张风景图判断转换正确。

如产品要求独立高分辨率拍照，可以对 ImageCapture 输出另行执行共享的滤镜算法，或采用目标版本公开支持的图像效果能力。应让预览和照片共用参数/算法定义，而不误认为二者天然是同一图像流。

## 17. 资源释放和生命周期完整说明

### 17.1 谁拥有谁

| 资源 | 创建者 | 释放者 / 时机 |
|---|---|---|
| 输入 Android Surface | Processor | 对应 provideSurface 结果回调后 |
| 输入 SurfaceTexture | Processor | 对应输入归还后 |
| 输入 OES 纹理 | Renderer | 对应输入归还后，正确 GL 上下文中 |
| 输出 Android Surface | CameraX / 下游 | 下游负责；Processor 只调用 SurfaceOutput.close |
| 输出 EGLSurface | Renderer | 停止输出写入、切走 current 后销毁 |
| Program / 中间 FBO 和纹理 | Renderer | 停止使用后，在 current 上下文中删除 |
| Pbuffer / EGLContext / Display | Renderer | 所有输入归还、输出已关闭之后 |
| HandlerThread | Processor | 最后，保证释放回调仍能运行 |

**CameraX 1.4.2 不会自动释放应用传入的外部 SurfaceProcessor。** 内部包装器的 `release()` 是 no-op，所有者必须维护自己的 release 方法。

### 17.2 为什么 unbind 后不能立即释放输入

`unbind()` 触发异步停机。方法返回并不意味着相机服务已停止向输入队列写数据。立刻 `SurfaceTexture.release()` 或删除 OES 纹理，可能让仍在工作的生产者写进失效队列。

本文的 release 分两阶段：

```mermaid
sequenceDiagram
    participant UI as 页面主线程
    participant CX as CameraX
    participant GL as GL 线程
    UI->>CX: unbind(本页面 UseCase)
    UI->>GL: processor.release()
    GL->>GL: closing=true，关闭输出
    Note over GL: 输入尚未归还则保留 EGL 与线程
    CX->>GL: 各输入 provideSurface 结果回调
    GL->>GL: 释放对应输入资源
    GL->>GL: 所有输入归还后释放 EGL
    GL->>GL: 完成 future，quitSafely
```

release future 未完成时，先检查是否仍绑定其他使用此处理器的 UseCase、是否仍有录制、是否忘记解绑 Controller。不要通过固定延时 500 ms 后强制删除资源来“修好”它；相机异步操作在不同设备上没有统一延时保证。

### 17.3 什么时机该解绑，什么时机不该销毁

普通生命周期进入后台时，CameraX 会依据 LifecycleOwner 停止/恢复相机，可能重新申请 Surface。暂时 `onStop()` 不一定意味着处理器永久不用；本例直到 `onDestroy()` 才销毁它。

切换摄像头时保留 Processor，重新绑定对应 CameraSelector 即可；它会管理新旧输入。若永久替换 Effect，先从相机绑定移除旧 Effect/解绑旧链路，再释放旧 Processor；新 Processor 拥有自己的线程和 EGL 资源。

`EVENT_REQUEST_CLOSE` 只表示那个输出寿命结束，并不表示整个处理器要退出。视图重建后新的输出还可能到来，所以关闭一个输出不应销毁全局 EGLContext。

## 18. API 速查和常见故障

本节用于快速定位。需要了解每个函数的全部参数与调用示例，请查第 21 节的完整 API 说明。

### 18.1 SurfaceTexture 与 EGL 速查

| API | 调用线程 / 上下文要求 | 最易误用之处 |
|---|---|---|
| `SurfaceTexture(textureId)` | 与准备好的 GL 纹理和上下文配套 | 纹理未创建或 target 错误 |
| `setDefaultBufferSize(w,h)` | 输入提交前设置 | 用 View 尺寸替代请求尺寸 |
| `setOnFrameAvailableListener(listener, handler)` | 指定 GL Handler | 监听通知线程没有 EGLContext |
| `updateTexImage()` | 关联上下文 current 的 GL 线程 | 以为通知会自动更新纹理 |
| `getTransformMatrix(float[16])` | update 后读该帧 | 忘记 CameraX 附加变换 |
| `getTimestamp()` | update 后读，纳秒 | 误用系统毫秒时间 |
| `eglInitialize()` | EGL 连接初始化阶段 | 失败后继续创建资源 |
| `eglChooseConfig()` | 选支持所需 Surface 类型的配置 | 只检查返回值不检查数量 |
| `eglCreateContext()` | 指定 ES 版本 | shader 版本与 Context 不匹配 |
| `eglCreatePbufferSurface()` | 配置支持 Pbuffer | 只请求 Window 配置 |
| `eglCreateWindowSurface()` | 包装有效 Android Surface | 把 Android Surface 误当 EGLSurface |
| `eglMakeCurrent()` | 确定当前线程上下文和目标 | 在另一线程调用 GL |
| `eglPresentationTimeANDROID()` | 在该输出 swap 前设置 | 毫秒与纳秒混淆 |
| `eglSwapBuffers()` | 绘制结果提交 | 只 draw 没 swap |
| `eglDestroySurface()` | 停止使用后销毁包装 | 仍绑定为 current，或重复销毁 |
| `eglDestroyContext()` / `eglTerminate()` | 完成各资源收尾后 | 早于输入归还 |

### 18.2 GLES 速查

| API | 改变什么 | 使用要点 |
|---|---|---|
| `glGenTextures()` | 创建纹理名称 | 创建后需 bind 和正确参数 |
| `glBindTexture()` | 当前单元指定 target 的绑定 | OES 与 2D 要匹配 sampler |
| `glTexParameteri()` | 纹理采样参数 | OES 使用合法过滤、边缘策略 |
| `glShaderSource()` / `glCompileShader()` | shader 源码与编译 | 检查编译状态与日志 |
| `glLinkProgram()` | 连接顶点/片元阶段 | 检查链接状态 |
| `glUseProgram()` | 当前绘制 program | 上传 uniform 之前调用 |
| `glGetAttribLocation()` / `glGetUniformLocation()` | 查询 program 变量位置 | -1 可能是拼写或优化移除 |
| `glVertexAttribPointer()` | attribute 数据布局 | count 与分量、stride 别混淆 |
| `glUniformMatrix4fv()` | 当前 program 的矩阵值 | 16 元素、列主序、transpose=false |
| `glActiveTexture()` | 当前纹理单元 | 用 `GL_TEXTURE0 + 单元索引` |
| `glUniform1i()` | sampler 或整数 uniform | sampler 传单元索引 |
| `glDrawArrays()` | 提交图元 | triangle strip 四点组成矩形 |
| `glBindFramebuffer()` | 绘制目标 | 最终输出恢复 0 |
| `glFramebufferTexture2D()` | FBO 的颜色附件 | 使用普通二维纹理 |
| `glReadPixels()` | 读当前 framebuffer 像素 | 同步成本、行方向、通道转换 |
| `glDelete*()` | 回收对应 GPU 对象 | 在正确上下文，且不再使用 |
| `glGetError()` | 读取并清除一个 GL 错误 | 标注检查点，必要时循环取完日志 |

### 18.3 排错表

| 现象 | 优先检查 |
|---|---|
| 完全黑屏 | 权限、相机绑定、输入是否提交、帧是否到达、current context、输出是否有效、是否 swap |
| 只有清屏颜色 | shader/link/location、顶点数组启用与 position、texture unit、sampler 类型 |
| 画面上下颠倒 | 是否使用组合矩阵、是否额外 Y 翻转、FBO 是否重复变换 |
| 画面横竖方向不对 | targetRotation、布局更新、每个输出的矩阵是否正确 |
| 画面被拉伸 | viewport 尺寸、输出 size、ViewPort、PreviewView 显示策略 |
| 预览镜像正常但录像反了 | 预览与录像策略是否不同、共享链路上是否手动镜像 |
| 切相机后崩溃 | 旧输入回调是否删了新纹理、释放是否提前、排队帧是否检查资源仍在集合 |
| 退出页面偶发崩溃 | 是否 unbind 后立即 release 输入、是否先停 GL 线程再等归还 |
| eglCreatePbufferSurface 失败 | EGLConfig 是否含 PBUFFER_BIT、配置数量和错误码 |
| eglSwapBuffers 失败 | 输出是否已请求关闭、尺寸/Surface 是否有效、EGL_BAD_SURFACE / EGL_CONTEXT_LOST |
| 录像速度或时间不正常 | 输入纳秒时间戳是否透传、是否重复提交同一时间戳 |
| 预览灰度但拍照原色 | Effect 目标、实际 ImageCapture 路径、是否独立拍照流 |
| FBO 后最终黑屏 | 是否绑定 framebuffer=0、纹理是否同时读写、FBO 是否完整 |
| 水印边缘发黑 | 预乘 alpha 与 blend func 是否匹配 |
| 滤镜越用越卡 | 每帧分配/编译/上传/读回、下游回压、多 pass 是否重复、资源泄漏 |

排错按链路逐段记录：输入请求到来 → 输出到来 → 帧通知 → updateTexImage → draw → swap。不要一开始就同时修改矩阵、shader、Surface 和生命周期，否则很难定位是哪一段失效。

### 18.4 如何从教学实现演进到产品

- **输出单独失败**：区分输出无效与上下文失效；只移除已坏的输出，保留其余输出，等 CameraX 提供新的输出。本文为简单选择整体停止。
- **上下文丢失恢复**：进入重建状态，通知所有者重绑/调用 invalidate，待旧输入按契约归还后重建纹理、program、FBO 和输出包装。不能继续使用旧 context 下的整数 ID。
- **帧积压控制**：只保留最新待处理通知，可将帧事件合并为一个排队 draw；设计时仍要消费输入、保证参数更新和关闭事件不饥饿。
- **性能**：缓存 program、复用 buffers/FBO、合并简单颜色运算；模糊可降低中间分辨率，明确画质损失；测量 update、draw 和 swap 分别耗时。
- **HDR**：核实请求动态范围、10 位 EGLConfig、ES 3.x、外部纹理扩展、色彩空间、传递函数与输出 metadata。本文的 8 位 SDR 实现不能只改一个标志就变成 HDR。
- **热量与耗电**：减少无意义的多输出重复计算、降低不必要分辨率/帧率，后台不处理，长录像在目标设备上测试温升和降频。

## 19. 跟做验证与练习

### 19.1 第一轮：只验证最小链路

1. 构建安装到真机，授权相机，看到灰度实时画面。
2. 将强度设为 0，确认原色能恢复；再设为 0.5，确认连续混合。
3. 使用含“上/下”文字和彩色块的测试场景，确认方向和 RGB 通道。
4. 旋转设备、返回再进入，确认输出重建不崩溃。
5. 连续退出和进入多次，检查释放日志，确认 GL 线程和 Surface 数量没有持续增长。

### 19.2 第二轮：检查新旧交接与录像

1. 改为前置相机，检查镜像与方向；再实现重新绑定来回切换相机。
2. 加 VideoCapture 和正确目标组合，保存短视频，用独立播放器检查滤镜、方向、时长与帧节奏。
3. 录制过程中改强度，确认录像也更新；停止录制等待 Finalize 后再开启下一次。
4. 录制后退出页面，确认已解绑所有拥有的 UseCase，输入回调最终归还。
5. 人为把 shader 变量名写错，确认有编译/链接/location 日志和可控退出，而不是后台线程静默死亡。

### 19.3 第三轮：理解扩展

1. 增加反色模式：只改一个 program，使用 mode uniform。
2. 增加输出角落水印：明确输出坐标和 alpha blending。
3. OES 转二维纹理后实现双 pass 模糊：保持矩阵只应用一次。
4. 给模糊增加强度混合：保留未被覆盖的原图纹理。
5. 增加一次快照：验证行方向、通道和保存分辨率，测量读回造成的停顿。

这些练习分别覆盖颜色算法、额外纹理、几何叠加、多 pass、资源分配和 GPU→CPU 边界。完成后再读其他滤镜 shader，就能判断它应放在哪一遍、需要什么输入、怎样管理参数和资源。

## 20. 查证资料

本文涉及目标组合、外部处理器释放和 snapshot 的说明，核对的是 **CameraX 1.4.2 sources.jar**。在线 API 页随版本更新，遇到差异时先打开与项目依赖一致的源码，不要把仓库中的零散源码文件默认当成所用依赖的全部行为。

建议在 Android Studio 中依次阅读这些类：

| 类 | 重点看什么 |
|---|---|
| `CameraEffect` | `SURFACE_PROCESSOR_TARGETS`、公共构造函数、默认输出/变换选项 |
| `SurfaceProcessor` | 输入归还和输出关闭的正式契约 |
| `SurfaceRequest` | `provideSurface()`、Result、取消、invalidate 的时序 |
| `SurfaceOutput` | 关闭协议和组合矩阵说明 |
| `SurfaceOutputImpl` | `updateTransformMatrix()` 的乘法顺序 |
| `SurfaceProcessorWithExecutor` | 入口异常捕获、外部 release、snapshot 限制 |
| `DefaultSurfaceProcessor` | 输入计数、晚释放、绘制与内部快照实现 |
| `OpenGlRenderer` | EGLConfig、时间戳、纹理/输出管理 |
| `StreamSharing` | 外部 Effect 与内部共享、JPEG 快照分别在哪个节点 |

官方参考：

- [CameraEffect API](https://developer.android.com/reference/androidx/camera/core/CameraEffect)
- [SurfaceProcessor API](https://developer.android.com/reference/androidx/camera/core/SurfaceProcessor)
- [SurfaceRequest API](https://developer.android.com/reference/androidx/camera/core/SurfaceRequest)
- [SurfaceOutput API](https://developer.android.com/reference/androidx/camera/core/SurfaceOutput)
- [SurfaceTexture API](https://developer.android.com/reference/android/graphics/SurfaceTexture)
- [EGL14 API](https://developer.android.com/reference/android/opengl/EGL14)
- [EGLExt API](https://developer.android.com/reference/android/opengl/EGLExt)
- [GLES20 API](https://developer.android.com/reference/android/opengl/GLES20)
- [CameraX 变换说明](https://developer.android.com/media/camera/camerax/transform-output)
- [CameraX 视频录制](https://developer.android.com/media/camera/camerax/video-capture)
- [GL_OES_EGL_image_external 扩展规范](https://registry.khronos.org/OpenGL/extensions/OES/OES_EGL_image_external.txt)
- [EGL_ANDROID_recordable 扩展规范](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_recordable.txt)
- [EGL_ANDROID_presentation_time 扩展规范](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_presentation_time.txt)

本项目已有的补充阅读：

- [ProcessCameraProvider API 使用详解](ProcessCameraProvider_API使用详解.md)
- [CameraController API 使用详解](CameraController_API使用详解.md)
- [着色器代码编写指南](着色器代码编写指南.md)
- [着色器参数传递指南](着色器参数传递指南.md)
- [安卓图形矩阵使用指南](安卓图形矩阵使用指南.md)

## 21. GL 相关 API 的完整参数与使用说明

本节覆盖**本文完整实现和扩展步骤涉及的 EGL14、EGLExt、GLES20 API**，并补充直接相关的 SurfaceTexture、GLUtils、矩阵、VBO 和 GLSL 函数。按真实数据流分类，避免只解释 `glDrawArrays()`，却跳过纹理、程序和状态的准备过程。

每个函数都给出 Android Java 签名，方便核对 IDE 提示。使用 Kotlin 调用同样的静态方法即可；签名里的 `void` 表示没有返回值，`int[]` 对应 Kotlin `IntArray`，`float[]` 对应 `FloatArray`。

### 21.1 读 API 前先统一这些规则

#### 21.1.1 名称、位置、枚举和偏移不是同一种整数

| 类型 | 例子 | 含义 |
|---|---|---|
| GL 对象名称 | textureId、program、shader、framebufferId、bufferId | GL 管理的对象标识，各类对象有各自的名称空间，不能混用 |
| program 内变量位置 | uniformLocation、attributeLocation | 某个已链接 program 中变量的位置，重新链接后需要重新查询 |
| 枚举常量 | `GL_TEXTURE_2D`、`GL_FLOAT` | 指定 API 的类型或模式，不是对象 ID |
| 纹理单元索引 | 0、1 | sampler 指向的单元；与 `GL_TEXTURE0` 枚举区分 |
| Java 数组 offset | `paramsOffset`、`mOffset` | 以数组**元素数**计数，不以字节计数 |
| Buffer position | FloatBuffer.position、ByteBuffer.position | 以该 Buffer 的元素类型计数，例如 FloatBuffer 中一个位置是一个 float |
| VBO offset / stride / size | 顶点字节偏移、步长、缓冲大小 | 以**字节**计数，与数组 offset 不同 |

`0` 也因 API 而异：纹理 0 表示解除命名纹理绑定，framebuffer 0 表示默认绘制目标，program 0 表示不使用有效着色程序，sampler 的值 0 却表示纹理单元 0。不能把它们统称为“空”。

#### 21.1.2 很多函数没有显式传入被修改的对象

`glTexParameteri()` 修改当前纹理单元、指定 target 上绑定的纹理；`glUniform1f()` 修改当前 program；`glReadPixels()` 读取当前 framebuffer。它们依赖**绑定状态**，必须先执行对应 bind/use。

```text
修改纹理参数：glActiveTexture → glBindTexture → glTexParameteri
修改程序参数：glUseProgram → glUniform*
修改离屏附件：glBindFramebuffer → glFramebufferTexture2D
绘制到窗口：eglMakeCurrent → glBindFramebuffer(..., 0) → draw → swap
```

`eglMakeCurrent()` 不会清空旧的 GL 状态；切换窗口后，绑定的 FBO、program、纹理单元和 viewport 仍需按当前 pass 的需要设置。

#### 21.1.3 查询和绘制的前置条件

除 EGL 环境创建/查询外，本节 GLES20 示例默认在 **GL 线程、正确 EGLContext 已 current** 的条件下执行。Java 方法能够返回，不代表驱动一定接受了这次调用；创建/链接状态要显式检查，其他调用在调试检查点使用 `glGetError()`。

Android 数组重载通常显式传 offset；Buffer 重载从当前 position 访问，需保证 remaining 足够。下面主要详解本文用到的数组重载，另在需要时说明 Buffer 与 VBO 版本的差别。

### 21.2 能力、状态和错误查询

#### 21.2.1 glGetString：查询驱动和扩展字符串

```java
String GLES20.glGetString(int name);
```

| 参数 / 返回值 | 含义 |
|---|---|
| `name` | `GL_VENDOR` 厂商、`GL_RENDERER` 渲染器、`GL_VERSION` GLES 版本、`GL_SHADING_LANGUAGE_VERSION` GLSL 版本或 `GL_EXTENSIONS` 扩展列表 |
| 返回值 | 对应字符串；失败或没有有效上下文时不能依赖它一定非空 |

```kotlin
val rendererName = GLES20.glGetString(GLES20.GL_RENDERER)
val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ')
check("GL_OES_EGL_image_external" in extensions)
```

扩展名按完整 token 比较，避免字符串包含导致误判。GL 扩展从这里查询；EGL 扩展从 `eglQueryString()` 查询，二者不是同一份列表。此处是 ES 2.0 的查询用法，ES 3.x 的扩展枚举应按相应版本接口处理。

#### 21.2.2 glGetIntegerv：查询整数状态或能力上限

```java
void GLES20.glGetIntegerv(int pname, int[] params, int offset);
```

| 参数 | 含义 |
|---|---|
| `pname` | 要查询的状态，如 `GL_MAX_TEXTURE_SIZE`、`GL_MAX_TEXTURE_IMAGE_UNITS`、`GL_MAX_VERTEX_ATTRIBS`、`GL_VIEWPORT` |
| `params` | **输出数组**，接收结果；不同 pname 返回的元素数量不同 |
| `offset` | 写入 `params` 的起始元素索引 |

```kotlin
val limit = IntArray(1)
GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
check(width <= limit[0] && height <= limit[0])
val viewport = IntArray(4)
GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
// viewport 依次为 x、y、width、height。
```

该方法无返回值，结果写进数组。尺寸上限满足不等于一定有足够 GPU 内存；分配后仍检查错误。不要为返回 4 个整数的 `GL_VIEWPORT` 只分配 `IntArray(1)`。

#### 21.2.3 glGetShaderPrecisionFormat：查询 shader 浮点精度

```java
void GLES20.glGetShaderPrecisionFormat(
        int shadertype, int precisiontype,
        int[] range, int rangeOffset,
        int[] precision, int precisionOffset);
```

| 参数 | 含义 |
|---|---|
| `shadertype` | `GL_VERTEX_SHADER` 或 `GL_FRAGMENT_SHADER` |
| `precisiontype` | 查询的精度类别，如 `GL_HIGH_FLOAT`、`GL_MEDIUM_FLOAT`、`GL_LOW_FLOAT`，整数类则为对应 `*_INT` |
| `range` | 输出数组，至少容纳从 rangeOffset 开始的 2 个整数，表示可表示范围的指数信息，不是直接的最小/最大浮点值 |
| `rangeOffset` | 两个范围结果在 range 中的起始索引 |
| `precision` | 输出数组，接收精度位数信息 |
| `precisionOffset` | precision 输出位置 |

```kotlin
val range = IntArray(2)
val precision = IntArray(1)
GLES20.glGetShaderPrecisionFormat(
    GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT,
    range, 0, precision, 0
)
val hasFragmentHighp = precision[0] > 0
```

ES 2.0 的片元 highp 浮点不是所有设备必须支持，查询后选择 shader 版本或回退算法。它不是通过这个函数把 mediump shader 升级成 highp，还需改变 shader 精度声明并编译。

#### 21.2.4 glGetError：读取 GL 错误标志

```java
int GLES20.glGetError();
```

没有参数。返回一个错误码，并清除对应错误标志；`GL_NO_ERROR` 表示当前没有待取出的错误。多个错误可能需要反复读取，返回次序不应用来推断全部调用顺序。

| 常见结果 | 典型原因 |
|---|---|
| `GL_INVALID_ENUM` | 枚举不被该参数接受，例如 target 或 pname 错误 |
| `GL_INVALID_VALUE` | 数值不合法，例如负宽度、错误数量 |
| `GL_INVALID_OPERATION` | 状态或对象不适合操作，例如 uniform 类型不匹配 |
| `GL_INVALID_FRAMEBUFFER_OPERATION` | 当前 FBO 不完整却继续绘制/读取 |
| `GL_OUT_OF_MEMORY` | GPU 分配失败；不要继续假设对象已正常初始化 |

```kotlin
var error = GLES20.glGetError()
while (error != GLES20.GL_NO_ERROR) {
    Log.e("GL", "检查点错误：0x${Integer.toHexString(error)}")
    error = GLES20.glGetError()
}
```

应把检查点放在纹理分配、shader/program 设置和 draw 后，而不是只在帧末读一次再猜哪句出错。EGL 错误使用 `eglGetError()`，不是这里。shader 语法错误还需要 info log，不能只看 GL 错误。

### 21.3 EGL：环境、目标、提交和回收

#### 21.3.1 eglGetDisplay：取得 Display 连接句柄

```java
EGLDisplay EGL14.eglGetDisplay(int display_id);
```

| 参数 / 返回值 | 含义 |
|---|---|
| `display_id` | 原生显示标识；本文传 `EGL_DEFAULT_DISPLAY` 请求默认图形显示连接 |
| 返回值 | EGLDisplay，失败为 `EGL_NO_DISPLAY` |

```kotlin
val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
check(display != EGL14.EGL_NO_DISPLAY)
```

它不是 Android 屏幕 View，也不是 GL 绘制目标。得到句柄后还要 `eglInitialize()`。

#### 21.3.2 eglInitialize：初始化 Display

```java
boolean EGL14.eglInitialize(
        EGLDisplay dpy,
        int[] major, int majorOffset,
        int[] minor, int minorOffset);
```

| 参数 | 含义 |
|---|---|
| `dpy` | 要初始化的 EGLDisplay |
| `major` | 输出 EGL 主版本号的数组 |
| `majorOffset` | 主版本号写入 major 的元素位置 |
| `minor` | 输出 EGL 次版本号的数组 |
| `minorOffset` | 次版本号写入 minor 的元素位置 |
| 返回值 | true 初始化成功，false 失败，应立即查询 EGL 错误 |

```kotlin
val version = IntArray(2)
check(EGL14.eglInitialize(display, version, 0, version, 1))
// version[0]、version[1] 是 EGL 版本，不是 OpenGL ES 版本。
```

两个输出可以共用一个数组，但 offset 要区分，数组也要足够长。该调用不会创建 EGLContext。

#### 21.3.3 eglQueryString：查询 EGL 的版本和扩展

```java
String EGL14.eglQueryString(EGLDisplay dpy, int name);
```

| 参数 / 返回值 | 含义 |
|---|---|
| `dpy` | 已初始化的 Display；本文在初始化后查询 |
| `name` | `EGL_VENDOR`、`EGL_VERSION`、`EGL_EXTENSIONS` 或 `EGL_CLIENT_APIS` |
| 返回值 | 对应字符串，失败可能为 null，需要查 EGL 错误 |

```kotlin
val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
    .orEmpty().split(' ')
check("EGL_ANDROID_recordable" in extensions)
check("EGL_ANDROID_presentation_time" in extensions)
```

它查询 EGL 能力，不能用来判断 `GL_OES_EGL_image_external` 是否存在。

#### 21.3.4 eglChooseConfig：按条件筛选配置

```java
boolean EGL14.eglChooseConfig(
        EGLDisplay dpy,
        int[] attrib_list, int attrib_listOffset,
        EGLConfig[] configs, int configsOffset,
        int config_size,
        int[] num_config, int num_configOffset);
```

| 参数 | 含义 |
|---|---|
| `dpy` | 已初始化的 Display |
| `attrib_list` | 输入属性数组，按 key/value 排列，最后 `EGL_NONE` 终止 |
| `attrib_listOffset` | 从属性数组哪个元素开始读取；本文 0 |
| `configs` | 输出配置数组，用来接收符合条件的 EGLConfig |
| `configsOffset` | 写入 configs 的起始位置 |
| `config_size` | 允许写入的配置项数量，不是属性数组长度或字节数 |
| `num_config` | 输出数组，报告此次查询实际返回的配置数量；查询总数模式可先不接收配置 |
| `num_configOffset` | 数量结果写入 num_config 的位置 |
| 返回值 | 查询过程是否成功；true 与“至少找到一项”是两件事 |

```kotlin
val configs = arrayOfNulls<EGLConfig>(1)
val count = IntArray(1)
check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0))
check(count[0] > 0)
val selected = requireNotNull(configs[0])
```

本文属性要求各颜色通道至少满足指定位数，支持 ES2、Window 和 Pbuffer；`EGL_SURFACE_TYPE` 是位掩码，这里使用 `or`，而属性 key/value 之间用逗号排列。recordable 是选择条件，不是录像开始按钮。

#### 21.3.5 eglCreateContext：创建 GL 上下文

```java
EGLContext EGL14.eglCreateContext(
        EGLDisplay dpy, EGLConfig config,
        EGLContext share_context,
        int[] attrib_list, int offset);
```

| 参数 | 含义 |
|---|---|
| `dpy` | 已初始化的 Display |
| `config` | 选中的、支持目标 GL 版本的配置 |
| `share_context` | 要共享资源的已有上下文；`EGL_NO_CONTEXT` 表示不共享 |
| `attrib_list` | 上下文属性，如 `EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE` |
| `offset` | 属性数组起始元素位置 |
| 返回值 | EGLContext，失败为 `EGL_NO_CONTEXT` |

```kotlin
val context = EGL14.eglCreateContext(
    display, config, EGL14.EGL_NO_CONTEXT,
    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
)
check(context != EGL14.EGL_NO_CONTEXT)
```

创建后不会自动成为 current，需要 `eglMakeCurrent()`。共享 context 可共享规范允许的纹理、程序等资源，但并不共享所有绑定状态，也不自动解决跨线程 GPU 同步；FBO 等对象的共享规则需按 GLES 版本核实。

#### 21.3.6 eglCreatePbufferSurface：创建离屏 EGL 目标

```java
EGLSurface EGL14.eglCreatePbufferSurface(
        EGLDisplay dpy, EGLConfig config,
        int[] attrib_list, int offset);
```

| 参数 | 含义 |
|---|---|
| `dpy` | Display |
| `config` | 必须支持 `EGL_PBUFFER_BIT` 的配置 |
| `attrib_list` | Pbuffer 属性，本文指定 `EGL_WIDTH=1`、`EGL_HEIGHT=1` 并用 `EGL_NONE` 终止 |
| `offset` | 属性数组起始位置 |
| 返回值 | 离屏 EGLSurface，失败为 `EGL_NO_SURFACE` |

```kotlin
val pbuffer = EGL14.eglCreatePbufferSurface(
    display, config,
    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
)
```

宽高单位是像素。它提供 current 目标，不与 PreviewView 自动连接，也不是一个可直接交给 CameraX 的 Android Surface。

#### 21.3.7 eglCreateWindowSurface：包装下游 Android Surface

```java
EGLSurface EGL14.eglCreateWindowSurface(
        EGLDisplay dpy, EGLConfig config,
        Object win, int[] attrib_list, int offset);
```

| 参数 | 含义 |
|---|---|
| `dpy` | Display |
| `config` | 支持 `EGL_WINDOW_BIT`，且与下游兼容的配置 |
| `win` | Android Java 包装器接受的原生窗口对象；本文是 `SurfaceOutput.getSurface()` 返回的 `android.view.Surface` |
| `attrib_list` | 窗口属性；本文只传 `EGL_NONE`，采用默认设置 |
| `offset` | 属性数组起始位置 |
| 返回值 | Window EGLSurface；失败为 `EGL_NO_SURFACE`，不支持的 Java 窗口类型还可能触发参数异常 |

```kotlin
val window = EGL14.eglCreateWindowSurface(
    display, config, androidSurface, intArrayOf(EGL14.EGL_NONE), 0
)
```

参数声明是 Object，不代表可以随便传 View、Bitmap 或 textureId。窗口缓冲尺寸来自底层 Surface，不能靠在这个默认属性列表里写宽高来改变相机输出。

#### 21.3.8 eglMakeCurrent：绑定线程、上下文和绘制目标

```java
boolean EGL14.eglMakeCurrent(
        EGLDisplay dpy, EGLSurface draw,
        EGLSurface read, EGLContext ctx);
```

| 参数 | 含义 |
|---|---|
| `dpy` | 上下文和 Surface 所属的 Display |
| `draw` | 默认 framebuffer 绘制使用的 EGLSurface |
| `read` | 默认 framebuffer 读取使用的 EGLSurface；本文与 draw 相同 |
| `ctx` | 要成为调用线程 current 的上下文 |
| 返回值 | 绑定成功为 true；失败查 EGL 错误 |

```kotlin
check(EGL14.eglMakeCurrent(display, output, output, context))
// 当前线程之后的 GLES 调用使用 context；framebuffer=0 时画到 output。
```

同一个 context 不能同时 current 在两个线程上。转移前要在原线程解除绑定，不能只把 Kotlin 变量传给新线程。

解除绑定的方法如下：

```kotlin
EGL14.eglMakeCurrent(
    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
)
```

本例不依赖无 Surface 上下文扩展，因此正常 GL 操作用 Pbuffer 作为落脚点。

#### 21.3.9 eglQuerySurface：查询实际 EGL 目标尺寸

```java
boolean EGL14.eglQuerySurface(
        EGLDisplay dpy, EGLSurface surface,
        int attribute, int[] value, int offset);
```

| 参数 | 含义 |
|---|---|
| `dpy` | Display |
| `surface` | 被查询的 EGLSurface |
| `attribute` | 要查询的属性，常见 `EGL_WIDTH`、`EGL_HEIGHT` |
| `value` | 输出数组，接收一个属性值 |
| `offset` | value 写入位置 |
| 返回值 | 查询成功/失败 |

```kotlin
val width = IntArray(1)
check(EGL14.eglQuerySurface(display, output, EGL14.EGL_WIDTH, width, 0))
```

排查 CameraX `SurfaceOutput.size` 与底层缓冲是否一致时有用。它不会设置 viewport，也不会修改 Surface 尺寸。

#### 21.3.10 eglGetCurrentContext / eglGetCurrentDisplay / eglGetCurrentSurface

```java
EGLContext EGL14.eglGetCurrentContext();
EGLDisplay EGL14.eglGetCurrentDisplay();
EGLSurface EGL14.eglGetCurrentSurface(int readdraw);
```

前两个无参数，查询**调用线程**的 current 对象，没有时分别返回 `EGL_NO_CONTEXT`、`EGL_NO_DISPLAY`。

`eglGetCurrentSurface()` 的 `readdraw` 传 `EGL_DRAW` 或 `EGL_READ`，选择查询当前绘制/读取目标，没有时返回 `EGL_NO_SURFACE`。

```kotlin
check(EGL14.eglGetCurrentContext() == context)
check(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == output)
```

这组查询用于调试线程和目标错误，不表示其他线程也拥有相同 current 状态。

#### 21.3.11 eglPresentationTimeANDROID：设置下一帧提交时间

```java
boolean EGLExt.eglPresentationTimeANDROID(
        EGLDisplay dpy, EGLSurface surface, long time);
```

| 参数 | 含义 |
|---|---|
| `dpy` | Display |
| `surface` | 即将提交帧的输出 EGLSurface |
| `time` | 下一次 swap 提交帧的展示时间戳，单位纳秒；本文透传刚 update 后的 SurfaceTexture.timestamp |
| 返回值 | 设置成功/失败；需支持 `EGL_ANDROID_presentation_time` |

```kotlin
check(EGLExt.eglPresentationTimeANDROID(display, output, timestampNs))
check(EGL14.eglSwapBuffers(display, output))
```

它不负责播放、编码或延时等待，也不会自动推算下一帧时间。不同来源的时间戳不能不经设计就拼成一条连续录制时间线。

#### 21.3.12 eglSwapBuffers：提交窗口帧

```java
boolean EGL14.eglSwapBuffers(EGLDisplay dpy, EGLSurface surface);
```

| 参数 / 返回值 | 含义 |
|---|---|
| `dpy` | Display |
| `surface` | 已完成绘制、需要提交的窗口 EGLSurface |
| 返回值 | 提交成功为 true，失败读取 EGL 错误并处理失效输出/上下文 |

通常在画完一帧并设置时间戳后调用。它提交图像到下游队列，可能因消费速度和同步等待阻塞，不保证显示器或编码器已经完整消费该帧。Pbuffer/FBO 中间 pass 不靠它把纹理送给下一 pass，使用同上下文 GL 命令顺序即可。

#### 21.3.13 eglDestroySurface：销毁 EGL 包装

```java
boolean EGL14.eglDestroySurface(EGLDisplay dpy, EGLSurface surface);
```

`dpy` 是所属 Display，`surface` 是要销毁的 EGLSurface，返回 true/false。本文先切回 Pbuffer，再销毁输出，避免依赖 current Surface 的延迟销毁行为。

```kotlin
check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
check(EGL14.eglDestroySurface(display, output))
surfaceOutput.close()
```

它销毁的是 EGL 对象，不代替 CameraX 的关闭协议，也不等价于 `androidSurface.release()`。GL 中间纹理和 FBO 还需要各自的 glDelete 调用。

#### 21.3.14 eglDestroyContext：销毁上下文

```java
boolean EGL14.eglDestroyContext(EGLDisplay dpy, EGLContext ctx);
```

`dpy` 为所属 Display，`ctx` 为上下文，返回操作成功与否。本文完成所有输入归还、GPU 对象清理及解除 current 后调用。

上下文销毁后不能拿旧纹理名称到一个全新、不共享的上下文里使用。若存在共享 context，资源实际寿命还受共享组中其他上下文和引用影响。

#### 21.3.15 eglTerminate：结束 Display 初始化

```java
boolean EGL14.eglTerminate(EGLDisplay dpy);
```

唯一参数是此前初始化的 Display，返回 true/false。它属于 EGL 生命周期收尾，不是“清空这一帧”。本文在 Surface 和 Context 处理完之后调用。若进程里其他模块也使用 EGL，必须明确各自的初始化、共享和收尾安排，不要让一个局部模块随意终止公共管理器。

#### 21.3.16 eglReleaseThread：释放当前线程的 EGL 状态

```java
boolean EGL14.eglReleaseThread();
```

没有参数，返回 true/false。清理调用线程的 EGL 线程状态，本文在 renderer 完成解绑、对象销毁、terminate 后调用。它不是 `HandlerThread.quitSafely()`，不会替你终止 Android 线程。

#### 21.3.17 eglGetError：读取线程的 EGL 错误

```java
int EGL14.eglGetError();
```

没有参数，返回调用线程最近的 EGL 错误并将其恢复为 `EGL_SUCCESS`。EGL 与 GLES 的成功常量不同：EGL 使用 `EGL_SUCCESS`，GL 使用 `GL_NO_ERROR`。

| 错误 | 优先检查 |
|---|---|
| `EGL_BAD_DISPLAY` / `EGL_NOT_INITIALIZED` | Display 是否取得并初始化 |
| `EGL_BAD_CONFIG` / `EGL_BAD_MATCH` | 配置能力、Context 版本、窗口和上下文是否匹配 |
| `EGL_BAD_CONTEXT` / `EGL_BAD_SURFACE` | 句柄是否已销毁或失效 |
| `EGL_BAD_NATIVE_WINDOW` | 下游 Android Surface 是否有效 |
| `EGL_BAD_ACCESS` | context/surface 是否仍被其他线程占用 |
| `EGL_CONTEXT_LOST` | GPU 上下文丢失，需要走资源重建流程 |

只在 EGL 操作失败后立即读取并记录，避免中间另一个错误覆盖诊断信息。不能在主线程调用它来诊断 GL 线程之前的失败。

### 21.4 纹理：创建、绑定、采样和上传

#### 21.4.1 glGenTextures：生成纹理名称

```java
void GLES20.glGenTextures(int n, int[] textures, int offset);
```

| 参数 | 含义 |
|---|---|
| `n` | 要生成的纹理名称数量，非负；本文 1 |
| `textures` | 输出数组，接收 n 个名称，容量至少为 offset+n |
| `offset` | 写入 textures 的起始元素索引 |

```kotlin
val names = IntArray(1)
GLES20.glGenTextures(1, names, 0)
val textureId = names[0]
check(textureId != 0)
```

无返回值，名称写入数组。生成名称不等于已有像素内容：普通二维纹理之后要分配/上传存储；OES 图像来自 SurfaceTexture。名称不能拿来直接当成 sampler 的值。

#### 21.4.2 glActiveTexture：选择当前纹理单元

```java
void GLES20.glActiveTexture(int texture);
```

`texture` 是**纹理单元枚举**，如 `GL_TEXTURE0` 或 `GL_TEXTURE0 + 1`。选择后，后续纹理绑定/参数操作作用于该单元。无返回值。

```kotlin
GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + 1)
GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
GLES20.glUniform1i(lutLocation, 1)
```

传入整数 1 不是选择单元 1 的正确方式，传 textureId 也不是。fragment shader 可用单元数量查询 `GL_MAX_TEXTURE_IMAGE_UNITS`，同时关注组合单元上限。

#### 21.4.3 glBindTexture：将纹理对象绑定到当前单元

```java
void GLES20.glBindTexture(int target, int texture);
```

| 参数 | 含义 |
|---|---|
| `target` | 纹理类别，本文相机为 `GL_TEXTURE_EXTERNAL_OES`，普通图/FBO 为 `GL_TEXTURE_2D` |
| `texture` | 对应纹理名称；0 表示解除这个 target 的命名纹理绑定 |

```kotlin
GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
```

首次绑定确定纹理对象的 target 类型，不能把同一个已用于 OES 的名称后来改作 2D。OES 与 2D 可以有不同绑定槽，但 shader 的 sampler 类型必须匹配实际纹理类型。绑定纹理既不上传图像，也不自动选择 program 中的 sampler。

#### 21.4.4 glTexParameteri：设置当前绑定纹理的整数参数

```java
void GLES20.glTexParameteri(int target, int pname, int param);
```

| 参数 | 含义 |
|---|---|
| `target` | 要修改哪个 target 上当前绑定的纹理，先 bind 正确对象 |
| `pname` | 修改的采样属性，如 MIN_FILTER、MAG_FILTER、WRAP_S、WRAP_T |
| `param` | 这个属性的值，合法取值随 pname 和纹理类型变化 |

| pname | 常用 param | 本文为何这样选 |
|---|---|---|
| `GL_TEXTURE_MIN_FILTER` | 普通 2D 有 NEAREST、LINEAR 和各 mipmap 模式；OES 只用 NEAREST/LINEAR | `GL_LINEAR` 不要求 mipmap，适合相机缩小采样 |
| `GL_TEXTURE_MAG_FILTER` | `GL_NEAREST` / `GL_LINEAR` | LINEAR 做放大插值 |
| `GL_TEXTURE_WRAP_S` | 普通 2D 可 REPEAT、CLAMP_TO_EDGE、MIRRORED_REPEAT；OES 要 CLAMP_TO_EDGE | s 为横向纹理坐标，边缘不卷绕 |
| `GL_TEXTURE_WRAP_T` | 同上 | t 为纵向纹理坐标，边缘不卷绕 |

```kotlin
GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
GLES20.glTexParameteri(
    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
)
```

ES 2.0 中非 2 的幂尺寸普通纹理若无扩展，也应使用无 mipmap 的 MIN_FILTER 和 CLAMP_TO_EDGE。API 还提供 `glTexParameterf(target, pname, float param)`；三个参数语义相同，对整数枚举属性使用 `glTexParameteri()` 更清楚。

#### 21.4.5 glTexImage2D：为普通二维纹理分配或上传像素

```java
void GLES20.glTexImage2D(
        int target, int level, int internalformat,
        int width, int height, int border,
        int format, int type, Buffer pixels);
```

| 参数 | 含义 |
|---|---|
| `target` | 本文 `GL_TEXTURE_2D`，操作已绑定的普通纹理；不是 OES |
| `level` | mipmap 层级，0 是原始分辨率；本文只使用 0 |
| `internalformat` | GPU 存储的基础格式；ES 2.0 本文用 `GL_RGBA`，需与 format 配套 |
| `width` | 该层级图像宽度，像素数 |
| `height` | 该层级图像高度，像素数 |
| `border` | OpenGL ES 必须为 0，不能借它增加边框 |
| `format` | 输入数据的通道排列基础格式，如 `GL_RGBA`、`GL_RGB` |
| `type` | 每个像素数据的类型/打包方式，本文 `GL_UNSIGNED_BYTE` 为每通道 8 位 |
| `pixels` | 原生 Buffer，从当前 position 读取；null 表示只分配存储，内容尚未定义 |

```kotlin
GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
GLES20.glTexImage2D(
    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
    width, height, 0,
    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
)
```

这是 FBO 附件的常用分配方式。null 不意味着黑色，读取前先 clear 或完整绘制。若上传 RGBA 字节，最基本容量是 width×height×4，还要考虑行对齐。对于 ES 2.0，不要套用桌面 GL 或 ES 3.x 的任意 sized internalformat。

#### 21.4.6 glTexSubImage2D：更新已有二维纹理的局部区域

```java
void GLES20.glTexSubImage2D(
        int target, int level, int xoffset, int yoffset,
        int width, int height, int format, int type, Buffer pixels);
```

| 参数 | 含义 |
|---|---|
| `target` | 已绑定纹理类型，本文 `GL_TEXTURE_2D` |
| `level` | 已存在的 mipmap 层级，本文 0 |
| `xoffset` / `yoffset` | 写入纹理区域的左下起始 texel 坐标，单位像素，不是 UV |
| `width` / `height` | 本次更新的矩形像素宽高 |
| `format` / `type` | 本次输入像素格式和类型，需与已有存储兼容 |
| `pixels` | 待上传像素 Buffer，容量和行布局要与更新区域匹配 |

```kotlin
GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId)
GLES20.glTexSubImage2D(
    GLES20.GL_TEXTURE_2D, 0, 0, 0, patchWidth, patchHeight,
    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, patchBytes
)
```

它不分配新尺寸；必须先有 `glTexImage2D()` 建立的存储，区域也不能越界。更换同尺寸水印可更新，改变尺寸则重新分配或创建新纹理。

#### 21.4.7 glPixelStorei：设置像素上传/读回的行对齐

```java
void GLES20.glPixelStorei(int pname, int param);
```

| 参数 | 含义 |
|---|---|
| `pname` | 上传使用 `GL_UNPACK_ALIGNMENT`；读回使用 `GL_PACK_ALIGNMENT` |
| `param` | 每行对齐字节数，允许 1、2、4、8，默认 4 |

```kotlin
GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
// 上传紧密排列、没有行 padding 的 RGB 或 RGBA 数据。
GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
// 将 glReadPixels 结果按紧密行布局写入 Buffer。
```

例如 RGB 宽度 5，一行 15 字节，默认对齐 4 时需要按 16 字节步长提供行。若输入其实每行只有 15 字节却保留默认值，会产生错行。RGBA 每行 width×4，通常天然满足默认对齐。

这两个设置都是 context 状态，不只影响一次调用；与其他模块共同渲染时恢复其期望值。它们不能任意设置行宽，也不能解决 Bitmap 本身不同 rowBytes 的全部问题。

#### 21.4.8 GLUtils.texImage2D：上传 Android Bitmap

```java
void GLUtils.texImage2D(int target, int level, Bitmap bitmap, int border);
```

| 参数 | 含义 |
|---|---|
| `target` | 已绑定普通纹理，本文 `GL_TEXTURE_2D` |
| `level` | 层级，本文 0 |
| `bitmap` | 未 recycle、平台支持其格式的 Bitmap；辅助函数据此选择像素格式和类型 |
| `border` | GLES 中传 0 |

```kotlin
GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId)
android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
```

其他重载显式接收 `internalformat`，或同时接收 `internalformat`、`type`；含义与 GLES 上传一致。这个辅助方法不是自动加载图片，也不自动开启 alpha 混合；需要自己设置纹理过滤和 wrap，检查 Bitmap 配置/行布局，验证 UV 上下方向。它也不会替你改变 `GL_UNPACK_ALIGNMENT`，需让该状态与输入 Bitmap 的行布局匹配。

Bitmap 的预乘 alpha 不会因为上传而自动变成未预乘。图片解码可在工作线程完成，GL 上传必须在 GL 线程进行。

#### 21.4.9 GLUtils.texSubImage2D：用 Bitmap 更新已存在纹理

```java
void GLUtils.texSubImage2D(
        int target, int level, int xoffset, int yoffset, Bitmap bitmap);
```

target、level、xoffset、yoffset 与 `glTexSubImage2D()` 相同；bitmap 提供整张更新数据，其宽高决定更新区域。没有返回值，已绑定纹理必须预先有兼容存储。

```kotlin
android.opengl.GLUtils.texSubImage2D(
    GLES20.GL_TEXTURE_2D, 0, 0, 0, newWatermarkBitmap
)
```

不能用它把原来较小纹理自动扩容。仅内容变化时更新即可，固定水印不需要每帧上传。

#### 21.4.10 glDeleteTextures：回收纹理对象

```java
void GLES20.glDeleteTextures(int n, int[] textures, int offset);
```

`n` 为删除数量，`textures` 是**输入数组**保存已有纹理名称，`offset` 指向起始元素。无返回值；0 和不对应现存纹理的名称会被忽略，但应用仍应幂等管理，避免对象名称复用后误删新对象。

```kotlin
GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
```

对应相机输入先等 provideSurface 结果回调，再释放 Surface/SurfaceTexture 和纹理。删除纹理不等于释放 Android 输入 Surface，也不允许你跳过相机所有权契约。

### 21.5 Shader 和 Program：每一步的参数

#### 21.5.1 glCreateShader：创建 shader 对象

```java
int GLES20.glCreateShader(int type);
```

`type` 只能选择 shader 阶段，本文 `GL_VERTEX_SHADER` 或 `GL_FRAGMENT_SHADER`。返回 shader 名称，创建失败为 0。

```kotlin
val shader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
check(shader != 0)
```

只创建对象，里面还没有源码，也未编译。这里不能传 program 或 shader 字符串。

#### 21.5.2 glShaderSource：指定源码

```java
void GLES20.glShaderSource(int shader, String string);
```

`shader` 为刚创建的 shader 对象；`string` 是完整 GLSL 源码。没有返回值，这一步替换源码，不会编译，也不会改变已经链接 program 的可执行代码。

```kotlin
GLES20.glShaderSource(shader, fragmentSource)
```

这是 Android String 包装接口，不需要额外传 C API 的字符串数量、长度或指针数组。OES 扩展声明要放在适当位置，不能在 shader 代码中夹入 Kotlin/Java 声明。

#### 21.5.3 glCompileShader：编译当前源码

```java
void GLES20.glCompileShader(int shader);
```

唯一参数是 shader 名称，没有直接返回编译结果。调用后用 `glGetShaderiv()` 查询 `GL_COMPILE_STATUS`，失败时读取 `glGetShaderInfoLog()`。

```kotlin
GLES20.glCompileShader(shader)
```

不要每帧编译。新模式尽量通过 uniform 选择，或者提前编译并缓存 program。

#### 21.5.4 glGetShaderiv：查询 shader 编译状态

```java
void GLES20.glGetShaderiv(int shader, int pname, int[] params, int offset);
```

| 参数 | 含义 |
|---|---|
| `shader` | 被查询的 shader 名称 |
| `pname` | 常用 `GL_COMPILE_STATUS`，也可查询 `GL_SHADER_TYPE`、`GL_DELETE_STATUS`、日志长度等 |
| `params` | 输出数组接收结果 |
| `offset` | 写入结果的元素位置 |

```kotlin
val status = IntArray(1)
GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
```

`status[0]` 才是结果，调用本身无返回值。shader 对象有效不代表编译成功。

#### 21.5.5 glGetShaderInfoLog：取得编译诊断

```java
String GLES20.glGetShaderInfoLog(int shader);
```

参数是 shader 名称，返回驱动生成的日志字符串。可能有错误、警告或为空。它不返回源码，也不以“日志非空”作为编译成功与否的标准；以 COMPILE_STATUS 判断。

保留日志里的行号并对应实际提交的 shader 文本，特别是运行时拼接了扩展或宏定义时，IDE 文件行号可能与 shader 行号不同。

#### 21.5.6 glCreateProgram：创建 program 对象

```java
int GLES20.glCreateProgram();
```

无参数，返回 program 名称，失败为 0。一个 program 将顶点阶段与片元阶段连接为一次 draw 使用的程序。

```kotlin
val program = GLES20.glCreateProgram()
check(program != 0)
```

program 名称不能当 shader 名称传入 compile API。

#### 21.5.7 glAttachShader：把 shader 附加到 program

```java
void GLES20.glAttachShader(int program, int shader);
```

`program` 是目标程序对象，`shader` 是某阶段 shader 对象，无返回值。本文分别附加一个编译成功的 vertex shader 和 fragment shader。

```kotlin
GLES20.glAttachShader(program, vertexShader)
GLES20.glAttachShader(program, fragmentShader)
```

attach 不是 link，不保证阶段接口已经匹配。不要交换两个参数。

#### 21.5.8 glLinkProgram：链接两个阶段

```java
void GLES20.glLinkProgram(int program);
```

参数是要链接的 program，无直接返回值。检查 `GL_LINK_STATUS`，失败读取 program 日志。varying 的名称/类型必须匹配，采样器及 uniform 的使用也要满足设备限制。

重新链接会影响变量 location 和 uniform 初始化状态。若热更新 shader，建立新 program、查询全部位置并上传参数后再切换，避免继续使用旧 location。

#### 21.5.9 glGetProgramiv：查询链接状态

```java
void GLES20.glGetProgramiv(int program, int pname, int[] params, int offset);
```

program 为查询对象；pname 常用 `GL_LINK_STATUS`，还可查询 `GL_ACTIVE_UNIFORMS`、`GL_ACTIVE_ATTRIBUTES`、`GL_INFO_LOG_LENGTH`；params 为输出数组，offset 为写入元素位置。

```kotlin
val status = IntArray(1)
GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
```

program 创建成功、两个 shader 编译成功，都不能替代链接状态检查。

#### 21.5.10 glGetProgramInfoLog：取得链接诊断

```java
String GLES20.glGetProgramInfoLog(int program);
```

参数为 program 名称，返回链接/相关程序诊断日志。查看阶段接口不匹配、资源上限等原因。日志可有警告，最终成功与否仍看 LINK_STATUS。

#### 21.5.11 glUseProgram：选择接下来绘制的 program

```java
void GLES20.glUseProgram(int program);
```

`program` 为成功链接的程序，0 解除有效 program 选择，无返回值。GLES2 没有旧式固定管线可供 program=0 正常绘制滤镜。

```kotlin
GLES20.glUseProgram(program)
GLES20.glUniform1f(intensityLocation, intensity)
GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
```

uniform 上传作用于当前 program，因此 use 必须在上传前。选择 program 不会自动绑定它需要的输入纹理、attribute 数组或 FBO。

#### 21.5.12 glGetAttribLocation：查询顶点属性位置

```java
int GLES20.glGetAttribLocation(int program, String name);
```

program 为已链接程序，name 为 shader 中的 active attribute 名称，大小写必须一致。返回非负位置；未找到/非活跃等情况为 -1。

```kotlin
val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
check(positionLocation >= 0)
```

之后把位置传给 enable 和 pointer 函数。优化掉的 attribute 不会得到可用位置，不要拿 -1 继续启用数组。

#### 21.5.13 glGetUniformLocation：查询程序参数位置

```java
int GLES20.glGetUniformLocation(int program, String name);
```

program 为已链接程序，name 为 active uniform 名称，例如 `uTexture`、`uTexMatrix`。返回 location，未找到/非活跃时为 -1。数组元素可用相应名字，例如 `uWeights[0]`。

```kotlin
val samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
check(samplerLocation >= 0)
```

GL 对 location=-1 的 uniform 上传会忽略，但教学实现选择检查，尽早发现拼写/优化问题。attribute 的 -1 不能照这个规则继续使用。

#### 21.5.14 glDeleteShader：标记 shader 删除

```java
void GLES20.glDeleteShader(int shader);
```

参数为 shader 名称，0 被忽略，无返回值。shader 若仍被 program 附加，实际删除可能延迟到引用解除。正常链接后调用不会让已经链接好的 program 丢失可执行代码，因此本文 finally 中回收 shader。

#### 21.5.15 glDeleteProgram：回收 program

```java
void GLES20.glDeleteProgram(int program);
```

参数为 program 名称，0 被忽略，无返回值。仍作为 current 使用的程序可能延迟实际删除；应用停止使用或切换程序后收尾更清楚。它不删除输入纹理、输出 Surface 或所有 context 状态。

### 21.6 顶点、uniform 与 draw

#### 21.6.1 glEnableVertexAttribArray / glDisableVertexAttribArray

```java
void GLES20.glEnableVertexAttribArray(int index);
void GLES20.glDisableVertexAttribArray(int index);
```

两者唯一参数 index 都是 attribute 位置，范围应落在支持的 attribute 索引内。enable 让 vertex shader 从已描述的数组逐顶点读取；disable 停止使用该数组，改用该索引的常量属性值。都没有返回值。

```kotlin
GLES20.glEnableVertexAttribArray(positionLocation)
GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
GLES20.glDisableVertexAttribArray(positionLocation)
```

disable 不是释放 FloatBuffer/VBO，也不是把所有 GL 状态恢复默认。

#### 21.6.2 glVertexAttribPointer：描述顶点数组或 VBO 布局

```java
void GLES20.glVertexAttribPointer(
        int indx, int size, int type, boolean normalized,
        int stride, Buffer ptr);
void GLES20.glVertexAttribPointer(
        int indx, int size, int type, boolean normalized,
        int stride, int offset);
```

| 参数 | 含义 |
|---|---|
| `indx` | 当前 draw 使用的 program attribute 位置 |
| `size` | 每顶点该属性分量数，1～4；本文位置/UV 都是 2 |
| `type` | 数据类型，本文 `GL_FLOAT`；与实际 buffer 存储匹配 |
| `normalized` | 整数输入是否归一化到浮点范围；float 数据时不需要，本文 false |
| `stride` | 相邻顶点相同属性的起始地址差，单位字节；0 表示该属性紧密排列 |
| `ptr` | 使用客户端数组的重载：指向顶点数据的 direct Buffer，读取起点是其 position |
| `offset` | 使用 VBO 的重载：已绑定 GL_ARRAY_BUFFER 内的起始**字节偏移**，不是 Buffer position |

客户端路径先绑定 ARRAY_BUFFER=0，再传 direct FloatBuffer。VBO 路径先 bind 有效 VBO，再传整数 offset。不要在绑定 VBO 时把 Java Buffer 当成其偏移使用。

```kotlin
// 独立位置数组：每顶点两个 float，stride=0。
GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
vertices.position(0)
GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)

// VBO 交错布局为 x、y、s、t，每顶点 16 字节，UV 从第 8 字节开始。
GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, interleavedVbo)
GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, 0)
GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, 8)
```

pointer 描述数据来源，不立即 draw。VBO 绑定会在 pointer 设置时与属性关联；客户端 Buffer 必须在 draw 时仍有效且内容足够。

#### 21.6.3 glUniform1f：上传一个浮点参数

```java
void GLES20.glUniform1f(int location, float x);
```

location 是当前 program 的 float uniform 位置，x 是值。无返回值。示例 intensity 限制在 0～1：

```kotlin
GLES20.glUseProgram(program)
GLES20.glUniform1f(intensityLocation, intensity.coerceIn(0f, 1f))
```

函数自身不会把值限制到 0～1；参数合法范围由算法定义。uniform 保存在 program 中，后续 draw 沿用，直到再次上传或程序重新链接。

#### 21.6.4 glUniform1i：上传整数或 sampler 单元

```java
void GLES20.glUniform1i(int location, int x);
```

location 为当前 program 的整数/bool/sampler uniform 位置；x 为整数值。sampler 必须用整数接口，x 是纹理单元索引，本文 0；mode 则是算法模式编号。

```kotlin
GLES20.glUniform1i(samplerLocation, 0)
GLES20.glUniform1i(modeLocation, 2)
```

float uniform 与整数上传接口不匹配会出错，不是所有 uniform 都能用 1i 传。sampler 不接受纹理名称或 `GL_TEXTURE0` 枚举值。

#### 21.6.5 glUniform2f / glUniform3f / glUniform4f：上传向量

```java
void GLES20.glUniform2f(int location, float x, float y);
void GLES20.glUniform3f(int location, float x, float y, float z);
void GLES20.glUniform4f(int location, float x, float y, float z, float w);
```

location 为当前 program 中对应 vec2/vec3/vec4 的位置，后续实参依次为向量分量，均无返回值。不能给 vec2 uniform 用 glUniform1f 上传。

```kotlin
// 模糊横向采样间距，必须使用被采样纹理的实际尺寸。
GLES20.glUniform2f(stepLocation, 1f / sourceWidth, 0f)
// 三通道增益。
GLES20.glUniform3f(gainLocation, redGain, greenGain, blueGain)
// RGBA 常量颜色。
GLES20.glUniform4f(colorLocation, 1f, 0f, 0f, 0.5f)
```

GLSL 向量名称 x/y/z/w 和 r/g/b/a 可描述相同分量，但实际用途由 shader 决定，不是 API 自动判断颜色。

#### 21.6.6 glUniform1fv / glUniform2fv / glUniform3fv / glUniform4fv

```java
void GLES20.glUniform1fv(int location, int count, float[] v, int offset);
void GLES20.glUniform2fv(int location, int count, float[] v, int offset);
void GLES20.glUniform3fv(int location, int count, float[] v, int offset);
void GLES20.glUniform4fv(int location, int count, float[] v, int offset);
```

| 参数 | 含义 |
|---|---|
| `location` | 对应 uniform 或 uniform 数组首个待写元素的位置 |
| `count` | 上传的标量/向量**个数**，不是 float 总数 |
| `v` | 输入浮点数组，依次排列；Nfv 需要 count×N 个 float |
| `offset` | 从 v 哪个 float 元素开始读 |

```kotlin
// uniform float uWeights[5]：count=5，需要 5 个 float。
GLES20.glUniform1fv(weightsLocation, 5, weights, 0)
// uniform vec2 uOffsets[3]：count=3，需要 6 个 float。
GLES20.glUniform2fv(offsetsLocation, 3, floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f), 0)
```

上传多个值要求对应的是有足够元素的数组 uniform。上述浮点接口另有 FloatBuffer 重载，从其 position 读取；不要把 count 写成数组总长度，尤其 vec4 数组。

#### 21.6.7 glUniformMatrix4fv：上传 4×4 矩阵

```java
void GLES20.glUniformMatrix4fv(
        int location, int count, boolean transpose,
        float[] value, int offset);
```

| 参数 | 含义 |
|---|---|
| `location` | 当前 program 中 mat4 uniform 的位置 |
| `count` | 上传的 mat4 数量，本文 1，不是 16 |
| `transpose` | OpenGL ES 要求 false，直接按列主序使用 |
| `value` | 输入数组，每个 mat4 连续 16 个 float，至少满足 offset+count×16 |
| `offset` | 输入数组起始 float 元素索引 |

```kotlin
GLES20.glUniformMatrix4fv(matrixLocation, 1, false, outputMatrix, 0)
```

SurfaceTexture 和 android.opengl.Matrix 的 4×4 数组可按此格式上传；不要把 Android graphics.Matrix 的 9 个值传进来，也不要把 transpose 设 true 来修正自己排错的数组。

#### 21.6.8 glDrawArrays：从顺序顶点绘制图元

```java
void GLES20.glDrawArrays(int mode, int first, int count);
```

| 参数 | 含义 |
|---|---|
| `mode` | 图元组织方式，本文 `GL_TRIANGLE_STRIP`，其他有 TRIANGLES、LINES、POINTS 等 |
| `first` | 从属性数组中的哪个顶点开始，本文 0，单位顶点索引 |
| `count` | 读取的顶点数，本文 4，不是 float 数或三角形数 |

```kotlin
GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
```

调用前必须准备 current program、目标 framebuffer、viewport、attribute、uniform 与纹理。它把 GPU 工作排入命令流，不自动 swap，不返回处理后的 Bitmap。

#### 21.6.9 glDrawElements：按索引绘制

```java
void GLES20.glDrawElements(int mode, int count, int type, Buffer indices);
void GLES20.glDrawElements(int mode, int count, int type, int offset);
```

| 参数 | 含义 |
|---|---|
| `mode` | 图元模式，例如 `GL_TRIANGLES` |
| `count` | 本次索引个数，矩形两个三角形通常 6 |
| `type` | 索引元素类型；基础 ES 2.0 用 `GL_UNSIGNED_BYTE` 或 `GL_UNSIGNED_SHORT`，32 位索引需额外能力 |
| `indices` | 客户端索引数据 direct Buffer，ELEMENT_ARRAY_BUFFER 应为 0 |
| `offset` | VBO 索引路径中，已绑定 ELEMENT_ARRAY_BUFFER 内的起始字节偏移 |

```kotlin
val indices = ByteBuffer.allocateDirect(6 * 2)
    .order(ByteOrder.nativeOrder()).asShortBuffer()
    .apply { put(shortArrayOf(0, 1, 2, 2, 1, 3)); position(0) }
GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, indices)
```

索引指向之前定义的顶点属性，不能超出属性数据范围。客户端/索引 VBO 两种路径不能混淆。本文简单矩形不必改为索引绘制，复杂网格再考虑。

### 21.7 绘制区域、清屏与混合状态

#### 21.7.1 glViewport：将 NDC 映射到输出像素区域

```java
void GLES20.glViewport(int x, int y, int width, int height);
```

| 参数 | 含义 |
|---|---|
| `x` / `y` | viewport 左下角在当前 framebuffer 中的像素位置 |
| `width` / `height` | viewport 像素宽高，不能为负 |

```kotlin
GLES20.glViewport(0, 0, outputWidth, outputHeight)
```

NDC (-1,-1) 映射到左下，(1,1) 映射到右上。函数不改变纹理或 EGLSurface 的实际尺寸，也不等价于裁剪区域；clear 的范围不由 viewport 决定。多 pass 切换尺寸时必须更新。

#### 21.7.2 glClearColor：设置之后清颜色缓冲使用的颜色

```java
void GLES20.glClearColor(float red, float green, float blue, float alpha);
```

四个参数是 RGBA 清屏颜色分量，通常按 0～1 表示，在本文 RGBA8 场景中使用相应范围；alpha=1 为不透明，0 为透明。

```kotlin
GLES20.glClearColor(0f, 0f, 0f, 1f)
```

仅设置状态，没有立即写入 framebuffer。清为透明不代表 PreviewView 所在窗口和视频编码器能保留透明；下游是否支持 alpha 是另一个问题。

#### 21.7.3 glClear：清除指定缓冲

```java
void GLES20.glClear(int mask);
```

mask 是位掩码，可用 `GL_COLOR_BUFFER_BIT`、`GL_DEPTH_BUFFER_BIT`、`GL_STENCIL_BUFFER_BIT` 组合。本文只需要颜色缓冲：

```kotlin
GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
// 若有深度附件并需要同时清除：
GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
```

它清当前 framebuffer，不清所有纹理或 GL 状态。clear 会受 scissor 和相应写入 mask 影响，不受 viewport 限制；开启 scissor 后只清 scissor 范围。滤镜完整覆盖颜色缓冲时可评估省略 clear。

#### 21.7.4 glEnable / glDisable：开关流水线能力

```java
void GLES20.glEnable(int cap);
void GLES20.glDisable(int cap);
```

cap 是可启停的能力枚举，例如 `GL_BLEND`、`GL_DEPTH_TEST`、`GL_SCISSOR_TEST`、`GL_CULL_FACE`。没有返回值，设置保持到之后再次修改。

```kotlin
GLES20.glDisable(GLES20.GL_BLEND)
GLES20.glDisable(GLES20.GL_DEPTH_TEST)
GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
```

cap 不能传 textureId、uniformLocation 或 `GL_TEXTURE_2D`；GLES2 纹理采样由 shader 决定，不靠旧式 `glEnable(GL_TEXTURE_2D)`。顶点属性开关使用专门的 `glEnableVertexAttribArray()`。

#### 21.7.5 glScissor：限制可写入的矩形

```java
void GLES20.glScissor(int x, int y, int width, int height);
```

x/y 为当前 framebuffer 的左下起始像素坐标，width/height 为矩形宽高，不能为负。只设置 scissor 矩形，需 `glEnable(GL_SCISSOR_TEST)` 才生效。

```kotlin
GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
GLES20.glScissor(0, 0, outputWidth / 2, outputHeight)
// 后续 draw/clear 只影响左半边，用于滤镜前后对比。
GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
```

viewport 描述坐标映射，scissor 描述写入限制。要从 Android 顶部原点矩形转换为 GL 底部原点，使用 `glY = framebufferHeight - top - rectHeight`。

#### 21.7.6 glBlendFunc：指定源色和目标色的混合系数

```java
void GLES20.glBlendFunc(int sfactor, int dfactor);
```

| 参数 | 含义 |
|---|---|
| `sfactor` | 源颜色系数；源是当前 fragment shader 输出 |
| `dfactor` | 目标颜色系数；目标是 framebuffer 中已有颜色 |

在默认相加混合方程下，概念上 `result = source × sfactor + destination × dfactor`。常见系数为 `GL_ONE`、`GL_ZERO`、`GL_SRC_ALPHA`、`GL_ONE_MINUS_SRC_ALPHA`。

```kotlin
GLES20.glEnable(GLES20.GL_BLEND)
// 预乘 alpha 水印：shader 的 RGB 已含 alpha。
GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
```

未预乘颜色常用 SRC_ALPHA / ONE_MINUS_SRC_ALPHA 混合 RGB；若同时需要准确保留目标 alpha，用下面的 Separate 接口分别配置。设置 blend func 不会自动 enable blending。

#### 21.7.7 glBlendFuncSeparate：分别设置 RGB 和 alpha 系数

```java
void GLES20.glBlendFuncSeparate(
        int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
```

srcRGB/dstRGB 分别控制 RGB 源/目标系数，srcAlpha/dstAlpha 分别控制 alpha 源/目标系数。没有返回值。

```kotlin
// 未预乘 RGB，alpha 按 source-over 累积。
GLES20.glBlendFuncSeparate(
    GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
    GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA
)
```

此例 alpha 为 `srcA + dstA × (1-srcA)`，避免普通 BlendFunc 的 SRC_ALPHA 同时作用于 alpha，使源 alpha 再平方。最终不透明视频可简化，但离屏透明合成需准确区分。

#### 21.7.8 glBlendEquation：决定系数乘积怎样组合

```java
void GLES20.glBlendEquation(int mode);
```

mode 为 `GL_FUNC_ADD`、`GL_FUNC_SUBTRACT` 或 `GL_FUNC_REVERSE_SUBTRACT` 等当前版本/扩展支持的模式；本文使用默认 `GL_FUNC_ADD`。

```kotlin
GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
```

它指定相加/相减关系，BlendFunc 指定两边系数，Enable 指定是否执行混合，三者分工不同。与外部 renderer 混用时显式恢复方程，避免上一步减法混合影响水印。

### 21.8 FBO 与 GPU 像素读回

#### 21.8.1 glGenFramebuffers：生成 FBO 名称

```java
void GLES20.glGenFramebuffers(int n, int[] framebuffers, int offset);
```

n 为生成数量，framebuffers 为输出名称数组，offset 为起始元素位置。无返回值，与 GenTextures 的数组规则一致。

```kotlin
val names = IntArray(1)
GLES20.glGenFramebuffers(1, names, 0)
```

只生成名称，不创建颜色像素存储；需要另外的二维纹理或 renderbuffer 附件。

#### 21.8.2 glBindFramebuffer：选择后续绘制与读取目标

```java
void GLES20.glBindFramebuffer(int target, int framebuffer);
```

target 在 GLES20 中使用 `GL_FRAMEBUFFER`，framebuffer 为 FBO 名称；0 表示当前 EGLSurface 对应的默认 framebuffer。

```kotlin
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFbo)
GLES20.glViewport(0, 0, intermediateWidth, intermediateHeight)
// 绘制离屏 pass。
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
GLES20.glViewport(0, 0, outputWidth, outputHeight)
```

不能把 textureId 当成 FBO 名称。切换 EGLSurface 后，如果仍绑定命名 FBO，draw 依然写 FBO，所以最后输出必须显式恢复 0。

#### 21.8.3 glFramebufferTexture2D：把二维纹理作为颜色附件

```java
void GLES20.glFramebufferTexture2D(
        int target, int attachment, int textarget,
        int texture, int level);
```

| 参数 | 含义 |
|---|---|
| `target` | `GL_FRAMEBUFFER`，修改当前绑定的命名 FBO |
| `attachment` | 附件位置，本文 `GL_COLOR_ATTACHMENT0` |
| `textarget` | 附加纹理的类型，本文 `GL_TEXTURE_2D` |
| `texture` | 已创建且已分配存储的二维纹理名称；0 表示解除此附件 |
| `level` | 使用的纹理层级，本文 ES 2.0 路径为 0 |

```kotlin
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
GLES20.glFramebufferTexture2D(
    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
    GLES20.GL_TEXTURE_2D, colorTextureId, 0
)
```

它连接已有存储，不分配像素、不复制图像，也不执行 draw。不能将相机 OES 纹理直接作为本文颜色附件；不能同一 draw 又采样 colorTextureId 又向其写入。

#### 21.8.4 glCheckFramebufferStatus：检查 FBO 是否可以绘制

```java
int GLES20.glCheckFramebufferStatus(int target);
```

target 为 `GL_FRAMEBUFFER`，查询当前绑定 FBO。返回 `GL_FRAMEBUFFER_COMPLETE` 才表示完整；其他返回值为诊断状态，不能只判断非零。

| 常见状态 | 含义 |
|---|---|
| `GL_FRAMEBUFFER_COMPLETE` | 附件组合可用于绘制 |
| `GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT` | 某个附件不满足要求，如无有效存储 |
| `GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT` | 没有附件 |
| `GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS` | GLES2 中附件尺寸不兼容 |
| `GL_FRAMEBUFFER_UNSUPPORTED` | 驱动不支持此附件格式组合 |

```kotlin
check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
```

在创建或更换附件后检查，不必稳定配置下每帧重复查询。RGBA8 普通纹理是常见起点，浮点/HDR 附件必须查询 renderable 能力。

#### 21.8.5 glDeleteFramebuffers：删除 FBO 对象

```java
void GLES20.glDeleteFramebuffers(int n, int[] framebuffers, int offset);
```

n 为数量，framebuffers 为输入名称数组，offset 为起始元素位置，无返回值。

```kotlin
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
GLES20.glDeleteTextures(1, intArrayOf(colorTextureId), 0)
```

删除 FBO 不等于删除它连接的纹理，二者分别管理。分辨率改变时停止使用旧目标，再更新附件存储或重建目标。

#### 21.8.6 glReadPixels：从当前 framebuffer 读像素到 CPU

```java
void GLES20.glReadPixels(
        int x, int y, int width, int height,
        int format, int type, Buffer pixels);
```

| 参数 | 含义 |
|---|---|
| `x` / `y` | 读取矩形左下角，在当前 framebuffer 中的像素坐标 |
| `width` / `height` | 读取区域宽高，单位像素 |
| `format` | 输出像素通道格式；本文 `GL_RGBA` |
| `type` | 输出数据类型；本文 `GL_UNSIGNED_BYTE` 为每通道一个字节 |
| `pixels` | **输出 Buffer**，从当前 position 写入，需有足够原生存储，考虑 PACK_ALIGNMENT |

```kotlin
val byteCountLong = width.toLong() * height * 4
require(width > 0 && height > 0 && byteCountLong <= Int.MAX_VALUE)
val byteCount = byteCountLong.toInt()
val pixels = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, snapshotFbo)
GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
pixels.position(0)
GLES20.glReadPixels(
    0, 0, width, height,
    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels
)
pixels.position(0)
```

需要先画好 snapshotFbo，函数不自动采样某个 textureId。第一行对应 GL 底部，保存常见图片时需要按转换方案调整行序；RGBA 字节也不能盲目当成 Java ARGB int。

RGBA/UNSIGNED_BYTE 是本例归一化颜色缓冲的基础读回组合；其他格式/type 组合需按版本和能力查询。读取范围应处于有效缓冲中。这个调用可能同步等待 GPU，建议只在明确请求快照时执行。

### 21.9 VBO：可选的顶点数据扩展

正文使用 direct FloatBuffer 已能正确绘制。顶点数据多、重复使用或需要索引网格时，可把数据上传到 GPU buffer。本小节不是运行灰度示例的必需步骤。

#### 21.9.1 glGenBuffers：生成缓冲对象名称

```java
void GLES20.glGenBuffers(int n, int[] buffers, int offset);
```

n 为生成数量，buffers 为输出数组，offset 为写入起点，无返回值。生成名称后还需 bind 和 BufferData 才有数据存储。

```kotlin
val names = IntArray(1)
GLES20.glGenBuffers(1, names, 0)
val vboId = names[0]
```

#### 21.9.2 glBindBuffer：选择后续缓冲操作的对象

```java
void GLES20.glBindBuffer(int target, int buffer);
```

target 为 `GL_ARRAY_BUFFER`（顶点数据）或 `GL_ELEMENT_ARRAY_BUFFER`（索引数据），buffer 为对应对象名称，0 表示解除该绑定。无返回值。

```kotlin
GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
```

它决定 BufferData 修改哪个对象，也决定 VertexAttribPointer 的整数 offset 路径如何解释。不要把 Framebuffer 的 target 混进这里。

#### 21.9.3 glBufferData：分配并上传缓冲存储

```java
void GLES20.glBufferData(int target, int size, Buffer data, int usage);
```

| 参数 | 含义 |
|---|---|
| `target` | 已 bind 的缓冲类别，ARRAY_BUFFER 或 ELEMENT_ARRAY_BUFFER |
| `size` | 分配/上传大小，单位字节，不能写成 float 个数 |
| `data` | 输入原生 Buffer，从 position 读取；null 可只分配存储 |
| `usage` | 使用提示，常见 STATIC_DRAW（很少更新）、DYNAMIC_DRAW（重复更新）、STREAM_DRAW（短期使用） |

```kotlin
vertexBuffer.position(0)
GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
GLES20.glBufferData(
    GLES20.GL_ARRAY_BUFFER, vertexCount * componentsPerVertex * 4,
    vertexBuffer, GLES20.GL_STATIC_DRAW
)
```

usage 是驱动优化提示，不会限制之后一定不能修改。该调用创建或替换数据存储，上传后 GPU buffer 独立保存数据；pointer 的 stride/offset 必须对应你真实上传布局。

#### 21.9.4 glDeleteBuffers：回收 VBO / 索引缓冲

```java
void GLES20.glDeleteBuffers(int n, int[] buffers, int offset);
```

n 为删除数量，buffers 为输入对象名称数组，offset 为读取起点，无返回值。停止 draw 使用后删除，并更新应用保存的名称/属性布局，避免后续继续沿用旧资源。

```kotlin
GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
```

它不释放 Java direct Buffer，不删除纹理，也不负责退出 GL 线程。

### 21.10 命令提交和等待

#### 21.10.1 glFlush：让已排队命令开始推进

```java
void GLES20.glFlush();
```

无参数、无返回值。促使之前的 GL 命令向执行阶段推进，但不等待所有工作完成，也不提交 Android Surface 的显示帧。

正常窗口渲染在 swap 时已经有相应提交行为，通常无需再每帧加 glFlush。跨上下文协作要使用明确同步方案，单纯 flush 不能证明另一个上下文已安全读到完整结果。

#### 21.10.2 glFinish：等待此前 GL 工作完成

```java
void GLES20.glFinish();
```

无参数、无返回值。调用线程等待此前 GL 命令的效果完成，可能显著停顿。它不代表编码器、播放器等下游已经消费完成，也不是输入 Surface 可以释放的 CameraX 信号。

同一上下文连续 pass 顺序由 GL 命令顺序保证；每 pass 调用 glFinish 通常没有必要。glReadPixels 的客户端读回已经需要使返回数据可供 CPU 使用，也不应无条件再加一次 finish。性能诊断或跨 API 同步需求应单独设计。

### 21.11 SurfaceTexture：相机帧如何进入 OES 纹理

下面是 Android 图形 API，不是 GLES20 函数；但它负责连接 CameraX 输入与纹理采样，必须与 GL 部分一起理解。

#### 21.11.1 SurfaceTexture 构造函数

```java
SurfaceTexture(int texName);
SurfaceTexture(int texName, boolean singleBufferMode);
```

| 参数 | 含义 |
|---|---|
| `texName` | 与 SurfaceTexture 关联的 GL 外部纹理名称；本文先生成并配置 OES 纹理再传入 |
| `singleBufferMode` | 是否采用单缓冲模式；本文使用单参数构造函数的普通模式，不引入单缓冲所有权交接 |

```kotlin
val surfaceTexture = SurfaceTexture(textureId)
val inputSurface = Surface(surfaceTexture)
```

创建后它是队列消费者，`Surface(surfaceTexture)` 是生产者使用的 Surface 包装。singleBufferMode=true 需要配套 releaseTexImage 等严格的交接协议，不应为了“更省内存”直接开启到相机示例。

平台另有不立即附加纹理的构造路径，用于之后显式 attach 到 GLContext；本文已附加构造路径不需要再 attach，同一个 SurfaceTexture 的上下文转移也不能只切换线程变量。

#### 21.11.2 setDefaultBufferSize：指定输入缓冲期望尺寸

```java
void SurfaceTexture.setDefaultBufferSize(int width, int height);
```

width/height 为缓冲区像素宽高，使用 `SurfaceRequest.resolution`，没有返回值。它不是 viewport，也不是 shader UV 范围。

```kotlin
surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
```

在 provideSurface 前设置。生产者可能有自己的尺寸协商，本例按 CameraX 明确请求设置；随意改成屏幕尺寸会使 Surface 不符合请求或产生错误比例。

#### 21.11.3 setOnFrameAvailableListener：注册可消费帧通知

```java
void SurfaceTexture.setOnFrameAvailableListener(OnFrameAvailableListener listener);
void SurfaceTexture.setOnFrameAvailableListener(
        OnFrameAvailableListener listener, Handler handler);
```

| 参数 | 含义 |
|---|---|
| `listener` | 新帧可用的回调；null 表示移除监听。回调参数是触发通知的 SurfaceTexture |
| `handler` | 调度回调的 Handler，本文 glHandler 保证线程确定；无 Handler 版本使用平台规定的调度方式 |

```kotlin
surfaceTexture.setOnFrameAvailableListener({ source ->
    renderer.makePbufferCurrent()
    source.updateTexImage()
    // 继续读取矩阵、时间戳和绘制。
}, glHandler)
```

回调参数不是 Bitmap，也不是 pixel Buffer；通知不会自动调用 updateTexImage。注册 GL Handler 仍不等于上下文已 current，所以代码继续显式绑定 EGL 目标。

移除监听不保证此前已经排入队列的任务都消失，因此还要检查输入资源是否有效、是否 closing。完整版中的资源集合判断就是为此服务。

#### 21.11.4 updateTexImage：取得最新可用图像

```java
void SurfaceTexture.updateTexImage();
```

无参数、无返回值。将消费者选取的最新图像更新到关联的 OES 纹理，并更新这张图像的矩阵与时间戳信息，可能跳过较旧图像。

必须在关联 GL 上下文 current 的线程调用；对象已释放、上下文不对等情况可能抛出异常。它不执行你自己的滤镜 shader，也不把图像交给输出 Surface。

此方法可能改变外部纹理绑定状态。后续 draw 仍显式设置 active texture 和 bind 输入纹理，不依赖调用前残留的绑定。

#### 21.11.5 getTransformMatrix：取得当前图像采样映射

```java
void SurfaceTexture.getTransformMatrix(float[] mtx);
```

mtx 为**输出数组**，长度必须**恰好等于 16**，平台会检查长度，写入列主序 4×4 变换。它描述从基础齐次 UV `(s,t,0,1)` 到实际图像采样坐标的映射，包含底层图像所需的变换。

```kotlin
surfaceTexture.updateTexImage()
surfaceTexture.getTransformMatrix(inputMatrix)
surfaceOutput.updateTransformMatrix(outputMatrix, inputMatrix)
```

在 update 后取得对应图像的矩阵。数组不是 program 的 uniform 本身，还需上传组合矩阵。它也不等于 CameraX ViewPort 的全部裁剪/旋转信息，因此随后调用 SurfaceOutput.updateTransformMatrix。

#### 21.11.6 getTimestamp：取得当前图像时间戳

```java
long SurfaceTexture.getTimestamp();
```

无参数，返回当前 update 取得图像的生产时间戳，单位纳秒；Kotlin 用 `surfaceTexture.timestamp`。没有取得有效图像前，不要把初始值当正常帧时间。

```kotlin
surfaceTexture.updateTexImage()
val timestampNs = surfaceTexture.timestamp
```

时间基准由图像来源决定，不能当 UTC 时间显示，也不能未经转换与另一来源时间戳相减。单路相机录像应保留该流时间顺序，重复通知取得同一 timestamp 时避免重复提交。

#### 21.11.7 SurfaceTexture.release 与 Surface.release

```java
void SurfaceTexture.release();
void Surface.release();
```

二者都无参数、无返回值，但释放不同对象。SurfaceTexture.release 释放其消费者资源并使队列进入不可继续使用的状态；Surface.release 释放当前 Android Surface 包装所持原生引用。

```kotlin
// 仅在对应 provideSurface 结果回调后回收自己创建的输入。
surfaceTexture.setOnFrameAvailableListener(null)
inputSurface.release()
surfaceTexture.release()
GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
```

SurfaceTexture.release 不代替显式纹理名称管理，Surface.release 也不代替 EGLSurface 销毁。输出 getSurface 返回的 Android Surface 不归处理器所有，不能套用输入释放代码。

### 21.12 android.opengl.Matrix：自定义 4×4 变换

这些方法是 CPU 数组运算，无须 current EGLContext；但若矩阵由 GL 线程使用，跨线程更新仍需要避免同时读写。都使用列主序 4×4 数组，offset 为 float 元素位置。

#### 21.12.1 setIdentityM：写入单位矩阵

```java
void Matrix.setIdentityM(float[] sm, int smOffset);
```

sm 为输出数组，smOffset 为起始元素索引，从此位置起至少 16 个 float。没有返回值。单位矩阵对角为 1，其余为 0，乘任何坐标不改变它。

```kotlin
android.opengl.Matrix.setIdentityM(customMatrix, 0)
```

新建 FloatArray(16) 得到全 0，不是单位矩阵；全零矩阵会让 UV 全部变成同一个位置。

#### 21.12.2 translateM：右乘平移变换

```java
void Matrix.translateM(float[] m, int mOffset, float x, float y, float z);
void Matrix.translateM(
        float[] tm, int tmOffset, float[] m, int mOffset,
        float x, float y, float z);
```

| 参数 | 含义 |
|---|---|
| `m` / `mOffset` | 原矩阵及起始位置；单数组版本原地修改 |
| `tm` / `tmOffset` | 双数组版本的输出矩阵及起始位置 |
| `x` / `y` / `z` | 三轴平移量，单位取决于坐标空间：UV 中 0.5 是半个归一化范围，不是半个像素 |

结果为 `M × T`，按列向量规则，对坐标先应用 T 再应用 M。

```kotlin
android.opengl.Matrix.translateM(customMatrix, 0, 0.5f, 0.5f, 0f)
```

连续调用的代码顺序不等于向量最终经历变换的顺序，需要按矩阵乘法检查。围绕 UV 中心缩放的前后平移可参照第 11.6 节。

#### 21.12.3 scaleM：右乘缩放变换

```java
void Matrix.scaleM(float[] m, int mOffset, float x, float y, float z);
void Matrix.scaleM(
        float[] sm, int smOffset, float[] m, int mOffset,
        float x, float y, float z);
```

m/mOffset 为输入矩阵；单数组版原地修改；sm/smOffset 为双数组版的输出。x/y/z 为各轴缩放倍数：1 保持，0.5 减半，负值包含轴翻转。结果为 `M × S`。

```kotlin
android.opengl.Matrix.scaleM(customMatrix, 0, 0.5f, 0.5f, 1f)
```

缩小“采样 UV”表示从更小区域取样，内容看起来变大；缩小“顶点几何”则使输出矩形变小。相同函数用于不同空间会得到不同视觉结果。

#### 21.12.4 multiplyMM：计算两个矩阵的乘积

```java
void Matrix.multiplyMM(
        float[] result, int resultOffset,
        float[] lhs, int lhsOffset,
        float[] rhs, int rhsOffset);
```

| 参数 | 含义 |
|---|---|
| `result` / `resultOffset` | 输出乘积矩阵的数组与起始位置 |
| `lhs` / `lhsOffset` | 左乘矩阵及起始位置 |
| `rhs` / `rhsOffset` | 右乘矩阵及起始位置 |

结果为 lhs×rhs，不是逐元素相乘。对列向量来说先应用 rhs，再应用 lhs。每个数组从相应位置起需要 16 个 float。

```kotlin
android.opengl.Matrix.multiplyMM(finalMatrix, 0, outputMatrix, 0, customMatrix, 0)
```

为便于推导并避免不同实现下覆盖输入的细节，示例使用独立 result 数组。不要把顺序交换后期望同样效果；旋转、平移、裁剪通常不满足交换律。

### 21.13 GLSL 中的构造、采样和颜色函数

下面是在 GPU shader 里调用的 GLSL ES 1.00 函数或表达式，不是 Android Java API。它们不存在 Java 数组 offset，也不应在 Kotlin 中调用 `texture2D()`。

#### 21.13.1 vec2 / vec3 / vec4：构造向量

```glsl
vec2 uv = vec2(0.5, 0.5);
vec3 grayRgb = vec3(gray);
vec4 homogeneousUv = vec4(uv, 0.0, 1.0);
vec4 outputColor = vec4(grayRgb, alpha);
```

| 构造 | 各参数含义 |
|---|---|
| `vec2(x,y)` | 两个分量依次为 x、y |
| `vec3(value)` | 一个标量扩展为三个相同分量，适合灰度 RGB |
| `vec4(uv,z,w)` | uv 提供前两分量，z 和 w 依次补充；纹理齐次坐标用 z=0、w=1 |
| `vec4(rgb,alpha)` | rgb 三个通道加 alpha，得到四通道颜色 |

`.xy`、`.rgb`、`.a` 是分量选择语法，称 swizzle/分量访问，不是另外一次矩阵运算。mat4×vec4 表示矩阵作用于列向量。

#### 21.13.2 texture2D：按归一化坐标采样纹理

```glsl
vec4 texture2D(sampler2D sampler, vec2 coord);
// OES 扩展额外提供对 samplerExternalOES 的采样支持。
vec4 texture2D(samplerExternalOES sampler, vec2 coord);
```

sampler 是已通过 uniform 指向纹理单元的采样器，coord 是归一化 UV，返回采样 RGBA vec4。过滤和边界行为由绑定纹理的参数决定。

```glsl
vec4 source = texture2D(uTexture, vTexCoord);
```

它不接受 textureId，也不读取 Android View 像素。OES sampler 必须声明对应扩展；采样返回值已经是纹理可见颜色表示，不能把它直接当相机 YUV 原始平面。

普通 sampler2D 还有使用偏差参数的版本，但 OES 的纹理/LOD 限制不同；本文只使用共同的两参数路径，不把 mipmap bias 任意套到外部相机纹理。

#### 21.13.3 dot：计算两个向量的点积

```glsl
float dot(vec3 x, vec3 y);
```

x/y 必须有相同维度，返回各分量乘积的和。支持相应浮点向量维度，不只 vec3。

```glsl
float gray = dot(source.rgb, vec3(0.299, 0.587, 0.114));
```

这句等于 R×0.299 + G×0.587 + B×0.114。dot 不自动转换色彩空间，权重应匹配你的颜色/亮度定义。

#### 21.13.4 mix：按权重插值

```glsl
vec3 mix(vec3 x, vec3 y, float a);
```

x 为起点，y 为终点，a 为插值权重；返回 `x×(1-a) + y×a`。a=0 为 x，a=1 为 y，中间连续混合。也支持同型向量权重等合法重载。

```glsl
vec3 filtered = mix(source.rgb, vec3(gray), uIntensity);
```

mix 不自动把 a 限制到 0～1，超出会外推；本文 UI/Processor 将强度约束。饱和度算法可能故意使用大于 1 的权重，随后处理颜色范围。

#### 21.13.5 clamp：将数值限制在范围内

```glsl
vec3 clamp(vec3 x, float minVal, float maxVal);
float clamp(float x, float minVal, float maxVal);
```

x 为待限制数据，minVal/maxVal 为下限/上限；返回逐分量 `min(max(x,minVal),maxVal)`。

```glsl
vec3 safeRgb = clamp(adjustedRgb, 0.0, 1.0);
```

下限应不大于上限。clamp 是范围裁切，不能替代 HDR 到 SDR 的正确 tone mapping，也不进行 gamma 转换。

#### 21.13.6 floor 与 fract：LUT 切片位置及插值权重

```glsl
float floor(float x);
float fract(float x);
```

两个函数的唯一参数 x 为输入数值。floor 返回不大于 x 的最大整数的浮点表示；fract 返回 `x-floor(x)`，用于小数部分，也支持逐分量向量重载。

```glsl
float slice = blue * (lutSize - 1.0);
float lower = floor(slice);
float weight = fract(slice);
```

这里 floor 的返回仍是浮点，不能当任意动态数组索引使用而不考虑 GLSL ES 1.00 限制。负数的 fract 与一些语言的取余行为不同，LUT 输入先约束在有效颜色范围。

#### 21.13.7 min / max 与 mod：限制上切片并定位 atlas

```glsl
float min(float x, float y);
float max(float x, float y);
float mod(float x, float y);
```

min/max 返回两输入较小/较大值，也支持合法向量重载。mod(x,y) 返回 `x-y*floor(x/y)`，不是 GLSL 浮点 `%` 运算，y 不应为 0。

```glsl
float upper = min(lower + 1.0, lutSize - 1.0);
float tileX = mod(lower, tilesPerRow);
float tileY = floor(lower / tilesPerRow);
```

atlas 维度、tile 数和 LUT 文件的排列规则必须对应。min 限制最高切片，mod/floor 决定列行，随后加半 texel 偏移再除以 atlas 像素尺寸取得 UV。

#### 21.13.8 gl_Position 与 gl_FragColor：阶段的输出变量

```glsl
gl_Position = aPosition;
gl_FragColor = vec4(filteredRgb, alpha);
```

它们不是函数，没有参数列表。GLSL ES 1.00 的 gl_Position 是 vertex shader 输出的齐次裁剪坐标，经过 w 除法和 viewport 变换；gl_FragColor 是 fragment shader 输出的 RGBA，之后仍受混合、写入等状态影响。

将颜色赋给 gl_FragColor 不自动提交 Android Surface。shader 执行依赖 draw，最终窗口帧提交依赖 EGL swap。迁移 GLSL ES 3.x 时，片元输出需要采用该版本的显式 out 变量用法。

### 21.14 将 API 放回一帧的完整调用顺序

| 阶段 | 关键调用 | 此时数据或状态的变化 |
|---|---|---|
| 一次性 EGL 初始化 | getDisplay → initialize → chooseConfig → createContext → createPbuffer → makeCurrent | GL 线程有可用 context 和目标 |
| 一次性程序初始化 | createShader → shaderSource → compile/status/log → createProgram → attach → link/status/log → getLocation | 可以执行的 GPU 程序与变量位置已准备 |
| 输入请求 | genTexture → bind OES → texParameteri → SurfaceTexture → defaultBufferSize → provideSurface | 相机有可写的输入队列 |
| 输出接入 | getSurface → createWindowSurface | 存在可提交给下游的 EGL 目标 |
| 帧取得 | makePbufferCurrent → updateTexImage → getTransformMatrix/getTimestamp | OES 纹理与元信息对应最新取得帧 |
| 输出映射 | SurfaceOutput.updateTransformMatrix | 获取当前输出需要的采样映射 |
| 绘制准备 | makeCurrent → bindFramebuffer → viewport → clear/state → useProgram | 当前 target 与 GL 状态确定 |
| 输入和参数 | enableAttrib/pointer → uniformMatrix/intensity → activeTexture/bind → sampler uniform | 顶点、采样器、矩阵和算法参数配套 |
| 处理提交 | drawArrays → presentationTime → swapBuffers | GPU 滤镜结果进入下游队列 |
| 输出收尾 | 切回 Pbuffer → destroy EGLSurface → SurfaceOutput.close | 停止写这个下游 |
| 输入归还 | 移除监听 → release Surface/SurfaceTexture → deleteTexture | 相机不再使用的输入资源回收 |
| 最终退出 | deleteProgram/中间资源 → 解除 current → destroySurface/context → terminate → releaseThread | 保证所有输入归还后退出 GL 环境 |

想扩展功能时，先判断它改变哪一类状态：只调颜色新增 uniform；读取邻域引入额外采样或 FBO；叠加图片新增二维纹理和 draw；改变区域新增矩阵；保存图像新增受控读回。这样能确定应该修改哪一段，而不是把新 API 随意塞进帧循环。
