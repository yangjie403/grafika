# 使用 OpenGL ES 实现视频播放

本文介绍 Android 上如何把视频文件解码为图像，并使用 OpenGL ES 对视频帧进行绘制、缩放、旋转、裁剪和滤镜处理。内容以 Java、`MediaExtractor`、`MediaCodec`、`SurfaceTexture`、EGL 和 OpenGL ES 2.0/3.0 为例，重点对应本仓库中的 Grafika 实现。

先明确一个边界：OpenGL ES 本身不负责解析 MP4，也不负责解码 H.264/H.265。OpenGL ES 负责的是“拿到已经解码的图像后如何绘制”。通常由 `MediaCodec` 完成视频解码，由 `SurfaceTexture` 把解码器输出转换为 OpenGL ES 可以采样的外部纹理。

## 1. 两种视频显示方案

### 1.1 解码器直接输出到 Surface

```text
视频文件
   │
   ├─ MediaExtractor：读取压缩样本
   │
   ├─ MediaCodec：解码
   │
   └─ SurfaceView / TextureView 的 Surface：显示
```

这种方案不需要应用编写 OpenGL ES 绘制代码。它的优点是实现简单、功耗和拷贝开销通常较低，适合普通视频播放；缺点是应用无法方便地在视频图像上叠加自定义 GL 滤镜、3D 场景或复杂的合成效果。

本仓库的 [`MoviePlayer.java`](../app/src/main/java/com/android/grafika/MoviePlayer.java) 使用的就是这条链路：

```java
decoder.configure(format, outputSurface, null, 0);
```

其中 `outputSurface` 可以来自 `SurfaceView`，也可以来自 `SurfaceTexture` 包装出的 `Surface`。

### 1.2 解码到 SurfaceTexture，再由 OpenGL ES 绘制

```text
视频文件
   │
   ├─ MediaExtractor
   │
   ├─ MediaCodec
   │       │ 输出到
   │       ▼
   │    Surface
   │       │ 连接到
   │       ▼
   │    SurfaceTexture ── updateTexImage() ── 外部纹理 GL_TEXTURE_EXTERNAL_OES
   │                                                   │
   │                                                   ▼
   │                                         Vertex Shader / Fragment Shader
   │                                                   │
   │                                                   ▼
   │                                  EGLSurface ── eglSwapBuffers() ── 屏幕
```

这种方案中，视频帧不需要从 GPU 读回 CPU。解码器把帧写入 `SurfaceTexture` 的 BufferQueue，`SurfaceTexture.updateTexImage()` 把最新缓冲区绑定到外部纹理，片元着色器直接从 `samplerExternalOES` 采样。

它适合以下场景：

- 视频旋转、镜像、裁剪和保持宽高比；
- 黑白、颜色调整、卷积、边缘检测等滤镜；
- 视频与其他纹理、FBO、粒子或 3D 场景合成；
- 把视频同时绘制到屏幕、编码器输入 Surface 或离屏纹理。

## 2. 核心对象的职责

| 对象 | 职责 | 是否是 OpenGL ES 对象 |
|---|---|---|
| `MediaExtractor` | 从 MP4 等容器中读取压缩视频样本和时间戳 | 否 |
| `MediaFormat` | 描述 MIME、宽高、帧率、CSD 等媒体格式 | 否 |
| `MediaCodec` | 将压缩视频样本解码成原始视频帧 | 否 |
| `Surface` | Android 图形 BufferQueue 的生产端接口，可作为解码器输出目标 | 否 |
| `SurfaceTexture` | 消费 BufferQueue 中的图像，并将其关联到 GL 外部纹理 | Android 图形对象，与 GL 互操作 |
| `GL_TEXTURE_EXTERNAL_OES` | 采样 `SurfaceTexture` 最新图像的纹理目标 | 是 |
| `EGLDisplay` | EGL 与系统显示设备的连接 | EGL 对象 |
| `EGLContext` | 保存 OpenGL ES 对象和状态 | EGL 对象 |
| `EGLSurface` | 把 GL 绘制结果连接到 Android `Surface` 或 `SurfaceTexture` | EGL 对象 |
| `Texture2dProgram` | Shader program 和纹理采样逻辑 | 项目封装 |
| `FullFrameRect` | 用一个全屏矩形绘制视频纹理 | 项目封装 |

要避免把以下几个概念混淆：

- `Surface` 是 Android 图形系统的接口；
- `SurfaceTexture` 是 Android 图形缓冲区与纹理之间的桥梁；
- `EGLSurface` 是 EGL 对 OpenGL ES 绘制目标的封装；
- `GL_TEXTURE_EXTERNAL_OES` 是 OpenGL ES 中的纹理目标，不能当作普通 `GL_TEXTURE_2D` 使用。

## 3. 完整播放流程

### 3.1 创建并绑定 OpenGL ES 环境

播放线程需要先创建 EGL display、EGL config、EGL context 和窗口 EGL surface，并让 EGL context 在当前线程成为 current：

```java
EglCore eglCore = new EglCore(null, EglCore.FLAG_TRY_GLES3);
WindowSurface windowSurface = new WindowSurface(eglCore, androidSurface, false);
windowSurface.makeCurrent();
```

这里的 `androidSurface` 可以来自 `SurfaceView` 的 `SurfaceHolder.getSurface()`，也可以来自一个专门给 GL 显示用的 `Surface`。

本仓库的 [`EglCore.java`](../app/src/main/java/com/android/grafika/gles/EglCore.java) 会完成以下工作：

1. `EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)` 获取 display；
2. `EGL14.eglInitialize()` 初始化 EGL；
3. `eglChooseConfig()` 选择颜色缓冲配置；
4. `eglCreateContext()` 创建 GLES 2 或 GLES 3 context；
5. `eglCreateWindowSurface()` 创建窗口 EGL surface；
6. `eglMakeCurrent()` 将 context 和 surface 绑定到当前线程。

OpenGL ES 的纹理、Shader、Program、FBO 等对象都属于某个 `EGLContext`。它们必须在创建它们的 context current 时使用，通常也必须在同一个 GL 线程上销毁。

### 3.2 创建外部纹理和 SurfaceTexture

创建一个外部纹理：

```java
int[] textureIds = new int[1];
GLES20.glGenTextures(1, textureIds, 0);
int textureId = textureIds[0];

GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
GLES20.glTexParameterf(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MIN_FILTER,
        GLES20.GL_LINEAR);
GLES20.glTexParameterf(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MAG_FILTER,
        GLES20.GL_LINEAR);
GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_S,
        GLES20.GL_CLAMP_TO_EDGE);
GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_T,
        GLES20.GL_CLAMP_TO_EDGE);

SurfaceTexture surfaceTexture = new SurfaceTexture(textureId);
Surface decoderSurface = new Surface(surfaceTexture);
```

此时的关系是：

```text
textureId ──属于当前 EGLContext
    │
    ▼
SurfaceTexture(textureId)
    │
    ▼
Surface(surfaceTexture)
    │
    ▼
MediaCodec.configure(..., decoderSurface, ...)
```

`SurfaceTexture` 创建时传入的纹理必须是 `GL_TEXTURE_EXTERNAL_OES` 目标绑定的纹理。不能先用 `GL_TEXTURE_2D` 创建普通纹理再传入。

### 3.3 创建 MediaExtractor 并选择视频轨道

```java
MediaExtractor extractor = new MediaExtractor();
extractor.setDataSource(videoFile.toString());

int videoTrack = -1;
for (int i = 0; i < extractor.getTrackCount(); i++) {
    MediaFormat trackFormat = extractor.getTrackFormat(i);
    String mime = trackFormat.getString(MediaFormat.KEY_MIME);
    if (mime != null && mime.startsWith("video/")) {
        videoTrack = i;
        break;
    }
}

if (videoTrack < 0) {
    throw new IOException("No video track found");
}

extractor.selectTrack(videoTrack);
MediaFormat format = extractor.getTrackFormat(videoTrack);
String mime = format.getString(MediaFormat.KEY_MIME);
```

不要只手动构造包含 MIME、宽高的 `MediaFormat`。`getTrackFormat()` 返回的格式还可能包含 `csd-0`、`csd-1` 等 codec-specific data，它们通常包含 H.264/H.265 解码所需的 SPS/PPS 等参数。

### 3.4 配置并启动解码器

```java
MediaCodec decoder = MediaCodec.createDecoderByType(mime);
decoder.configure(format, decoderSurface, null, 0);
decoder.start();
```

解码器配置完成后，`MediaCodec` 会把解码后的图像写入 `decoderSurface`。在 Surface 输出模式中，应用不需要也不能通过普通输出 `ByteBuffer` 读取 YUV 数据。

### 3.5 向解码器喂入压缩样本

旧版同步 API 的典型循环如下：

```java
boolean inputDone = false;
ByteBuffer[] inputBuffers = decoder.getInputBuffers();

while (!inputDone) {
    int inputIndex = decoder.dequeueInputBuffer(10_000);
    if (inputIndex < 0) {
        continue;
    }

    ByteBuffer inputBuffer = inputBuffers[inputIndex];
    int sampleSize = extractor.readSampleData(inputBuffer, 0);

    if (sampleSize < 0) {
        decoder.queueInputBuffer(
                inputIndex, 0, 0, 0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        inputDone = true;
    } else {
        long presentationTimeUs = extractor.getSampleTime();
        decoder.queueInputBuffer(
                inputIndex, 0, sampleSize, presentationTimeUs, 0);
        extractor.advance();
    }
}
```

实际播放时不能在送入 EOS 后立即结束循环，因为解码器可能还持有尚未输出的参考帧。必须继续调用 `dequeueOutputBuffer()`，直到收到输出 EOS。

Android API 21 及以上还可以使用 `getInputBuffer(index)`、`getOutputBuffer(index)`，或者使用 `MediaCodec.Callback` 异步模式。Surface 输出模式下通常只需要关注输出回调和 `BufferInfo`，不需要读取输出字节。

### 3.6 取出解码输出并通知 Surface

```java
boolean outputDone = false;
MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

while (!outputDone) {
    int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000);

    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
        continue;
    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        MediaFormat outputFormat = decoder.getOutputFormat();
        // 可以在这里读取实际输出宽高、裁剪区域等信息。
    } else if (outputIndex >= 0) {
        boolean endOfStream =
                (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        boolean render = bufferInfo.size > 0;

        decoder.releaseOutputBuffer(outputIndex, render);

        if (endOfStream) {
            outputDone = true;
        }
    }
}
```

`releaseOutputBuffer(outputIndex, true)` 是 Surface 输出模式中“把这一帧交给 Surface”的动作。它不表示物理屏幕已经扫描显示，只表示解码器已经提交该输出缓冲区。

如果需要按照视频 PTS 播放，可以在 `releaseOutputBuffer()` 之前根据 `bufferInfo.presentationTimeUs` 等待；如果使用 API 21 的带时间参数版本，也可以让系统按照指定时间渲染，但仍要正确处理时钟、暂停、跳转和丢帧策略。

### 3.7 在 OpenGL ES 中消费 SurfaceTexture

当解码器向 `Surface` 提交新帧后，`SurfaceTexture` 会拥有一帧可更新的图像。GL 线程需要调用：

```java
surfaceTexture.updateTexImage();
surfaceTexture.getTransformMatrix(textureMatrix);
long timestampNanos = surfaceTexture.getTimestamp();
```

调用含义：

- `updateTexImage()`：将最新 BufferQueue 缓冲区绑定到关联的外部纹理。每次绘制新帧前调用一次；
- `getTransformMatrix(float[] mtx)`：取得由 `SurfaceTexture` 提供的纹理坐标变换矩阵，数组必须至少有 16 个元素；
- `getTimestamp()`：取得当前纹理图像的时间戳，单位为纳秒。它适合用于音视频同步或编码时设置 PTS，但不能简单当作 Java `System.nanoTime()` 的绝对时间来比较而不做基准处理。

常见的帧驱动方式有两种：

1. `SurfaceTexture.OnFrameAvailableListener` 收到通知后向 GL 线程发送消息；
2. 使用 `Choreographer` 或固定刷新循环，在 GL 线程中检查并调用 `updateTexImage()`。

回调只表示“可能有新帧”，并不应在回调线程直接执行所有 GL 绘制。更安全的做法是将绘制工作投递到拥有 EGL context 的 GL 线程。

### 3.8 运行 Shader 并绘制

视频外部纹理的片元 Shader 必须声明扩展并使用 `samplerExternalOES`：

```glsl
#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;

void main() {
    gl_FragColor = texture2D(sTexture, vTextureCoord);
}
```

顶点 Shader 通常同时接收顶点位置矩阵和 `SurfaceTexture` 的纹理矩阵：

```glsl
uniform mat4 uMVPMatrix;
uniform mat4 uTexMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;

void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTextureCoord = (uTexMatrix * aTextureCoord).xy;
}
```

Java 端的典型绘制顺序：

```java
surfaceTexture.updateTexImage();
surfaceTexture.getTransformMatrix(textureMatrix);

GLES20.glViewport(0, 0, width, height);
GLES20.glClearColor(0f, 0f, 0f, 1f);
GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

GLES20.glUseProgram(program);
GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
GLES20.glUniform1i(textureSamplerLocation, 0);
GLES20.glUniformMatrix4fv(
        textureMatrixLocation, 1, false, textureMatrix, 0);

// 设置 aPosition、aTextureCoord 的顶点数据后绘制全屏矩形。
GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

windowSurface.swapBuffers();
```

本仓库已经把这部分封装为 [`Texture2dProgram.java`](../app/src/main/java/com/android/grafika/gles/Texture2dProgram.java) 和 [`FullFrameRect.java`](../app/src/main/java/com/android/grafika/gles/FullFrameRect.java)：

```java
FullFrameRect fullFrame = new FullFrameRect(
        new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

fullFrame.drawFrame(textureId, textureMatrix);
windowSurface.swapBuffers();
```

## 4. 关键 API 详细说明

下面按模块列出播放链路中最重要的 API。参数名称与 Android SDK 基本一致。

### 4.1 `MediaExtractor`

#### `setDataSource(String path)`

```java
extractor.setDataSource(file.getAbsolutePath());
```

- `path`：媒体文件的路径；
- 作用：打开容器并准备读取轨道和样本；
- 异常：可能抛出 `IOException` 或平台相关的 `RuntimeException`；
- 注意：使用完成后必须调用 `release()`。

#### `getTrackCount()`

返回容器中的轨道数。一个 MP4 可能同时包含视频、音频、字幕和元数据轨道。

#### `getTrackFormat(int trackIndex)`

- `trackIndex`：`0` 到 `getTrackCount() - 1` 的轨道索引；
- 返回：该轨道的 `MediaFormat`；
- 视频轨道通常至少包含 `KEY_MIME`、`KEY_WIDTH`、`KEY_HEIGHT`，并可能包含帧率、旋转角度、CSD 等字段。

#### `selectTrack(int trackIndex)` / `unselectTrack(int trackIndex)`

选择或取消选择一个轨道。调用 `readSampleData()` 前必须先选择需要读取的轨道。视频播放通常只选择 MIME 以 `video/` 开头的轨道；有音视频同步需求时还要选择并处理音频轨道。

#### `readSampleData(ByteBuffer byteBuf, int offset)`

- `byteBuf`：由 `MediaCodec` 提供的输入缓冲区；
- `offset`：写入缓冲区的起始偏移，通常为 `0`；
- 返回值：复制到缓冲区的字节数；返回负数表示没有更多样本，即输入 EOS；
- 注意：返回负数时不要调用 `advance()`。

#### `getSampleTime()`

返回当前样本的 presentation timestamp，单位是微秒。这个值应作为 `queueInputBuffer()` 的 `presentationTimeUs` 参数传入。

#### `getSampleTrackIndex()`

返回当前样本所属的轨道索引。选择单一视频轨道时通常应与已选择的索引一致，异常情况下可用于诊断。

#### `advance()`

移动到当前轨道的下一个样本。成功读取并排入当前样本后调用。

#### `seekTo(long timeUs, int mode)`

- `timeUs`：目标时间，单位微秒；
- `mode`：常用值包括 `SEEK_TO_PREVIOUS_SYNC`、`SEEK_TO_NEXT_SYNC`、`SEEK_TO_CLOSEST_SYNC`；
- 作用：跳转到接近目标时间的关键帧；
- 注意：跳转后通常需要重新 flush 解码器，并清理旧的输入和输出状态。

#### `release()`

释放 extractor 持有的文件和 native 资源。应放在 `finally` 中，且不要在其他线程仍使用它时释放。

### 4.2 `MediaFormat`

常用键：

| Key | 含义 | 单位/类型 |
|---|---|---|
| `KEY_MIME` | 编码格式，例如 `video/avc`、`video/hevc` | `String` |
| `KEY_WIDTH` | 编码宽度 | `int`，像素 |
| `KEY_HEIGHT` | 编码高度 | `int`，像素 |
| `KEY_FRAME_RATE` | 标称帧率 | `float` 或 `int` |
| `KEY_DURATION` | 媒体时长 | `long`，微秒 |
| `KEY_ROTATION` | 视频旋转角度 | `int`，度数 |
| `KEY_CROP_LEFT` 等 | 有效裁剪区域 | `int`，像素 |
| `KEY_COLOR_FORMAT` | 编解码器颜色格式 | `int` |
| `csd-0`、`csd-1` | codec-specific data | `ByteBuffer` |

对于 Surface 输出，应用通常不需要读取 YUV color format；应把完整的轨道 `MediaFormat` 原样传给 `MediaCodec.configure()`。

### 4.3 `MediaCodec`

#### `createDecoderByType(String type)`

- `type`：媒体 MIME 类型，例如 `video/avc`；
- 返回：能够处理该类型的解码器实例；
- 注意：不同设备支持的硬件/软件解码器不同，创建成功不代表所有格式参数都一定支持。

#### `configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags)`

- `format`：解码轨道格式，通常直接使用 `extractor.getTrackFormat()` 的返回值；
- `surface`：视频帧输出目标。传入非空 `Surface` 时使用 Surface 输出模式；传 `null` 时通常进入 ByteBuffer 输出模式；
- `crypto`：加密媒体的解密对象，普通明文视频传 `null`；
- `flags`：解码器配置标志，解码通常传 `0`，编码器才使用 `CONFIGURE_FLAG_ENCODE`；
- 注意：`configure()` 后不能随意改变输入输出模式，必须按 `start()`、工作、`stop()`、`release()` 的状态机顺序调用。

#### `start()`

启动解码器。启动前必须完成 `configure()`，启动后才可以获取输入缓冲区或投递异步回调。

#### `dequeueInputBuffer(long timeoutUs)`

- `timeoutUs`：等待输入缓冲区的最长时间，单位微秒；`0` 表示立即返回，较大的值可以减少忙轮询；
- 返回：可用输入缓冲区索引；负数表示暂时不可用。

#### `queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags)`

- `index`：由 `dequeueInputBuffer()` 返回的输入缓冲区索引；
- `offset`：有效压缩数据在缓冲区中的起始偏移；
- `size`：有效数据大小；
- `presentationTimeUs`：样本的展示时间戳，单位微秒；
- `flags`：样本标志，输入 EOS 使用 `BUFFER_FLAG_END_OF_STREAM`；
- 注意：调用后该缓冲区归 `MediaCodec` 所有，不能继续修改。

#### `dequeueOutputBuffer(BufferInfo info, long timeoutUs)`

- `info`：由调用方复用的 `MediaCodec.BufferInfo`，用于接收输出偏移、大小、PTS 和 flags；
- `timeoutUs`：等待输出的最长时间，单位微秒；
- 返回：输出缓冲区索引，或 `INFO_TRY_AGAIN_LATER`、`INFO_OUTPUT_FORMAT_CHANGED`、`INFO_OUTPUT_BUFFERS_CHANGED` 等特殊值。

#### `releaseOutputBuffer(int index, boolean render)`

- `index`：输出缓冲区索引；
- `render`：Surface 输出模式下是否将这一帧提交给输出 Surface。视频播放通常在 `info.size > 0` 时传 `true`；
- 注意：输出 EOS 可能与最后一帧同时到达，不能因为检测到 EOS 就跳过最后一个有效图像。

#### `flush()`

清空解码器中尚未处理的输入和输出。用于 seek 或循环播放时，必须重新按照解码器要求投递数据；不能把 flush 当成完整的 stop/start 替代品。

#### `stop()` / `release()`

`stop()` 停止已启动的 codec，`release()` 释放实例及 native 资源。推荐在 `finally` 中按以下顺序执行：

```java
if (decoder != null) {
    decoder.stop();
    decoder.release();
}
if (extractor != null) {
    extractor.release();
}
```

如果解码器仍可能向 `Surface` 提交帧，不要先释放 `Surface` 或 `SurfaceTexture`。

### 4.4 `SurfaceTexture`

#### `new SurfaceTexture(int texName)`

- `texName`：已经在当前 GL context 中创建的外部纹理名称；
- 作用：将 BufferQueue 的消费者连接到该外部纹理；
- 线程要求：后续 `updateTexImage()` 等消费者操作应由拥有该纹理和 EGL context 的 GL 线程执行。

#### `setOnFrameAvailableListener(...)`

注册新帧通知。回调线程取决于创建方式和重载版本：可能是创建 `SurfaceTexture` 的线程，也可能是指定的 `Handler` 所在线程。回调中建议只做轻量通知，不直接执行跨 context 的 GL 调用。

#### `updateTexImage()`

把最新可用图像更新到关联外部纹理。调用前必须有 current 的 EGL context，并且该 context 能访问对应纹理。每次实际绘制前调用一次即可；连续调用而没有新帧时通常只会保持当前图像。

#### `getTransformMatrix(float[] mtx)`

- `mtx`：长度至少为 16 的数组；
- 返回：4×4 纹理坐标矩阵；
- 作用：处理图像方向、裁剪和坐标约定差异；
- 注意：不要硬编码“视频一定上下翻转”，优先使用这个矩阵。

#### `getTimestamp()`

返回当前图像的时间戳，单位纳秒。可用于给编码器设置 presentation time，或与音频时钟建立同步关系。

#### `release()`

释放 `SurfaceTexture` 关联的 native 资源。调用前必须确保解码器已停止，且没有其他线程再调用 `updateTexImage()`。

### 4.5 `Surface`

#### `new Surface(SurfaceTexture surfaceTexture)`

把 `SurfaceTexture` 包装成解码器可以写入的 Android `Surface`：

```java
Surface outputSurface = new Surface(surfaceTexture);
```

它是一个独立的 Java 包装对象，使用完必须调用 `release()`。推荐由创建并拥有它的播放线程释放，而且要在 `MediaCodec` 停止之后释放。

`Surface` 和 `EGLSurface` 不是同一个对象：前者是 Android 图形接口，后者是 EGL 对它的 GL 绘制封装。

### 4.6 EGL API

#### `eglGetDisplay(EGLDisplay displayId)`

取得 EGL 与原生显示设备的连接。常用参数是 `EGL14.EGL_DEFAULT_DISPLAY`。

#### `eglInitialize(EGLDisplay display, int[] major, int majorOffset, int[] minor, int minorOffset)`

初始化 display，并把 EGL 主、次版本写入数组。数组可以是 `null`，但需要版本号时应提供长度至少为 1 的数组和偏移量。

#### `eglChooseConfig(...)`

根据属性选择 `EGLConfig`。常见属性包括：

- `EGL_RED_SIZE`、`EGL_GREEN_SIZE`、`EGL_BLUE_SIZE`、`EGL_ALPHA_SIZE`：颜色通道位数；
- `EGL_DEPTH_SIZE`：深度缓冲位数；
- `EGL_STENCIL_SIZE`：模板缓冲位数；
- `EGL_RENDERABLE_TYPE`：允许 GLES 2 或 GLES 3；
- `EGL_NONE`：属性列表结束标志。

如果 EGL surface 最终用于 MediaCodec 编码器输入，通常需要 Android 扩展属性 `EGL_RECORDABLE_ANDROID`；普通视频播放显示到屏幕时一般不需要它。

#### `eglCreateContext(display, config, shareContext, attribList, offset)`

- `display`：已初始化的 EGL display；
- `config`：选定的 EGLConfig；
- `shareContext`：可选的共享 context；传 `EGL_NO_CONTEXT` 表示不共享；
- `attribList`：包括 `EGL_CONTEXT_CLIENT_VERSION, 2` 或 `3` 的属性列表；
- 作用：创建保存纹理、Shader、FBO 等 GL 状态的 context。

两个共享 context 仍然各自需要有 current 的 EGLSurface，也不能把同一个 context 同时绑定到多个线程。

#### `eglCreateWindowSurface(display, config, nativeWindow, attribList, offset)`

把 Android `Surface` 或 `SurfaceTexture` 连接为 EGL 的窗口绘制目标。`nativeWindow` 必须是有效的 native window 对象，属性列表通常只包含 `EGL_NONE`。

#### `eglMakeCurrent(display, draw, read, context)`

把 context 和绘制/读取 surface 绑定到当前线程。最常见的绘制调用是：

```java
EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
```

后续 GL 调用使用的就是这个 current context。切换 surface 或 context 后，应重新确认 viewport、绑定纹理、program 等状态。

#### `eglSwapBuffers(display, surface)`

提交当前窗口 surface 的 back buffer，使本帧进入 Android 图形系统。它不是 MediaCodec 解码播放必需的 API；它是 GL 绘制到屏幕或编码器输入 surface 时的发布动作。

#### `eglDestroySurface()`、`eglDestroyContext()`、`eglTerminate()`

按顺序释放 EGL window surface、context 和 display。释放前应解除 current：

```java
EGL14.eglMakeCurrent(
        display,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_CONTEXT);
```

本仓库的 `EglCore.release()` 已经封装了这类清理流程。

### 4.7 OpenGL ES 纹理和绘制 API

#### `glGenTextures(int n, int[] textures, int offset)`

生成 `n` 个纹理名称并写入 `textures` 数组。`offset` 是写入数组的起始位置。

#### `glBindTexture(int target, int texture)`

把纹理名称绑定到目标。视频 `SurfaceTexture` 使用：

```java
GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
```

不能把同一个外部纹理绑定为 `GL_TEXTURE_2D`。

#### `glTexParameterf()` / `glTexParameteri()`

设置纹理采样参数。视频常用：

- `GL_TEXTURE_MIN_FILTER = GL_LINEAR`：缩小时线性过滤；
- `GL_TEXTURE_MAG_FILTER = GL_LINEAR`：放大时线性过滤；
- `GL_TEXTURE_WRAP_S/T = GL_CLAMP_TO_EDGE`：边缘采样不重复。

外部纹理支持的参数和设备实现存在差异，应只使用 `SurfaceTexture` 外部纹理要求的安全配置。

#### `glCreateShader()`、`glShaderSource()`、`glCompileShader()`

创建、设置源码并编译 vertex shader 或 fragment shader。编译后必须用 `glGetShaderiv(shader, GL_COMPILE_STATUS, ...)` 检查状态，并在失败时读取 `glGetShaderInfoLog()`。

#### `glCreateProgram()`、`glAttachShader()`、`glLinkProgram()`

创建 program，附加已编译的 vertex/fragment shader 并链接。链接后检查 `GL_LINK_STATUS` 和 `glGetProgramInfoLog()`。

#### `glUseProgram(int program)`

选择当前绘制使用的 program。后续 uniform 和 attribute 操作都会作用于这个 program。

#### `glGetAttribLocation()` / `glGetUniformLocation()`

查询 Shader 中 attribute 和 uniform 的位置。位置可能是 `-1`，表示编译器优化掉了对应变量；不能无条件把 `-1` 传给后续 API。

#### `glUniformMatrix4fv(int location, int count, boolean transpose, float[] value, int offset)`

- `location`：矩阵 uniform 的位置；
- `count`：矩阵数量，单个矩阵传 `1`；
- `transpose`：OpenGL ES 中通常传 `false`；
- `value`：矩阵数组；
- `offset`：数组起始偏移；
- 常用于上传 `SurfaceTexture.getTransformMatrix()` 返回的纹理矩阵。

#### `glVertexAttribPointer()` / `glEnableVertexAttribArray()`

指定顶点位置和纹理坐标的内存布局并启用 attribute。参数重点包括：

- `indx`：attribute 位置；
- `size`：每个顶点的分量数，例如二维坐标为 `2`；
- `type`：通常为 `GL_FLOAT`；
- `normalized`：浮点数据通常传 `false`；
- `stride`：相邻顶点之间的字节步长；
- `ptr`：顶点数据缓冲区。

#### `glDrawArrays(int mode, int first, int count)`

按照顶点数组绘制图元。全屏矩形常用 `GL_TRIANGLE_STRIP`，四个顶点的 `count` 为 `4`。

#### `glViewport(int x, int y, int width, int height)`

设置 OpenGL ES 输出区域。注意它的坐标原点在左下角，而 Android View 坐标原点通常在左上角；涉及裁剪区域时要转换坐标。

#### `glDeleteTextures()`、`glDeleteProgram()`、`glDeleteShader()`

释放 GL 对象。删除操作必须在拥有这些对象的 EGL context current 时执行；如果 context 即将整体销毁，也可以先释放 context，但不应再使用这些 Java 包装对象。

## 5. 一个可落地的播放线程结构

生产代码不建议在 Activity 中同时管理所有 EGL、解码和线程状态。可以按以下职责拆分：

```text
Activity / ViewModel
    ├─ 创建、暂停、恢复、停止播放器
    └─ 处理生命周期和用户操作

VideoDecoderThread
    ├─ MediaExtractor
    ├─ MediaCodec
    ├─ SurfaceTexture / decoder Surface 的创建或协作
    └─ 停止、EOS、seek

GlRenderThread
    ├─ EGLDisplay / EGLContext / EGLSurface
    ├─ 外部纹理和 SurfaceTexture 的消费者操作
    ├─ updateTexImage()
    ├─ Shader、矩形和滤镜
    └─ eglSwapBuffers()
```

如果解码器输出 `SurfaceTexture`，解码线程和 GL 线程之间只传递“有新帧”的通知，不传递像素数组。一个简化的初始化顺序如下：

```java
// GL 线程中
eglCore = new EglCore(null, EglCore.FLAG_TRY_GLES3);
windowSurface = new WindowSurface(eglCore, displaySurface, false);
windowSurface.makeCurrent();

textureId = createExternalTexture();
surfaceTexture = new SurfaceTexture(textureId);
surface = new Surface(surfaceTexture);
program = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
fullFrame = new FullFrameRect(program);

// 可在解码线程或控制线程中创建，但必须在真正开始前保证 Surface 有效
decoder = createAndConfigureDecoder(videoFile, surface);
decoder.start();

// GL 线程的每帧工作
surfaceTexture.updateTexImage();
surfaceTexture.getTransformMatrix(textureMatrix);
fullFrame.drawFrame(textureId, textureMatrix);
windowSurface.swapBuffers();
```

实际代码还必须处理“还没有新帧”“Surface 已销毁”“解码器已经 EOS”“线程正在停止”等状态，不能假设每次 GL 循环都有有效视频帧。

## 6. 播放速度、PTS 和音视频同步

### 6.1 PTS 的单位

- `MediaExtractor.getSampleTime()`：微秒；
- `MediaCodec.BufferInfo.presentationTimeUs`：微秒；
- `SurfaceTexture.getTimestamp()`：纳秒；
- `System.nanoTime()`：纳秒。

不要直接混用这些值。做同步时应统一到同一个单位，并建立明确的起始基准：

```text
相对视频时间 = 当前样本 PTS - 第一帧 PTS
目标现实时间 = 播放开始单调时钟 + 相对视频时间
```

### 6.2 仅视频播放

可以在提交解码输出前按照相邻 PTS 的差值等待，或让渲染线程以 VSYNC 驱动。不要用 `System.currentTimeMillis()` 作为播放基准，因为墙上时间可能被系统校准或修改。

本仓库的 [`SpeedControlCallback.java`](../app/src/main/java/com/android/grafika/SpeedControlCallback.java) 使用 `System.nanoTime()` 控制帧提交节奏。

### 6.3 音视频播放

完整播放器通常需要：

1. 视频解码和音频解码分别运行；
2. 以音频时钟、系统单调时钟或外部主时钟作为同步基准；
3. 视频过快时丢帧，视频过慢时等待；
4. 处理 seek、暂停、恢复和输出设备延迟。

如果需求包括稳定音视频同步、字幕、自适应码率或 DRM，通常应评估 AndroidX Media3/ExoPlayer，而不是从 `MediaExtractor + MediaCodec` 重新实现完整播放器。

## 7. SurfaceView、TextureView 和自建 SurfaceTexture 的选择

### 7.1 `SurfaceView`

适合普通视频显示和低功耗播放。解码器可以直接输出到：

```java
Surface surface = surfaceView.getHolder().getSurface();
decoder.configure(format, surface, null, 0);
```

优点是路径短、通常不需要应用层 GL 合成；缺点是与普通 View 的叠加、变换和裁剪能力较受限。

### 7.2 `TextureView`

`TextureView` 本身通过 `SurfaceTexture` 接入系统合成，方便放进普通 View 层级，也可以应用 View 变换。它适合简单显示，但如果要让应用自己用 OpenGL ES 采样视频，最好明确谁拥有 `SurfaceTexture`、谁调用 `updateTexImage()`，避免把同一个纹理消费者同时交给多个线程或 context。

`TextureView.SurfaceTextureListener` 的主要回调：

- `onSurfaceTextureAvailable(st, width, height)`：SurfaceTexture 可用；
- `onSurfaceTextureSizeChanged(st, width, height)`：尺寸变化；
- `onSurfaceTextureDestroyed(st)`：即将销毁，返回 `true` 表示允许 TextureView 释放它，返回 `false` 表示调用方继续持有；
- `onSurfaceTextureUpdated(st)`：有新帧更新到 TextureView。

### 7.3 自建 `SurfaceTexture`

如果需要完整控制 GL 纹理消费流程，推荐在 GL 线程创建外部纹理和 `SurfaceTexture`，再将其包装为解码器输出 `Surface`。这样纹理、SurfaceTexture 和 EGL context 的所有权关系清晰，适合视频滤镜和合成。

## 8. 生命周期和资源释放

播放时资源的依赖关系大致是：

```text
EGLContext
  └─ GL texture / Shader / Program
       └─ SurfaceTexture
            └─ Surface
                 └─ MediaCodec output
```

停止时建议从上游到下游协调停止，但释放资源要遵守“先停止使用者，再释放被使用对象”的原则：

```text
1. 停止向 GL 线程投递新的绘制任务
2. 请求 MediaCodec 解码循环退出
3. 等待 MediaCodec 完成 stop/release
4. release Surface
5. release SurfaceTexture
6. 在 EGL context current 时删除 texture / program
7. release EGLSurface
8. release EGLContext / EGLDisplay
```

实际项目中也可以把第 4～8 步集中放在 GL 线程完成，但必须保证解码器不再向 `Surface` 输出。

### 常见生命周期错误

#### 过早释放 `Surface`

解码线程仍在 `releaseOutputBuffer(..., true)` 时释放 `Surface`，可能导致 `IllegalStateException`、黑屏或设备相关的 native 错误。

#### 只设置停止标志、不等待线程退出

`requestStop()` 往往只是异步通知。若 Activity 随后销毁 `SurfaceTexture`，后台解码线程可能仍在写入旧对象。应提供完成回调、`join()` 或明确的等待协议。

#### 在错误线程调用 `updateTexImage()`

`updateTexImage()` 依赖纹理所属的 GL context。不要在任意的 `OnFrameAvailableListener` 回调线程中直接调用，除非该线程明确拥有正确的 EGL context。

#### EGL context 不 current 就创建或使用 GL 对象

Shader、纹理和 FBO 都需要正确的 current context。多线程使用时应使用共享 context，并明确每个对象的线程所有权。

#### Activity 静态字段持有 View

为了跨配置变化保留解码器而把 `TextureView` 或 Activity 放入静态对象，容易泄漏旧 Activity。应只保留播放器状态和底层图形资源，并在新 Activity 创建后重新 attach View。

## 9. 旋转、暂停和恢复

配置变化时有三种策略：

### 策略 A：停止并重建

最简单。销毁旧解码器和 Surface，重建 Activity 后重新播放。实现稳定，但会有停顿，播放位置也需要保存和恢复。

### 策略 B：保留解码器和 SurfaceTexture

旋转时保留播放线程、`SurfaceTexture` 和 `MediaCodec`，新 Activity 只重新绑定显示对象。这个方案可以连续播放，但要求严格管理：

- 旧 View 销毁时返回 `false`；
- 新 View 必须重新接收同一个 `SurfaceTexture`；
- 播放线程不能在切换期间释放 `Surface`；
- 结束页面时必须等待播放线程完成。

本仓库的 [`DoubleDecodeActivity.java`](../app/src/main/java/com/android/grafika/DoubleDecodeActivity.java) 演示了这种思路。

### 策略 C：只保留播放位置，重新创建解码器

适合普通 App。生命周期更简单，后台时可释放硬件解码器，回到前台时从保存的时间位置 seek。多数业务场景下，这比跨 Activity 保留 `SurfaceTexture` 更容易维护。

此外，不要只用 `isFinishing()` 判断是否应该停止视频。按 Home、锁屏或切到其他页面时 Activity 可能只是进入后台，通常还需要结合 `onStart()`/`onStop()`、`isChangingConfigurations()` 和产品需求设计暂停策略。

## 10. 性能优化

### 10.1 避免 YUV/RGB 读回 CPU

最重要的优化是使用 Surface 输出模式：

```java
decoder.configure(format, decoderSurface, null, 0);
```

不要把每帧 YUV 读到 Java/Kotlin，再转换成 RGB 后上传纹理。这样会产生大量内存带宽、CPU 和 GC 压力。

### 10.2 控制分辨率和帧率

如果显示区域只有 720p，却解码 4K 视频，解码和纹理采样成本都会增加。可以根据窗口尺寸、设备能力和实际观看距离选择合适的视频源。

### 10.3 合理驱动绘制

不要在有新帧时无节制地高速循环绘制。可以用 `OnFrameAvailableListener` 配合 `Choreographer`，或以 VSYNC 为节奏；同时要防止通知堆积。

### 10.4 减少状态切换

每帧尽量复用：

- `MediaCodec.BufferInfo`；
- 16 个元素的纹理矩阵；
- 顶点和纹理坐标缓冲区；
- Shader program 和纹理对象。

避免在每帧创建大量 Java 数组或 ByteBuffer。项目中的 [`FullFrameRect.java`](../app/src/main/java/com/android/grafika/gles/FullFrameRect.java) 和 [`Texture2dProgram.java`](../app/src/main/java/com/android/grafika/gles/Texture2dProgram.java) 就是把绘制对象长期复用。

### 10.5 只在需要时使用滤镜

外部纹理采样本身通常很高效，但复杂卷积、多个 FBO、过高精度和多次全屏 pass 会显著增加片元开销。滤镜可以按照设备 GLES 能力、目标分辨率和实际效果要求选择。

### 10.6 用工具验证瓶颈

不要只凭代码感觉判断性能。可以观察：

- 帧率和掉帧数；
- GL 线程 CPU 占用；
- 解码线程 CPU 占用；
- `eglSwapBuffers()` 是否阻塞；
- MediaCodec 输入/输出队列是否积压；
- Perfetto 中的线程调度、SurfaceFlinger 和 GPU 轨迹。

## 11. 常见问题排查

### 黑屏

按以下顺序检查：

1. `MediaExtractor` 是否找到了视频轨道；
2. `MediaCodec.configure()` 是否使用了完整的 `MediaFormat`；
3. `Surface` 创建时是否仍然有效；
4. 是否调用了 `decoder.start()`；
5. 是否真正送入了压缩样本和 EOS；
6. 是否检测并处理了 `INFO_OUTPUT_FORMAT_CHANGED`；
7. Surface 输出时是否调用了 `releaseOutputBuffer(index, true)`；
8. GL 端是否调用了 `updateTexImage()`；
9. Shader 是否使用 `samplerExternalOES` 和扩展声明；
10. 当前 EGL context 是否正确，`glGetError()` 和 Shader 日志是否有错误；
11. `uTexMatrix` 是否正确传入，viewport 是否为非零尺寸。

### 画面上下颠倒或旋转错误

不要直接翻转顶点坐标作为唯一修复方式。先调用：

```java
surfaceTexture.getTransformMatrix(textureMatrix);
```

再把矩阵传到顶点 Shader。视频文件的旋转 metadata 还可能需要和 `SurfaceTexture` 矩阵、View 宽高比矩阵合并。

### 画面变形

编码宽高不一定等于屏幕显示区域的宽高。根据视频有效宽高和目标 viewport 计算 letterbox 或 pillarbox 区域，使用等比缩放；不要简单把全屏矩形强行拉伸到任意窗口。

### `GL_INVALID_OPERATION`

重点检查：

- 是否在没有 current context 时调用 GL；
- 是否把 `GL_TEXTURE_EXTERNAL_OES` 当作 `GL_TEXTURE_2D`；
- 是否在错误 program 上设置 uniform；
- attribute/uniform location 是否为 `-1`；
- 顶点 stride、Buffer position 和 limit 是否正确。

### 停止时崩溃

重点检查释放顺序和线程同步：

- 是否在解码线程退出前释放 `Surface`；
- 是否在 GL 线程以外销毁 GL 对象；
- 是否把“发送停止消息”误认为“已经停止完成”；
- 是否允许停止消息和下一帧绘制消息乱序或重复执行。

## 12. 与本仓库代码的对应关系

本仓库的相关示例可以按以下方式理解：

| 文件 | 作用 |
|---|---|
| [`MoviePlayer.java`](../app/src/main/java/com/android/grafika/MoviePlayer.java) | `MediaExtractor + MediaCodec + Surface` 的视频解码播放骨架 |
| [`DoubleDecodeActivity.java`](../app/src/main/java/com/android/grafika/DoubleDecodeActivity.java) | 两路视频解码到两个 `TextureView`，重点演示 SurfaceTexture 生命周期 |
| [`TextureViewGLActivity.java`](../app/src/main/java/com/android/grafika/TextureViewGLActivity.java) | `TextureView + SurfaceTexture + EGL + GLES` 的绘制基础，但示例绘制的是动画而不是视频 |
| [`Texture2dProgram.java`](../app/src/main/java/com/android/grafika/gles/Texture2dProgram.java) | 普通纹理和 `GL_TEXTURE_EXTERNAL_OES` 的 Shader 封装 |
| [`FullFrameRect.java`](../app/src/main/java/com/android/grafika/gles/FullFrameRect.java) | 用全屏矩形绘制外部纹理 |
| [`EglCore.java`](../app/src/main/java/com/android/grafika/gles/EglCore.java) | EGL display、config、context 和 surface 封装 |
| [`WindowSurface.java`](../app/src/main/java/com/android/grafika/gles/WindowSurface.java) | Android `Surface`/`SurfaceTexture` 到 EGL window surface 的封装 |
| [`TextureMovieEncoder2.java`](../app/src/main/java/com/android/grafika/TextureMovieEncoder2.java) | 视频编码输出排空线程，不是视频解码播放类 |
| [`TextureMovieEncoder.java`](../app/src/main/java/com/android/grafika/TextureMovieEncoder.java) | 把外部纹理绘制到编码器输入 Surface 的编码示例，不是解码器 |

特别注意：`TextureMovieEncoder2` 中的 `MediaCodec` 方向是“编码”，它接收外部 GL 渲染结果并写入 MP4；视频播放的方向是“解码”，它从 `MediaExtractor` 读取压缩样本，再把输出交给 `Surface`。两者虽然都使用 `Surface`、EGL 和 `MediaCodec`，但数据方向完全相反。

## 13. 推荐的实现清单

实现一个基于 OpenGL ES 的视频播放器时，可以逐项确认：

- [ ] 明确是否真的需要 GL 处理；普通播放优先考虑直接输出到 `SurfaceView`；
- [ ] 在 GL 线程创建 EGL context 和外部纹理；
- [ ] 使用 `SurfaceTexture(textureId)`，再创建解码器输出 `Surface`；
- [ ] 传入 extractor 返回的完整 `MediaFormat`；
- [ ] 解码循环正确处理输入 EOS、输出 EOS 和 `INFO_*` 状态；
- [ ] GL 片元 Shader 使用 `samplerExternalOES`；
- [ ] 每帧在正确的 GL 线程调用 `updateTexImage()`；
- [ ] 使用 `getTransformMatrix()` 处理方向和纹理坐标；
- [ ] 统一 PTS 的微秒/纳秒单位，并建立单调时钟基准；
- [ ] 根据视频和窗口宽高比计算显示矩阵；
- [ ] 停止时等待解码线程退出，再释放 SurfaceTexture 和 EGL 资源；
- [ ] 避免静态对象持有 Activity 或 View；
- [ ] 使用 Perfetto、GPU profiler 和日志验证实际瓶颈。

如果只是播放本地视频，没有滤镜、缩放合成、画面录制或自定义渲染需求，直接使用 Media3/ExoPlayer 或 `MediaCodec` 输出到 `SurfaceView` 通常更合适。只有当应用确实需要对视频帧进行 OpenGL ES 处理时，才引入 `SurfaceTexture + GL_TEXTURE_EXTERNAL_OES` 这条更复杂的链路。
