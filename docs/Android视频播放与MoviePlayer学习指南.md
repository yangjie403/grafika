# Android 视频播放与 MoviePlayer 学习指南

本文围绕项目中的 [`MoviePlayer`](../app/src/main/java/com/android/grafika/MoviePlayer.java) 介绍 Android 视频播放的原理、流程、核心 API、参数含义、线程模型、时间戳同步、`Surface` 输出、循环播放和生命周期管理。

本文面向希望理解底层播放链路的开发者。项目中的 `MoviePlayer` 是学习型实现：它只处理视频轨道，使用同步式 `MediaCodec` 解码，并把结果输出到 `Surface`；它不处理音频、字幕、网络缓冲、DRM、播放列表和完整错误恢复。

> 开发真实产品时通常应优先评估 Jetpack Media3/ExoPlayer。`MoviePlayer` 更适合学习播放器底层需要解决的问题。

---

## 1. 视频播放的整体架构

一个最基本的视频播放链路如下：

```text
视频文件
    |
    | MediaExtractor：解封装容器，读取视频轨道
    v
压缩视频样本，例如 H.264/HEVC
    |
    | MediaCodec：解码
    v
解码后的视频帧
    |
    | releaseOutputBuffer(index, true)
    v
SurfaceView / TextureView / 其他图形消费者
    |
    v
屏幕显示
```

如果文件同时包含音频，还需要另一条音频链路：

```text
MediaExtractor
      |
      +--> 视频轨道 --> MediaCodec 视频解码器 --> Surface
      |
      +--> 音频轨道 --> MediaCodec 音频解码器 --> AudioTrack
```

| 组件 | 主要职责 |
| --- | --- |
| `MediaExtractor` | 解析 MP4 等容器，选择轨道，读取压缩样本和时间戳 |
| `MediaFormat` | 描述轨道或编解码器的格式信息 |
| `MediaCodec` | 将压缩数据解码为视频帧或音频 PCM |
| `Surface` | 作为视频解码器的图形输出目标 |
| `SurfaceView` | 提供独立的 Surface 图层 |
| `TextureView` | 将 `SurfaceTexture` 包装到普通 View 层级中 |
| `AudioTrack` | 将 PCM 音频数据提交给音频系统播放 |
| Media3/ExoPlayer | 对上述底层能力进行更完整的播放器封装 |

必须区分以下概念：

- MP4 是容器格式，不等于视频编码格式；
- H.264 是视频编码格式，不等于 MP4 文件；
- `MediaExtractor` 负责解封装，不负责解码；
- `MediaCodec` 负责编码或解码，不负责解析 MP4 容器；
- `Surface` 和 `EGLSurface` 相关但不是同一个对象；
- `presentationTimeUs` 是媒体时间轴上的时间戳，不是 Java 方法执行时间。

---

## 2. MP4、轨道和压缩样本

一个 MP4 文件通常包含容器元数据以及一个或多个轨道：

```text
MP4 容器
  |
  +-- 视频轨道
  |     +-- MIME：video/avc、video/hevc ...
  |     +-- 宽度、高度、帧率、时长
  |     +-- CSD：SPS/PPS 等解码初始化数据
  |     +-- 带 PTS 的压缩视频样本
  |
  +-- 音频轨道
        +-- MIME：audio/mp4a-latm 等
        +-- 采样率、声道数
        +-- CSD：AudioSpecificConfig 等初始化数据
        +-- 带 PTS 的压缩音频样本
```

“样本”不是解码后的 RGB 图像，而通常是压缩码流中的一段数据，例如 H.264 的一个或多个 NAL 单元。

### 2.1 MoviePlayer 的职责边界

`MoviePlayer` 负责：

- 打开媒体文件；
- 找到第一条视频轨道；
- 读取视频宽高；
- 创建和配置视频解码器；
- 向解码器输入压缩样本；
- 取出解码器输出；
- 将输出帧提交到 `Surface`；
- 处理输入 EOS 和输出 EOS；
- 支持循环播放；
- 响应停止请求并释放资源。

它不负责：

- 创建 Activity 界面；
- 创建 `SurfaceView` 或 `TextureView`；
- 处理播放按钮和文件选择；
- 播放音频；
- 根据 PTS 控制播放速度；
- 调整 View 的宽高比；
- 实现拖动、暂停、快进、字幕或 DRM。

项目中的职责分工：

| 类 | 职责 |
| --- | --- |
| [`MoviePlayer`](../app/src/main/java/com/android/grafika/MoviePlayer.java) | 视频解封装、解码和 Surface 输出 |
| [`PlayMovieActivity`](../app/src/main/java/com/android/grafika/PlayMovieActivity.java) | TextureView、文件列表、生命周期和播放控制 |
| [`PlayMovieSurfaceActivity`](../app/src/main/java/com/android/grafika/PlayMovieSurfaceActivity.java) | SurfaceView、SurfaceHolder 和播放控制 |
| [`SpeedControlCallback`](../app/src/main/java/com/android/grafika/SpeedControlCallback.java) | 根据 PTS 控制视频帧提交节奏 |
| `AspectFrameLayout` | 调整 SurfaceView 外层布局以保持宽高比 |

---

## 3. MediaExtractor：读取媒体容器

`MediaExtractor` 可以理解为 demuxer（解复用器）。它读取 MP4、WebM 等容器，把视频、音频等轨道暴露给应用。

### 3.1 设置数据源

播放应用私有文件时：

```java
MediaExtractor extractor = new MediaExtractor();
extractor.setDataSource(file.getAbsolutePath());
```

其他常见形式：

```java
// ContentResolver 返回的 Uri。
extractor.setDataSource(context, uri, null);

// FileDescriptor；ParcelFileDescriptor 的生命周期由调用方负责。
extractor.setDataSource(fileDescriptor);
```

使用 `Uri` 时，访问能力取决于 `ContentResolver` 返回的权限。使用 FileDescriptor 时，文件描述符的关闭职责仍由调用方负责。

### 3.2 枚举和选择轨道

```java
int trackCount = extractor.getTrackCount();
for (int i = 0; i < trackCount; i++) {
    MediaFormat format = extractor.getTrackFormat(i);
    String mime = format.getString(MediaFormat.KEY_MIME);
    Log.d("Player", "track=" + i + ", mime=" + mime + ", format=" + format);
}
```

`MoviePlayer` 通过 MIME 前缀查找第一条视频轨道：

```java
private static int selectVideoTrack(MediaExtractor extractor) {
    for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("video/")) {
            return i;
        }
    }
    return -1;
}
```

找到后必须调用：

```java
extractor.selectTrack(videoTrack);
```

如果不调用 `selectTrack()`，后续的 `readSampleData()` 不会按照预期读取选中的轨道。

### 3.3 常见 MediaFormat 参数

| Key | 含义 | 常见单位/示例 |
| --- | --- | --- |
| `KEY_MIME` | 编码 MIME 类型 | `video/avc`、`video/hevc` |
| `KEY_WIDTH` | 视频编码宽度 | 像素 |
| `KEY_HEIGHT` | 视频编码高度 | 像素 |
| `KEY_DURATION` | 轨道时长 | 微秒 |
| `KEY_FRAME_RATE` | 标称帧率 | FPS |
| `KEY_BIT_RATE` | 码率 | bit/s |
| `KEY_ROTATION` | 旋转角度 | 0/90/180/270 |
| `csd-0` | codec-specific data 第一段 | `ByteBuffer` |
| `csd-1` | codec-specific data 第二段 | `ByteBuffer` |
| `KEY_COLOR_FORMAT` | 颜色格式 | 编解码器相关整数 |
| `KEY_PROFILE` | 编码 profile | 编解码器相关整数 |
| `KEY_LEVEL` | 编码 level | 编解码器相关整数 |

可选键需要先判断是否存在：

```java
if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
    int frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
}
```

配置解码器时，优先使用 `getTrackFormat()` 返回的完整对象，而不是只手动创建 MIME 和宽高。完整格式可能包含 H.264 的 SPS/PPS、HEVC 的 VPS/SPS/PPS 和其他初始化参数。

### 3.4 读取压缩样本

```java
int sampleSize = extractor.readSampleData(inputBuffer, 0);
```

参数含义：

| 参数 | 含义 |
| --- | --- |
| `inputBuffer` | 由 `MediaCodec` 提供的输入 ByteBuffer |
| `offset` | 样本写入输入 buffer 的起始偏移，通常为 0 |

返回值：

- `>= 0`：成功读取的压缩样本字节数；
- `< 0`：当前轨道没有更多样本。

读取成功后还要读取时间戳和轨道索引：

```java
long presentationTimeUs = extractor.getSampleTime();
int sampleTrack = extractor.getSampleTrackIndex();
extractor.advance();
```

忘记调用 `advance()` 会反复提交同一个样本。

### 3.5 seekTo() 参数含义

```java
extractor.seekTo(
        targetTimeUs,
        MediaExtractor.SEEK_TO_CLOSEST_SYNC);
```

| 参数 | 含义 |
| --- | --- |
| `timeUs` | 目标时间，单位微秒 |
| `mode` | 目标附近同步帧的选择策略 |

常见模式：

- `SEEK_TO_PREVIOUS_SYNC`：目标之前或等于目标的同步帧；
- `SEEK_TO_NEXT_SYNC`：目标之后或等于目标的同步帧；
- `SEEK_TO_CLOSEST_SYNC`：距离目标最近的同步帧。

由于非关键帧通常依赖参考帧，任意位置 seek 往往需要先跳到关键帧，再解码到目标位置。

### 3.6 释放 Extractor

```java
MediaExtractor extractor = null;
try {
    extractor = new MediaExtractor();
    extractor.setDataSource(path);
    // 读取轨道和样本。
} finally {
    if (extractor != null) {
        extractor.release();
    }
}
```

---

## 4. MediaCodec：视频解码器

`MediaCodec` 是 Android 的底层编解码 API。播放视频时，它作为 decoder 使用：

```java
String mime = format.getString(MediaFormat.KEY_MIME);
MediaCodec decoder = MediaCodec.createDecoderByType(mime);
```

常见视频 MIME 类型：

```text
video/avc     -> H.264/AVC
video/hevc    -> H.265/HEVC
video/mp4v-es -> MPEG-4 Visual
```

不是每台设备都支持所有编码格式、profile、level、分辨率和帧率。创建解码器失败时，应向调用方报告设备不支持该媒体格式。

### 4.1 configure() 参数

`MoviePlayer` 使用：

```java
decoder.configure(format, outputSurface, null, 0);
```

参数含义：

| 参数 | 含义 |
| --- | --- |
| `format` | 解码器输入格式，通常来自 `MediaExtractor.getTrackFormat()` |
| `surface` | 解码输出目标；传入后使用 Surface 输出模式 |
| `crypto` | DRM 加密内容解密对象；普通文件传 `null` |
| `flags` | 配置标志；解码器通常传 0，编码器才传 `CONFIGURE_FLAG_ENCODE` |

配置完成后：

```java
decoder.start();
```

`start()` 之后才可以调用 `dequeueInputBuffer()` 和 `dequeueOutputBuffer()`。

### 4.2 输入队列 API

项目使用旧版 buffer 数组 API：

```java
ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
```

当 `inputBufIndex >= 0` 时，说明有输入 buffer 可用：

```java
ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
int chunkSize = extractor.readSampleData(inputBuf, 0);
long presentationTimeUs = extractor.getSampleTime();

decoder.queueInputBuffer(
        inputBufIndex,
        0,
        chunkSize,
        presentationTimeUs,
        0);
```

`queueInputBuffer()` 参数含义：

| 参数 | 含义 |
| --- | --- |
| `index` | `dequeueInputBuffer()` 返回的输入 buffer 索引 |
| `offset` | 有效数据在 buffer 中的起始偏移 |
| `size` | 有效压缩数据的字节数 |
| `presentationTimeUs` | 输入样本展示时间戳，单位微秒 |
| `flags` | 输入样本标志，普通样本通常为 0 |

现代 API 可以按索引获取 buffer：

```java
ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
```

### 4.3 输入 EOS

当 `readSampleData()` 返回负数时，表示没有更多输入。不能立即停止解码器，因为输入队列中可能还有已经提交但尚未解码完成的样本。

应提交一个空输入 buffer 并设置 EOS：

```java
decoder.queueInputBuffer(
        inputBufIndex,
        0,
        0,
        0L,
        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
```

然后继续取输出，直到输出端也报告 EOS：

```java
boolean inputDone = false;
boolean outputDone = false;
```

- `inputDone`：已经向 decoder 发送输入 EOS；
- `outputDone`：已经从 decoder 收到输出 EOS。

只有 `outputDone` 才能退出解码循环。

### 4.4 输出队列和 BufferInfo

```java
MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
```

第二个参数是超时时间，单位微秒：

```java
final int TIMEOUT_USEC = 10_000; // 10 毫秒
```

常见返回值：

| 返回值 | 含义 |
| --- | --- |
| `>= 0` | 有效输出 buffer 索引 |
| `INFO_TRY_AGAIN_LATER` | 当前暂时没有输出 |
| `INFO_OUTPUT_FORMAT_CHANGED` | 输出格式发生变化 |
| `INFO_OUTPUT_BUFFERS_CHANGED` | 旧版 API 的输出 buffer 数组发生变化 |

`BufferInfo` 常用字段：

| 字段 | 含义 |
| --- | --- |
| `offset` | 输出有效数据在 ByteBuffer 中的起始偏移；Surface 输出时通常不需要读取 |
| `size` | 输出数据大小；Surface 输出时可用于判断空控制 buffer |
| `presentationTimeUs` | 输出帧的展示时间戳，单位微秒 |
| `flags` | EOS、关键帧等标志 |

### 4.5 releaseOutputBuffer(index, render)

这是 Surface 输出模式中最关键的 API：

```java
boolean render = bufferInfo.size != 0;
decoder.releaseOutputBuffer(outputIndex, render);
```

| 参数 | 含义 |
| --- | --- |
| `index` | `dequeueOutputBuffer()` 返回的输出 buffer 索引 |
| `render` | 是否将解码帧提交到 `configure()` 时传入的 Surface |

当 `render == true` 时，解码器会把该输出帧交给 Surface。调用返回不等于画面已经出现在物理屏幕上，真正显示还涉及 BufferQueue、SurfaceFlinger、VSYNC 和屏幕刷新。

如果输出目标不是 Surface，而是 ByteBuffer，通常使用：

```java
ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
decoder.releaseOutputBuffer(outputIndex, false);
```

实际读取输出 buffer 时，应在释放之前获取并处理它。

### 4.6 flush、stop 和 release

`flush()` 清除 codec 内部已经排队的输入、输出和状态，用于 seek 或循环后重新开始解码；flush 之后通常需要重新提交输入数据。

`stop()` 停止 codec，应在 `release()` 前调用。`release()` 释放 native codec 资源和硬件解码器占用。

---

## 5. MoviePlayer 的核心工作流程

### 5.1 构造阶段：只探测视频信息

构造函数接收：

```java
public MoviePlayer(
        File sourceFile,
        Surface outputSurface,
        FrameCallback frameCallback) throws IOException
```

| 参数 | 含义 |
| --- | --- |
| `sourceFile` | 要播放的媒体文件 |
| `outputSurface` | 解码后视频帧的输出目标 |
| `frameCallback` | 帧提交前后回调，可为 `null` |

构造函数内部临时创建 `MediaExtractor`，只用于找到视频轨道并读取宽高：

```text
new MoviePlayer(...)
      |
      +--> new MediaExtractor()
      +--> setDataSource(sourceFile)
      +--> selectTrack()
      +--> getTrackFormat()
      +--> 读取 KEY_WIDTH / KEY_HEIGHT
      +--> release()
```

真正播放时，`play()` 会重新创建 Extractor 和 Decoder。构造阶段不会开始解码。

这样 `PlayMovieActivity` 可以在真正播放前调整 `TextureView` 的显示矩阵：

```java
MoviePlayer player = new MoviePlayer(file, surface, callback);
adjustAspectRatio(player.getVideoWidth(), player.getVideoHeight());
```

### 5.2 play() 阶段：真正打开、解码和释放

`play()` 是阻塞方法，核心流程如下：

```text
play()
  |
  +--> 检查 sourceFile.canRead()
  +--> 创建 MediaExtractor
  +--> setDataSource()
  +--> 查找视频轨道
  +--> selectTrack()
  +--> 获取视频 MediaFormat
  +--> 根据 MIME 创建 MediaCodec Decoder
  +--> decoder.configure(format, outputSurface, null, 0)
  +--> decoder.start()
  +--> doExtract()
  +--> finally: decoder.stop()/release()
  +--> finally: extractor.release()
```

源码中的关键配置：

```java
String mime = format.getString(MediaFormat.KEY_MIME);
decoder = MediaCodec.createDecoderByType(mime);
decoder.configure(format, mOutputSurface, null, 0);
decoder.start();
```

这里的 `format` 应使用 `MediaExtractor.getTrackFormat()` 返回的完整对象，不要只手动创建 MIME 和宽高。

### 5.3 doExtract()：输入和输出同时推进

`doExtract()` 同时维护两个异步队列：

```text
                +------------------------+
压缩样本 -----> | MediaCodec 输入队列     |
                +------------------------+
                           |
                           v
                     硬件/软件解码器
                           |
                           v
                +------------------------+
解码帧 <-----   | MediaCodec 输出队列     |
                +------------------------+
```

每轮循环做两件事：

1. 如果输入还没有 EOS，尝试提交一个压缩样本；
2. 尝试取出一个解码输出缓冲区。

不能简单地“提交一个样本后等待一个输出”，因为解码器可能需要参考帧、进行帧重排序，或者在输入 EOS 后仍有缓存输出。

### 5.4 doExtract() 学习版伪代码

```java
private void doExtract(
        MediaExtractor extractor,
        MediaCodec decoder,
        FrameCallback callback) {

    final int TIMEOUT_USEC = 10_000;
    ByteBuffer[] inputBuffers = decoder.getInputBuffers();
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    boolean inputDone = false;
    boolean outputDone = false;

    while (!outputDone) {
        if (!inputDone) {
            int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputIndex];
                int sampleSize = extractor.readSampleData(inputBuffer, 0);

                if (sampleSize < 0) {
                    decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    long ptsUs = extractor.getSampleTime();
                    decoder.queueInputBuffer(
                            inputIndex, 0, sampleSize, ptsUs, 0);
                    extractor.advance();
                }
            }
        }

        int outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            continue;
        }
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            continue;
        }
        if (outputIndex < 0) {
            continue;
        }

        boolean render = info.size > 0;
        boolean eos = (info.flags
                & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

        if (render && callback != null) {
            callback.preRender(info.presentationTimeUs);
        }
        decoder.releaseOutputBuffer(outputIndex, render);
        if (render && callback != null) {
            callback.postRender();
        }

        if (eos) {
            outputDone = true;
        }
    }
}
```

`inputDone` 和 `outputDone` 不相等：发送输入 EOS 后，解码器仍可能需要输出此前已经排队的帧。

`MoviePlayer` 还复用一个 `BufferInfo`：

```java
private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
```

视频播放是高频循环，复用对象有助于减少每帧分配和 GC 压力。

---

## 6. Surface、SurfaceView 和 TextureView

### 6.1 Surface 是什么

`Surface` 可以理解为供图形生产者提交缓冲区、供图形消费者读取的接口。视频解码器作为生产者，把解码帧提交到 Surface；SurfaceFlinger、TextureView 或其他消费者再使用这些缓冲区。

它不是 Java Bitmap，也不是可以直接调用 `drawBitmap()` 的 Canvas。

### 6.2 Surface 和 EGLSurface 的区别

```text
Android Surface
  用于连接图形生产者和消费者

EGLSurface
  EGL 对 Android Surface 或 Pbuffer 的包装
  作为 OpenGL ES 的绘制目标
```

例如 `RecordFBOActivity` 中：

```text
MediaCodec.createInputSurface()
        |
        v
Android Surface
        |
        | new WindowSurface(EglCore, surface, true)
        v
EGLSurface
        |
        v
OpenGL ES 绘制
```

`MoviePlayer` 不需要自己把输出 Surface 包装成 EGLSurface，因为 MediaCodec 解码器会直接向传入的 Android `Surface` 输出。

### 6.3 SurfaceView 播放

`SurfaceView` 提供独立的 Surface 图层。只有 `surfaceCreated()` 回调之后，才能把 `holder.getSurface()` 传给解码器。Surface 销毁之前必须先停止并等待播放器线程退出。

```java
SurfaceView surfaceView = findViewById(R.id.video_surface);
surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        // 现在可以把 surface 传给 MediaCodec.configure()。
    }

    @Override
    public void surfaceChanged(
            SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 必须先停止播放器，再允许后台线程继续使用 Surface。
    }
});
```

### 6.4 TextureView 播放

`TextureView` 可以像普通 View 一样参与窗口布局和变换。典型流程是：

```text
TextureView
    |
    +--> SurfaceTexture
            |
            +--> new Surface(surfaceTexture)
                    |
                    +--> MoviePlayer
                            |
                            +--> MediaCodec.configure(..., surface, ...)
```

示例：

```java
TextureView textureView = findViewById(R.id.video_texture);
textureView.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture surfaceTexture,
                    int width,
                    int height) {
                Surface surface = new Surface(surfaceTexture);
                // 将 surface 传给 MoviePlayer。
            }

            @Override
            public boolean onSurfaceTextureDestroyed(
                    SurfaceTexture surfaceTexture) {
                // 停止播放器后返回 true，允许 TextureView 释放它。
                return true;
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture surfaceTexture,
                    int width,
                    int height) {
            }

            @Override
            public void onSurfaceTextureUpdated(
                    SurfaceTexture surfaceTexture) {
            }
        });
```

由调用方创建的 `Surface` 包装对象在播放结束后应释放：

```java
Surface surface = new Surface(surfaceTexture);
try {
    // 使用 surface 播放。
} finally {
    surface.release();
}
```

---

## 7. 线程模型与生命周期

`MoviePlayer.play()` 包含文件读取、Extractor 操作、Codec 轮询和可能的等待，不能放在 UI 线程执行：

```java
// 错误示例：会阻塞主线程。
player.play();
```

`PlayTask` 将播放放到后台线程：

```java
MoviePlayer.PlayTask playTask = new MoviePlayer.PlayTask(
        player,
        new MoviePlayer.PlayerFeedback() {
            @Override
            public void playbackStopped() {
                // 更新 UI。
            }
        });

playTask.execute();
```

### 7.1 Handler 的作用

`PlayTask` 构造时创建 `LocalHandler`。如果构造发生在 UI 线程，Handler 就绑定 UI 线程的 Looper。播放线程结束后发送消息：

```java
mLocalHandler.sendMessage(
        mLocalHandler.obtainMessage(MSG_PLAY_STOPPED, mFeedback));
```

这样 `playbackStopped()` 可以在 UI 线程更新按钮和 View。

### 7.2 requestStop() 与 waitForStop()

`requestStop()` 是异步的，只设置一个跨线程可见的 `volatile` 标志：

```java
public void requestStop() {
    mIsStopRequested = true;
}
```

播放循环下一次检查到标志后才会退出：

```java
while (!outputDone) {
    if (mIsStopRequested) {
        return;
    }
    // 继续输入/输出轮询。
}
```

如果要确认播放线程已经停止：

```java
playTask.requestStop();
playTask.waitForStop();
```

`waitForStop()` 不能从播放线程自己调用，否则会等待自己结束，形成死锁。

### 7.3 正确的 Surface 生命周期顺序

```text
Surface 创建
    -> 创建/配置/启动 decoder
    -> 解码并输出
    -> 请求停止
    -> 等待播放线程退出
    -> stop/release decoder
    -> Surface 销毁
```

不能让后台线程在 `surfaceDestroyed()` 之后继续调用：

```java
decoder.releaseOutputBuffer(outputIndex, true);
```

否则可能出现黑屏、异常、native 崩溃、BufferQueue 错误或线程阻塞。

---

## 8. 播放速度与 PTS 同步

### 8.1 PTS 是什么

PTS 是 presentation timestamp，即展示时间戳。Android `MediaCodec` 和 `MediaExtractor` 中视频 PTS 通常以微秒为单位：

```text
1 秒       = 1,000,000 微秒
16.67 毫秒 = 16,667 微秒左右
```

一个 30 FPS 视频可能有：

```text
帧 0:       0 us
帧 1:   33333 us
帧 2:   66666 us
帧 3:   99999 us
```

PTS 描述媒体时间轴，不保证方法调用的现实时间等于 PTS。播放器需要建立两个时间轴的映射：

```text
视频时间轴：PTS 0us ---- 33333us ---- 66666us ----

现实时间轴：now 0ms ---- 33ms ------ 66ms ------
```

### 8.2 为什么要控制提交节奏

如果解码器很快，而应用每拿到一帧就立刻 `releaseOutputBuffer()`，可能以解码器最大速度播放：

```text
错误：dequeue output -> 立即 releaseOutputBuffer(true)

改进：dequeue output -> 按 PTS 等待 -> releaseOutputBuffer(true)
```

因此 `MoviePlayer` 提供 `FrameCallback`：

```java
public interface FrameCallback {
    void preRender(long presentationTimeUsec);
    void postRender();
    void loopReset();
}
```

调用顺序是：

```text
dequeueOutputBuffer()
        |
        v
preRender(ptsUs)       // 这里可以等待
        |
        v
releaseOutputBuffer(index, true)
        |
        v
postRender()
```

### 8.3 SpeedControlCallback 的算法

[`SpeedControlCallback`](../app/src/main/java/com/android/grafika/SpeedControlCallback.java) 使用 `System.nanoTime()` 作为现实时间基准：

```text
首帧：记录首帧 PTS 和当前单调时钟，不等待

后续帧：
  frameDelta = 当前 PTS - 上一帧 PTS
  desiredTime = 上一帧目标现实时间 + frameDelta
  当前时间早于 desiredTime 时 Thread.sleep()
  提交输出帧
```

核心逻辑：

```java
long frameDelta = presentationTimeUsec - mPrevPresentUsec;
long desiredUsec = mPrevMonoUsec + frameDelta;
long nowUsec = System.nanoTime() / 1000L;

while (nowUsec < desiredUsec - 100) {
    long sleepTimeUsec = desiredUsec - nowUsec;
    if (sleepTimeUsec > 500_000) {
        sleepTimeUsec = 500_000;
    }

    Thread.sleep(
            sleepTimeUsec / 1000,
            (int) (sleepTimeUsec % 1000) * 1000);

    nowUsec = System.nanoTime() / 1000L;
}
```

`System.currentTimeMillis()` 是墙上时钟，可能因为网络校时或用户修改时间而跳变；`System.nanoTime()` 更适合计算时间间隔。

### 8.4 PTS 异常和固定帧率

`SpeedControlCallback` 对异常时间戳做保护：

- PTS 倒退：将间隔限制为 0；
- PTS 相同：记录警告，允许继续播放；
- 间隔超过 10 秒：记录日志并把等待上限限制为 5 秒；
- 循环播放：重新建立上一轮和下一轮之间的时间关系。

`PlayMovieActivity` 还可以选择固定 60 FPS：

```java
SpeedControlCallback callback = new SpeedControlCallback();
callback.setFixedPlaybackRate(60);
```

内部间隔约为 `1,000,000 / 60 = 16,666` 微秒。此模式忽略文件中相邻帧 PTS 的真实差值，更适合测试。

---

## 9. 循环播放的工作原理

MoviePlayer 在输出 EOS 时判断循环开关：

```java
if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
    if (mLoop) {
        doLoop = true;
    } else {
        outputDone = true;
    }
}
```

循环时不能只把 Extractor seek 到开头，还需要清理解码器状态：

```java
extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
inputDone = false;
decoder.flush();
frameCallback.loopReset();
```

| 操作 | 作用 |
| --- | --- |
| `seekTo(0, ...)` | 让容器读取位置回到开头附近的同步帧 |
| `inputDone = false` | 允许下一轮重新向 decoder 输入样本 |
| `decoder.flush()` | 清除上一轮 EOS 和参考帧状态 |
| `loopReset()` | 通知帧速控制器重新建立时间基准 |

如果漏掉 `decoder.flush()`，解码器可能仍处于上一轮 EOS 状态；如果漏掉时间基准重置，下一轮 PTS 从 0 开始时可能导致等待时间计算出错。

---

## 10. PlayMovieActivity：TextureView 播放流程

`PlayMovieActivity` 的主要职责是 UI 和生命周期协调，真正的解封装和解码由 `MoviePlayer` 完成。

### 10.1 获取输出 Surface

```java
mTextureView = findViewById(R.id.movie_texture_view);
mTextureView.setSurfaceTextureListener(this);
```

只有收到 `onSurfaceTextureAvailable()` 后，才能创建输出 Surface：

```java
@Override
public void onSurfaceTextureAvailable(
        SurfaceTexture surfaceTexture,
        int width,
        int height) {
    Surface surface = new Surface(surfaceTexture);
    // 将 surface 传给 MoviePlayer。
}
```

### 10.2 创建并启动 MoviePlayer

```java
SpeedControlCallback callback = new SpeedControlCallback();
SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
Surface surface = new Surface(surfaceTexture);

MoviePlayer player = new MoviePlayer(
        new File(getFilesDir(), movieName),
        surface,
        callback);

adjustAspectRatio(player.getVideoWidth(), player.getVideoHeight());

MoviePlayer.PlayTask playTask = new MoviePlayer.PlayTask(
        player,
        new MoviePlayer.PlayerFeedback() {
            @Override
            public void playbackStopped() {
                // 在 UI 线程恢复按钮状态。
            }
        });

playTask.execute();
```

### 10.3 为什么要调整宽高比

视频编码尺寸和 View 尺寸通常不同。例如视频是 1920×1080，View 是 1080×1920。如果直接拉伸到整个 View，画面会变形。

`adjustAspectRatio()` 的流程是：

```text
原视频比例
      |
      v
计算 newWidth / newHeight
      |
      v
计算 xoff / yoff
      |
      v
Matrix.setScale() + postTranslate()
```

这只改变显示方式，不会改变解码器的输出分辨率。

### 10.4 停止流程

```java
if (mPlayTask != null) {
    mPlayTask.requestStop();
    mPlayTask.waitForStop();
}
```

顺序是：

```text
请求停止
    -> 后台线程退出 doExtract()
    -> MoviePlayer.play() finally 释放 decoder/extractor
    -> waitForStop() 返回
    -> 允许 SurfaceTexture 销毁
```

如果只调用 `requestStop()` 而不等待，Activity 可能已经销毁 Surface，而后台线程仍尝试提交帧。

---

## 11. PlayMovieSurfaceActivity：SurfaceView 播放流程

SurfaceView 版本的解码流程与 TextureView 版本相同，区别在于 Surface 来源和布局方式：

```java
mSurfaceView = findViewById(R.id.playMovie_surface);
mSurfaceView.getHolder().addCallback(this);
```

在 `surfaceCreated()` 中允许播放：

```java
@Override
public void surfaceCreated(SurfaceHolder holder) {
    mSurfaceHolderReady = true;
    updateControls();
}
```

点击播放时取得：

```java
Surface surface = mSurfaceView.getHolder().getSurface();
```

SurfaceView 通常适合连续播放，因为它拥有独立 Surface 图层；但要保持比例，需要调整外层 `AspectFrameLayout`，而不是简单依赖普通 View 的 `Matrix`。

---

## 12. 完整的最小视频解码示例

下面代码展示一个“只播放视频到 Surface”的核心结构。它省略了 Activity 布局、权限和 UI 状态，但包含 Extractor、Codec、EOS 和资源释放流程。

```java
public final class BasicVideoDecoder implements Runnable {
    private static final int TIMEOUT_USEC = 10_000;

    private final File inputFile;
    private final Surface outputSurface;

    public BasicVideoDecoder(File inputFile, Surface outputSurface) {
        this.inputFile = inputFile;
        this.outputSurface = outputSurface;
    }

    @Override
    public void run() {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try {
            extractor.setDataSource(inputFile.getAbsolutePath());

            int videoTrack = selectVideoTrack(extractor);
            if (videoTrack < 0) {
                throw new IllegalArgumentException("没有视频轨道");
            }

            extractor.selectTrack(videoTrack);
            MediaFormat format = extractor.getTrackFormat(videoTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, outputSurface, null, 0);
            decoder.start();

            decodeLoop(extractor, decoder);
        } catch (IOException e) {
            throw new RuntimeException("打开视频失败", e);
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            extractor.release();
        }
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private static void decodeLoop(
            MediaExtractor extractor,
            MediaCodec decoder) {
        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone) {
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = inputBuffers[inputIndex];
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long ptsUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(
                                inputIndex, 0, sampleSize, ptsUs, 0);
                        extractor.advance();
                    }
                }
            }

            int outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            boolean render = info.size > 0;
            boolean eos = (info.flags
                    & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            decoder.releaseOutputBuffer(outputIndex, render);

            if (eos) {
                outputDone = true;
            }
        }
    }
}
```

这个示例没有做速度控制，因此可能以解码器能达到的最大速度提交帧。加入 `SpeedControlCallback` 后，可以在 `releaseOutputBuffer()` 前按照 PTS 等待。

---

## 13. 常见问题与排查方式

### 13.1 configure() 失败

可能原因：

- MIME 类型没有对应解码器；
- 设备不支持该 profile/level；
- 视频分辨率超出硬件能力；
- 使用了不完整或错误的 `MediaFormat`；
- 输出 `Surface` 已经销毁或无效；
- codec 正在被其他线程使用。

排查时记录完整格式和 Surface 状态：

```java
Log.d(TAG, "mime=" + mime);
Log.d(TAG, "format=" + format);
Log.d(TAG, "surfaceValid=" + outputSurface.isValid());
```

### 13.2 黑屏但没有异常

检查：

- 是否在 `surfaceCreated()` 或 `onSurfaceTextureAvailable()` 之后创建播放器；
- `decoder.configure()` 是否传入了正确的 Surface；
- 是否调用了 `decoder.start()`；
- 是否持续向 decoder 输入样本；
- 是否调用了 `releaseOutputBuffer(outputIndex, true)`；
- 输入 EOS 后是否仍然排空输出；
- Activity 是否在播放开始后立即触发了 `onPause()`；
- Surface 是否被提前 `release()`。

### 13.3 播放速度过快

通常是没有根据 PTS 控制输出提交节奏。可以使用项目的 [`SpeedControlCallback`](../app/src/main/java/com/android/grafika/SpeedControlCallback.java)。

### 13.4 最后一帧没有显示

输入 EOS 不等于输出已经排空。正确流程是：

```text
发送输入 EOS
    -> 继续 dequeueOutputBuffer()
    -> 直到输出 buffer 带 BUFFER_FLAG_END_OF_STREAM
    -> 停止并释放 decoder
```

### 13.5 循环播放第二轮黑屏

检查是否同时完成：

```java
extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
decoder.flush();
inputDone = false;
frameCallback.loopReset();
```

### 13.6 Surface 销毁后崩溃

一般是播放器线程没有结束就让 Surface 消失。Activity 应该：

```java
player.requestStop();
playTask.waitForStop();
```

### 13.7 画面比例不正确

要区分三个尺寸：视频编码尺寸、Surface 缓冲区尺寸和 View 布局尺寸。保持比例可以通过调整外层布局、对 TextureView 使用 Matrix、在 OpenGL 渲染时使用投影矩阵，或者对视频做裁剪/留黑边实现。

---

## 14. 旧版 API 与现代 API

当前项目使用旧版 buffer 数组 API：

```java
ByteBuffer[] inputBuffers = decoder.getInputBuffers();
ByteBuffer inputBuffer = inputBuffers[inputIndex];
```

现代 Android API 更推荐按索引获取 buffer：

```java
ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
```

核心状态机没有改变：

```text
dequeueInputBuffer
    -> getInputBuffer
    -> queueInputBuffer

dequeueOutputBuffer
    -> getOutputBuffer（ByteBuffer 输出时）
    -> releaseOutputBuffer
```

`MediaCodec` 仍是底层 API，开发者需要自己处理输入输出状态、EOS、Surface 生命周期、音视频同步、错误恢复、线程和资源释放。

### 14.1 MediaCodec.Callback 异步模式

除了阻塞式 `dequeue...()`，也可以使用异步回调：

```java
codec.setCallback(new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(
            MediaCodec codec, int index) {
        // 读取 Extractor 样本并 queueInputBuffer。
    }

    @Override
    public void onOutputBufferAvailable(
            MediaCodec codec,
            int index,
            MediaCodec.BufferInfo info) {
        // releaseOutputBuffer(index, true)。
    }

    @Override
    public void onOutputFormatChanged(
            MediaCodec codec,
            MediaFormat format) {
    }

    @Override
    public void onError(
            MediaCodec codec,
            MediaCodec.CodecException e) {
    }
});
```

异步模式减少手写轮询，但线程归属和生命周期仍然需要明确。项目中的 `ScreenRecordActivity` 使用了异步 `MediaCodec.Callback`，可以作为对照阅读。

---

## 15. 什么时候使用 Media3/ExoPlayer

如果目标是开发真实产品播放器，通常应优先评估 Jetpack Media3 中的 ExoPlayer。它已经提供：

- 多种媒体源；
- 音视频同步；
- 缓冲和加载控制；
- seek；
- 播放列表；
- 字幕；
- 音轨/字幕轨切换；
- DRM；
- 网络流播放；
- 播放状态和错误回调；
- MediaSession 集成。

底层 `MoviePlayer` 仍然很有学习价值，因为它展示了 ExoPlayer 内部也需要解决的基本问题：

```text
媒体源
  -> 解封装
  -> 轨道选择
  -> 解码器配置
  -> 输入输出队列
  -> 时间戳调度
  -> Surface 输出
  -> 生命周期和错误处理
```

如果需要本地/网络播放、自动音视频同步、复杂格式、后台播放、通知栏控制或媒体会话，建议使用 Media3/ExoPlayer，而不是直接把 `MoviePlayer` 扩展成产品级播放器。

---

## 16. 推荐的源码阅读顺序

### 第一步：阅读 PlayMovieActivity

先理解界面如何获得 `Surface`：

```text
TextureView
  -> SurfaceTexture
  -> Surface
  -> MoviePlayer
```

重点看：`onSurfaceTextureAvailable()`、`clickPlayStop()`、`onPause()`、`playbackStopped()` 和 `adjustAspectRatio()`。

### 第二步：阅读 MoviePlayer.play()

重点看：`setDataSource()`、`selectTrack()`、`getTrackFormat()`、`createDecoderByType()`、`configure()`、`start()` 以及 finally 中的资源释放。

### 第三步：阅读 MoviePlayer.doExtract()

重点理解：

- `inputDone` 与 `outputDone` 的区别；
- `dequeueInputBuffer()`；
- `readSampleData()`；
- `queueInputBuffer()`；
- `dequeueOutputBuffer()` 的状态分支；
- `releaseOutputBuffer(index, true)`；
- 输出 EOS；
- 循环时 `seekTo()` + `flush()`。

### 第四步：阅读 SpeedControlCallback

重点理解媒体 PTS 和现实单调时钟的映射、`preRender()` 为什么可以阻塞、为什么使用 `nanoTime()` 以及循环时间轴重置。

### 第五步：对照 PlayMovieSurfaceActivity

理解 TextureView 和 SurfaceView 的输出目标差异，以及 Surface 生命周期如何影响播放器线程。

---

## 17. 一页总结

### 最小播放流程

```text
1. 创建 MediaExtractor
2. setDataSource()
3. 找到 video/* 轨道
4. selectTrack()
5. getTrackFormat()
6. createDecoderByType()
7. configure(format, surface, null, 0)
8. start()
9. 循环 dequeueInputBuffer / queueInputBuffer
10. 循环 dequeueOutputBuffer
11. releaseOutputBuffer(index, true)
12. 输入 EOS 后继续排空到输出 EOS
13. stop()
14. release()
```

### 最重要的四个原则

1. `MediaExtractor` 负责读取压缩样本，`MediaCodec` 负责解码；
2. `MediaCodec` 输出到 Surface 时，不需要把每帧像素读回 CPU；
3. 输入 EOS 后不能立即停止，必须等输出 EOS；
4. Surface 销毁前必须先停止并等待播放器线程结束。

### MoviePlayer 的核心数据流

```text
File
  -> MediaExtractor
  -> compressed sample + presentationTimeUs
  -> MediaCodec input queue
  -> decoded output buffer
  -> preRender(PTS)
  -> releaseOutputBuffer(index, true)
  -> Surface
```

### 相关源码

- [`MoviePlayer.java`](../app/src/main/java/com/android/grafika/MoviePlayer.java)
- [`SpeedControlCallback.java`](../app/src/main/java/com/android/grafika/SpeedControlCallback.java)
- [`PlayMovieActivity.java`](../app/src/main/java/com/android/grafika/PlayMovieActivity.java)
- [`PlayMovieSurfaceActivity.java`](../app/src/main/java/com/android/grafika/PlayMovieSurfaceActivity.java)
- [`MediaExtractor 与 MediaCodec 视频解码基础`](使用MediaExtractor和MediaCodec实现视频解码与播放.md)

官方 API 参考：

- [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)
- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [MediaFormat](https://developer.android.com/reference/android/media/MediaFormat)
- [Surface](https://developer.android.com/reference/android/view/Surface)
- [SurfaceView](https://developer.android.com/reference/android/view/SurfaceView)
- [TextureView](https://developer.android.com/reference/android/view/TextureView)
- [Media3 / ExoPlayer](https://developer.android.com/media/media3/exoplayer)
