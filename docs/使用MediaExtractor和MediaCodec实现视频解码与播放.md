# 使用 MediaExtractor 和 MediaCodec 实现视频解码与播放

本文介绍 Android 中使用 `MediaExtractor` 和 `MediaCodec` 播放视频的通用流程，并给出一个基于 Java、同步阻塞 API 的完整示例。

本文的实现思路与本项目的 `MoviePlayer` 基本一致：

- `MediaExtractor` 从 MP4 等容器中读取压缩视频样本；
- `MediaCodec` 把压缩样本解码成视频帧；
- `Surface` 作为解码器输出目标，接收并显示解码后的帧；
- 播放工作放在独立线程中，避免阻塞 Android 主线程；
- 输入和输出通过队列异步衔接，应用需要不断“喂输入”和“取输出”。

> 本文只讨论视频轨道，不包含音频解码、音视频同步和封装格式写入。

---

## 1. 整体架构

典型的视频播放链路如下：

```text
┌──────────────┐     压缩样本       ┌──────────────┐     解码帧      ┌──────────────┐
│ 视频文件     │ ────────────────▶ │ MediaCodec   │ ────────────▶ │ Surface      │
│ MP4/MKV/...  │  MediaExtractor   │ 视频解码器    │  release...   │ 屏幕/纹理      │
└──────────────┘                  └──────────────┘               └──────────────┘
        │                                  │                            │
        └────── MediaFormat：MIME、宽高、CSD ────────────────────────────┘
```

需要区分两个概念：

1. `MediaExtractor` 读取的是压缩数据，例如 H.264 的 NAL 单元，并不是 RGB/YUV 视频帧。
2. `MediaCodec` 配置了 `Surface` 作为输出目标后，解码后的像素通常不会通过 Java `ByteBuffer` 返回，而是直接进入图形管线。

因此，应用通常只需要把压缩数据写入 Codec 的输入缓冲区，再通过 `releaseOutputBuffer(outputIndex, true)` 把输出缓冲区提交给 `Surface`。

## 2. 三个核心组件

### 2.1 MediaExtractor：读取容器中的样本

`MediaExtractor` 负责解析媒体容器。一个 MP4 可能包含视频、音频和字幕等多个轨道。本例只选择第一条 MIME 类型以 `video/` 开头的轨道。

常用方法：

| 方法 | 作用 |
| --- | --- |
| `setDataSource(...)` | 打开文件、`Uri` 或文件描述符 |
| `getTrackCount()` | 获取轨道数量 |
| `getTrackFormat(index)` | 获取轨道的 `MediaFormat` |
| `selectTrack(index)` | 选择后续读取的轨道 |
| `readSampleData(buffer, offset)` | 读取当前样本的压缩数据 |
| `getSampleTime()` | 获取当前样本的展示时间戳，单位微秒 |
| `getSampleTrackIndex()` | 获取当前样本所属的轨道 |
| `advance()` | 移动到下一个样本 |
| `seekTo(timeUs, mode)` | 跳转到指定时间附近 |
| `release()` | 释放 Extractor |

选择视频轨道的代码：

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

选择轨道后必须调用：

```java
extractor.selectTrack(videoTrackIndex);
```

否则 `readSampleData()` 不会按照预期读取所选轨道的样本。

### 2.2 MediaFormat：描述解码器输入格式

`MediaFormat` 中至少需要关注：

```java
String mime = format.getString(MediaFormat.KEY_MIME);
int width = format.getInteger(MediaFormat.KEY_WIDTH);
int height = format.getInteger(MediaFormat.KEY_HEIGHT);
```

它还可能包含：

- `KEY_FRAME_RATE`：标称帧率；
- `KEY_DURATION`：时长，单位通常是微秒；
- `KEY_ROTATION`：视频旋转角度；
- `csd-0`、`csd-1`：codec-specific data，例如 H.264 的 SPS/PPS；
- HDR、颜色空间、裁剪区域等信息。

配置 `MediaCodec` 时应直接使用 `extractor.getTrackFormat()` 返回的完整格式，因为其中包含解码器初始化所需的 CSD 数据：

```java
MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
MediaCodec decoder = MediaCodec.createDecoderByType(
        format.getString(MediaFormat.KEY_MIME));
decoder.configure(format, outputSurface, null, 0);
```

不要只手动创建一个包含 MIME 和宽高的简化 `MediaFormat`，否则可能丢失 SPS/PPS 等初始化信息。

### 2.3 MediaCodec：两个异步队列

可以把解码器理解成两个队列：

```text
应用 ── queueInputBuffer() ──▶ [Codec 输入队列] ──▶ 解码器
应用 ◀─ dequeueOutputBuffer() ─ [Codec 输出队列] ◀─ 解码器
```

输入和输出不一定一一对应：

- 解码器可能需要积累多个样本才产生第一帧；
- B 帧可能导致解码顺序和显示顺序不同；
- 输入已经发送 EOS 后，输出队列中仍可能有尚未取出的帧；
- `dequeueOutputBuffer()` 返回的负数有时是状态通知，不一定是错误。

所以工作循环必须同时处理输入和输出，不能简单地“提交一个输入，然后只等待一个输出”。

## 3. 准备输出 Surface

`MediaCodec` 需要一个有效的 `Surface` 作为视频输出目标。常见来源有两种。

### 3.1 TextureView

```java
TextureView textureView = findViewById(R.id.video_texture_view);
SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
Surface surface = new Surface(surfaceTexture);
```

`getSurfaceTexture()` 可能返回 `null`。实际应用应实现 `TextureView.SurfaceTextureListener`，等收到 `onSurfaceTextureAvailable()` 后再创建 `Surface`。

### 3.2 SurfaceView

```java
Surface surface = surfaceView.getHolder().getSurface();
```

同样要等 `SurfaceHolder.Callback.surfaceCreated()` 后才能使用。

### 3.3 Surface 生命周期

Surface 的生命周期必须和解码器协调：

1. Surface 创建完成后，才能调用 `decoder.configure(..., surface, ...)`；
2. 播放期间不能销毁或替换输出 Surface；
3. Activity 暂停或 Surface 销毁前，应先请求播放器停止，并等待解码线程退出；
4. 谁创建的 `Surface`，通常由谁负责调用 `release()`。

本项目的 [`PlayMovieActivity`](../app/src/main/java/com/android/grafika/PlayMovieActivity.java) 在 `SurfaceTexture` 可用后创建 `Surface`，并在 Activity 暂停时等待 `MoviePlayer.PlayTask` 停止。

如果使用文件选择器返回的 `Uri`，可以这样设置数据源：

```java
extractor.setDataSource(context, uri, null);
```

如果访问应用私有目录中的文件，则通常不需要存储权限：

```java
extractor.setDataSource(file.getAbsolutePath());
```

## 4. 标准播放流程

### 阶段一：创建 Extractor 并选择视频轨道

```java
MediaExtractor extractor = new MediaExtractor();
extractor.setDataSource(file.getAbsolutePath());

int videoTrack = selectVideoTrack(extractor);
if (videoTrack < 0) {
    throw new IllegalArgumentException("No video track found");
}

extractor.selectTrack(videoTrack);
MediaFormat format = extractor.getTrackFormat(videoTrack);
```

### 阶段二：创建并配置 Decoder

```java
String mime = format.getString(MediaFormat.KEY_MIME);
MediaCodec decoder = MediaCodec.createDecoderByType(mime);

// 第二个参数是输出 Surface；普通非 DRM 视频的 crypto 传 null，flags 传 0。
decoder.configure(format, outputSurface, null, 0);
decoder.start();
```

### 阶段三：向 Decoder 输入压缩样本

```java
int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
if (inputIndex >= 0) {
    ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
    int sampleSize = extractor.readSampleData(inputBuffer, 0);

    if (sampleSize < 0) {
        // 没有更多输入，发送空缓冲区并标记输入 EOS。
        decoder.queueInputBuffer(
                inputIndex, 0, 0, 0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    } else {
        long presentationTimeUs = extractor.getSampleTime();
        decoder.queueInputBuffer(
                inputIndex, 0, sampleSize, presentationTimeUs, 0);
        extractor.advance();
    }
}
```

### 阶段四：从 Decoder 取出解码输出

```java
MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

if (outputIndex >= 0) {
    boolean render = bufferInfo.size > 0;
    decoder.releaseOutputBuffer(outputIndex, render);
}
```

在 Surface 输出模式下，`render == true` 会把解码帧提交给 Surface，应用不需要调用 `getOutputBuffer()` 读取图像数据。

### 阶段五：处理 EOS 并释放资源

当输入数据耗尽时，需要向 Codec 发送输入 EOS。之后仍要继续调用 `dequeueOutputBuffer()`，直到输出 `BufferInfo.flags` 中出现 `BUFFER_FLAG_END_OF_STREAM`。

输入 EOS 和输出 EOS 不是同一时刻发生的：输入 EOS 表示“不会再有新的输入”，输出 EOS 表示“之前排队的输入已经全部处理完”。

```java
try {
    // 播放循环
} finally {
    if (decoder != null) {
        decoder.stop();
        decoder.release();
    }
    if (extractor != null) {
        extractor.release();
    }
}
```

## 5. 完整 Java 播放器示例

下面的类使用 API 21+ 的 `getInputBuffer()`，把视频直接输出到传入的 `Surface`。它支持停止请求、循环播放、PTS 回调和正确的 EOS 处理。

```java
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 使用 MediaExtractor + MediaCodec 将视频轨道输出到 Surface。
 *
 * <p>play() 是阻塞方法，应放在工作线程中调用。</p>
 */
public final class MediaCodecVideoPlayer {
    private static final long TIMEOUT_US = 10_000;

    private final File sourceFile;
    private final Surface outputSurface;
    private final FrameCallback frameCallback;

    private volatile boolean stopRequested;
    private volatile boolean loop;

    public MediaCodecVideoPlayer(
            File sourceFile,
            Surface outputSurface,
            FrameCallback frameCallback) {
        this.sourceFile = sourceFile;
        this.outputSurface = outputSurface;
        this.frameCallback = frameCallback;
    }

    /** 请求停止；方法立即返回，不等待播放线程退出。 */
    public void requestStop() {
        stopRequested = true;
    }

    /** 设置是否循环播放；通常应在 play() 开始前设置。 */
    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    /** 解码并播放视频，直到结束、停止或发生异常。 */
    public void play() throws IOException {
        if (!sourceFile.canRead()) {
            throw new FileNotFoundException("Unable to read " + sourceFile);
        }

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        boolean decoderStarted = false;

        try {
            // 1. 打开媒体文件并选择视频轨道。
            extractor = new MediaExtractor();
            extractor.setDataSource(sourceFile.getAbsolutePath());

            int videoTrack = selectVideoTrack(extractor);
            if (videoTrack < 0) {
                throw new IOException("No video track found in " + sourceFile);
            }
            extractor.selectTrack(videoTrack);

            // 2. 使用轨道的完整 MediaFormat 创建并配置解码器。
            MediaFormat format = extractor.getTrackFormat(videoTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("Video track has no MIME type");
            }

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, outputSurface, null, 0);
            decoder.start();
            decoderStarted = true;

            // 3. 进入输入/输出工作循环。
            decodeLoop(extractor, decoder);
        } finally {
            // 4. 无论正常结束、停止还是异常，都释放 Codec 和 Extractor。
            if (decoder != null) {
                if (decoderStarted) {
                    try {
                        decoder.stop();
                    } catch (IllegalStateException ignored) {
                        // Codec 已处于异常状态时仍继续 release。
                    }
                }
                decoder.release();
            }
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    private void decodeLoop(MediaExtractor extractor, MediaCodec decoder) {
        final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone && !stopRequested) {
            // A. 向解码器输入端喂入一个压缩样本。
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                    if (inputBuffer == null) {
                        throw new IllegalStateException("Null input buffer");
                    }

                    // readSampleData() 把当前样本复制到 offset=0 处。
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);

                    if (sampleSize < 0) {
                        // 没有更多输入：发送空缓冲区并标记输入 EOS。
                        decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();

                        // 把压缩数据、有效长度和展示时间戳交给 Codec。
                        decoder.queueInputBuffer(
                                inputIndex, 0, sampleSize,
                                presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            // B. 从解码器输出端取一个解码结果。
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // Surface 输出模式下不直接访问输出 ByteBuffer，通常可以忽略。
                continue;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = decoder.getOutputFormat();
                // 这里可以读取实际宽高、颜色格式等信息。
                System.out.println("Output format changed: " + outputFormat);
                continue;
            }

            if (outputIndex < 0) {
                throw new IllegalStateException(
                        "Unexpected decoder status: " + outputIndex);
            }

            boolean outputEos = (bufferInfo.flags
                    & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            boolean render = bufferInfo.size > 0;

            // 输出时间戳来自输入样本；回调可以在这里等待以匹配原始帧率。
            if (render && frameCallback != null) {
                frameCallback.beforeRender(bufferInfo.presentationTimeUs);
            }

            // true 表示将该解码帧提交到 configure() 时传入的 Surface。
            decoder.releaseOutputBuffer(outputIndex, render);

            if (render && frameCallback != null) {
                frameCallback.afterRender();
            }

            if (outputEos) {
                if (loop && !stopRequested) {
                    // 循环播放：Extractor 回到开头，Codec 清空 EOS 和参考帧状态。
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    decoder.flush();
                    inputDone = false;

                    if (frameCallback != null) {
                        frameCallback.onLoopReset();
                    }
                } else {
                    outputDone = true;
                }
            }
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

    /** 回调运行在播放线程，不要在其中直接操作 Android UI。 */
    public interface FrameCallback {
        void beforeRender(long presentationTimeUs);
        void afterRender();
        void onLoopReset();
    }
}
```

### 5.1 启动播放器

```java
SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
if (surfaceTexture == null) {
    throw new IllegalStateException("TextureView is not ready");
}

Surface surface = new Surface(surfaceTexture);
MediaCodecVideoPlayer player = new MediaCodecVideoPlayer(
        new File(getFilesDir(), "sample.mp4"),
        surface,
        new PtsFramePacer());
player.setLoop(true);

Thread playbackThread = new Thread(() -> {
    try {
        player.play();
    } catch (IOException | RuntimeException e) {
        e.printStackTrace();
    }
}, "VideoPlayback");
playbackThread.start();
```

停止时应先请求播放器退出，再等待线程结束，最后释放调用方创建的 `Surface`：

```java
player.requestStop();
try {
    playbackThread.join();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
surface.release();
```

不要在播放线程仍然运行时销毁 `Surface`，否则可能出现黑屏、`IllegalStateException` 或厂商 Codec 崩溃。

## 6. 按 PTS 控制播放速度

`MediaCodec` 会通过 `BufferInfo.presentationTimeUs` 返回每个输出帧的展示时间戳。这个时间戳表示帧在媒体时间轴上应该何时出现，而不是 Java 线程必须立即显示它。

如果只是不断调用 `releaseOutputBuffer()`，解码器可能以“尽可能快”的速度提交画面，结果通常会比正常播放速度快。最简单的 PTS 播放控制方式是：

1. 第一次输出帧记录媒体时间和单调时钟时间；
2. 后续帧计算相邻 PTS 的差值；
3. 根据差值等待；
4. 等待结束后再调用 `releaseOutputBuffer()`。

示例实现：

```java
public final class PtsFramePacer
        implements MediaCodecVideoPlayer.FrameCallback {

    private static final long ONE_SECOND_US = 1_000_000L;

    private long previousPtsUs;
    private long previousClockUs;
    private boolean loopReset;

    @Override
    public void beforeRender(long presentationTimeUs) {
        if (previousClockUs == 0) {
            previousPtsUs = presentationTimeUs;
            previousClockUs = System.nanoTime() / 1_000L;
            return;
        }

        if (loopReset) {
            // 新一轮的 PTS 通常从 0 附近重新开始。
            previousPtsUs = presentationTimeUs - ONE_SECOND_US / 30;
            loopReset = false;
        }

        long frameDeltaUs = presentationTimeUs - previousPtsUs;

        // 防御异常时间戳，避免负数或超长时间导致播放器卡死。
        if (frameDeltaUs < 0) {
            frameDeltaUs = 0;
        } else if (frameDeltaUs > 5 * ONE_SECOND_US) {
            frameDeltaUs = 5 * ONE_SECOND_US;
        }

        long desiredClockUs = previousClockUs + frameDeltaUs;
        long nowClockUs = System.nanoTime() / 1_000L;

        while (nowClockUs < desiredClockUs) {
            long sleepUs = desiredClockUs - nowClockUs;
            // 分段休眠，便于未来增加停止标志检查或中断处理。
            sleepUs = Math.min(sleepUs, 500_000L);

            try {
                Thread.sleep(sleepUs / 1_000L,
                        (int) (sleepUs % 1_000L) * 1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            nowClockUs = System.nanoTime() / 1_000L;
        }

        // 用计算出的目标时间推进，避免使用实际唤醒时间导致误差不断累积。
        previousClockUs += frameDeltaUs;
        previousPtsUs += frameDeltaUs;
    }

    @Override
    public void afterRender() {
        // 当前示例不需要处理提交完成通知。
    }

    @Override
    public void onLoopReset() {
        loopReset = true;
    }
}
```

### 6.1 使用带渲染时间的 releaseOutputBuffer

API 21 及以上还可以使用带纳秒时间戳的重载方法：

```java
decoder.releaseOutputBuffer(
        outputIndex,
        bufferInfo.presentationTimeUs * 1_000L);
```

它把帧的目标渲染时间交给 Surface 系统处理。实际项目中应根据设备、Surface 类型和目标 Android 版本测试效果。很多简单播放器仍然选择在 `releaseOutputBuffer()` 前自行等待，因为这样更容易理解和控制。

## 7. 输入端和输出端的状态处理

### 7.1 `dequeueInputBuffer()`

```java
int inputIndex = decoder.dequeueInputBuffer(timeoutUs);
```

返回值含义：

- `>= 0`：获得可写入的输入缓冲区索引；
- `INFO_TRY_AGAIN_LATER`（通常为 `-1`）：当前没有可用输入缓冲区，下一轮继续尝试。

不要因为一次返回 `-1` 就认为播放结束，它只表示这次等待期间 Codec 没有准备好输入缓冲区。

### 7.2 `queueInputBuffer()`

```java
decoder.queueInputBuffer(
        inputIndex,
        offset,
        size,
        presentationTimeUs,
        flags);
```

参数含义：

- `inputIndex`：从 `dequeueInputBuffer()` 获得的索引；
- `offset`：有效压缩数据在输入缓冲区中的起始位置；
- `size`：有效数据长度；
- `presentationTimeUs`：样本展示时间，单位微秒；
- `flags`：普通样本传 `0`，输入结束传 `BUFFER_FLAG_END_OF_STREAM`。

`readSampleData(inputBuffer, 0)` 返回的值就是应传给 `size` 的有效长度。

### 7.3 `dequeueOutputBuffer()`

```java
int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
```

常见返回值：

| 返回值 | 含义 | 处理方式 |
| --- | --- | --- |
| `>= 0` | 得到一个输出缓冲区索引 | 根据 `BufferInfo` 决定是否渲染，然后 release |
| `INFO_TRY_AGAIN_LATER` | 暂时没有输出 | 下一轮继续处理 |
| `INFO_OUTPUT_FORMAT_CHANGED` | 输出格式发生变化 | 调用 `getOutputFormat()` 读取新格式 |
| `INFO_OUTPUT_BUFFERS_CHANGED` | 旧版 API 的输出数组变化 | 重新获取数组，Surface 模式通常可忽略 |

拿到 `outputIndex >= 0` 后，必须最终调用 `releaseOutputBuffer()`，否则 Codec 的输出缓冲区会被占满，播放最终停滞。

### 7.4 `BufferInfo` 的重要字段

```java
bufferInfo.offset;               // 输出数据在 ByteBuffer 中的偏移
bufferInfo.size;                 // 输出数据大小
bufferInfo.presentationTimeUs;   // 展示时间戳，单位微秒
bufferInfo.flags;                // EOS、关键帧等标志
```

使用 Surface 输出时通常重点关注：

```java
boolean render = bufferInfo.size > 0;
boolean eos = (bufferInfo.flags
        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
```

注意：EOS 可能与最后一个输出缓冲区同时出现。因此即使 `eos == true`，也应先释放当前输出缓冲区，再决定结束或循环。

## 8. 循环播放

循环播放不是简单地把 `inputDone` 设回 `false`。一轮播放结束后至少要完成：

```java
extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
decoder.flush();
inputDone = false;
```

原因是：

- `seekTo()` 让 `MediaExtractor` 回到文件开头附近；
- `flush()` 清空 Decoder 内部残留的输入、参考帧和 EOS 状态；
- `inputDone = false` 允许工作循环重新给 Codec 输入数据。

如果使用帧率控制器，还要重置它的时间基准：

```java
frameCallback.onLoopReset();
```

否则新一轮从接近 0 开始的 PTS 可能会被错误地和上一轮末尾的 PTS 比较，导致长时间等待或时间戳倒退警告。

`SEEK_TO_CLOSEST_SYNC` 可能跳到最近的关键帧，而不是精确的时间 0。对 H.264/H.265 等包含参考帧的视频，这是正常且必要的行为。

## 9. 停止播放和线程模型

`MediaExtractor` 和 `MediaCodec` 的调用可能阻塞，不能直接在主线程执行。建议的线程模型是：

```text
UI 线程
  ├─ 创建 Surface、播放器和播放线程
  ├─ 点击停止：设置 volatile stopRequested=true
  └─ 等待或接收“播放已停止”回调

播放线程
  ├─ 调用 MediaExtractor / MediaCodec
  ├─ 在循环中检查 stopRequested
  ├─ 退出 decodeLoop()
  └─ finally 中释放 decoder 和 extractor
```

停止标志通常定义为：

```java
private volatile boolean stopRequested;
```

`volatile` 保证播放线程能够看到其他线程设置的新值，但它不会自动中断正在执行的系统调用。因此：

- `dequeueInputBuffer()` 和 `dequeueOutputBuffer()` 应使用有限超时，而不是无限等待；
- 播放循环应定期检查停止标志；
- 如果帧率控制器正在长时间 `sleep()`，应分段休眠，并检查停止标志或响应线程中断；
- Activity 的 `onPause()` 中，如果要销毁 Surface，应等待播放线程结束。

本项目的 `MoviePlayer.PlayTask` 使用 `requestStop()` 设置停止标志，并使用 `waitForStop()` 等待后台线程完成，之后通过 `Handler` 将 `playbackStopped()` 回调切回 UI 线程。

## 10. 旧版 API 与现代 API

### 10.1 API 21+：逐个获取输入缓冲区

```java
ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
```

这些方法更适合现代代码。

### 10.2 旧版 API：获取缓冲区数组

旧版 Grafika 代码通常使用：

```java
ByteBuffer[] inputBuffers = decoder.getInputBuffers();
ByteBuffer inputBuffer = inputBuffers[inputIndex];
```

输出缓冲区数组还可能因为 `INFO_OUTPUT_BUFFERS_CHANGED` 发生变化，需要重新调用 `getOutputBuffers()`。如果配置为 Surface 输出，一般不会读取输出数组，因此只需处理或忽略该状态即可。

本项目 [`MoviePlayer`](../app/src/main/java/com/android/grafika/MoviePlayer.java) 使用的就是这种较早的同步 API；理解它有助于阅读旧版 Android 媒体代码，但新代码应优先使用 API 21+ 的单个缓冲区方法，或者使用 MediaCodec 的异步回调模式。

## 11. 直接输出 Surface 与读取 YUV 的区别

### 11.1 输出到 Surface：适合播放

```java
decoder.configure(format, surface, null, 0);
```

优点：

- 通常能使用硬件解码和图形缓冲区；
- 不需要把每一帧复制到 Java 层；
- 功耗和性能通常更适合连续播放；
- 代码相对简单。

限制：

- 应用不能直接取得解码后的 YUV/RGB 字节；
- 不能方便地在 CPU 中逐像素处理；
- 需要维护有效的 Surface 生命周期。

### 11.2 输出到 ByteBuffer：适合自定义处理

```java
decoder.configure(format, null, null, 0);
```

这时应用需要：

1. 通过 `dequeueOutputBuffer()` 获得输出索引；
2. 调用 `getOutputBuffer(outputIndex)`；
3. 根据输出颜色格式解释数据；
4. 处理完成后调用 `releaseOutputBuffer(outputIndex, false)`。

YUV 的颜色格式、平面布局、stride 和 crop 信息可能因设备和 Codec 实现而不同。除非确实需要 CPU 侧处理，否则播放场景通常优先使用 Surface 输出。

## 12. 常见错误和排查方法

### 12.1 在主线程播放导致卡顿或 ANR

错误做法：

```java
player.play(); // 直接在 onClick() 或 onCreate() 中调用
```

正确做法是创建工作线程：

```java
new Thread(() -> player.play(), "VideoPlayback").start();
```

### 12.2 Surface 尚未创建

症状：`getSurfaceTexture()` 返回 `null`，或 `MediaCodec.configure()` 失败。

处理方式：

- `TextureView`：等待 `onSurfaceTextureAvailable()`；
- `SurfaceView`：等待 `surfaceCreated()`；
- Surface 销毁后停止旧播放器，不要继续复用旧 Surface。

### 12.3 忘记调用 `extractor.advance()`

如果成功读取一个样本后没有调用 `advance()`，下一轮会反复把同一个样本提交给 Codec，最终可能导致播放卡住或时间戳异常。

### 12.4 忘记发送输入 EOS

读取到 `sampleSize < 0` 时必须发送：

```java
decoder.queueInputBuffer(
        inputIndex, 0, 0, 0,
        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
```

否则 Codec 不知道输入已经结束，播放器可能一直等待输出结束信号。

### 12.5 收到输出 EOS 后立即退出

EOS 可能和最后一帧一起到达，应先释放当前输出：

```java
boolean eos = (bufferInfo.flags
        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
decoder.releaseOutputBuffer(outputIndex, bufferInfo.size > 0);

if (eos) {
    // 此时再结束或循环
}
```

### 12.6 忘记 release 输出缓冲区

拿到 `outputIndex >= 0` 后，无论是否渲染，都必须调用：

```java
decoder.releaseOutputBuffer(outputIndex, render);
```

### 12.7 把 `postRender()` 当成“屏幕已经显示”

`releaseOutputBuffer()` 返回通常只表示帧已提交给 Surface/图形管线，不表示用户已经看到这一帧。真正的显示还受 Surface 合成、VSYNC 和显示设备刷新率影响。

### 12.8 忽略视频旋转信息

部分视频的编码宽高与实际显示方向不同。除了 `KEY_WIDTH`、`KEY_HEIGHT`，还应检查：

```java
if (format.containsKey(MediaFormat.KEY_ROTATION)) {
    int rotation = format.getInteger(MediaFormat.KEY_ROTATION);
}
```

然后通过 `TextureView.setTransform()`、OpenGL 矩阵或布局逻辑处理旋转。

### 12.9 把 Surface 当成普通 Bitmap 绘制

MediaCodec 的 Surface 输出不需要应用调用 Canvas 绘制。若要清屏或进行复杂特效，应区分：

- 直接播放：Codec → Surface；
- 纹理处理：Codec → SurfaceTexture → OpenGL；
- CPU 处理：Codec → ByteBuffer → 应用转换/绘制。

## 13. 与本项目代码的对应关系

本项目中各组件的职责如下：

| 文件 | 作用 |
| --- | --- |
| [`MoviePlayer.java`](../app/src/main/java/com/android/grafika/MoviePlayer.java) | 使用 `MediaExtractor` 读取视频样本，并使用 `MediaCodec` 输出到 `Surface` |
| [`PlayMovieActivity.java`](../app/src/main/java/com/android/grafika/PlayMovieActivity.java) | 创建 `TextureView`、`Surface` 和 `PlayTask`，管理 UI 与生命周期 |
| [`SpeedControlCallback.java`](../app/src/main/java/com/android/grafika/SpeedControlCallback.java) | 根据 PTS 控制帧提交节奏，也支持固定 60 FPS |
| `PlayMovieSurfaceActivity.java` | 使用 `SurfaceView` 作为另一种输出目标 |

对应的关键代码关系是：

```text
PlayMovieActivity
    │ 创建 Surface
    ▼
MoviePlayer.PlayTask
    │ 启动工作线程
    ▼
MoviePlayer.play()
    │ 创建 Extractor 和 Decoder
    ▼
doExtract()
    ├─ extractor.readSampleData()
    ├─ decoder.queueInputBuffer()
    ├─ decoder.dequeueOutputBuffer()
    ├─ SpeedControlCallback.preRender()
    └─ decoder.releaseOutputBuffer(..., true)
```

## 14. 实际项目中的扩展方向

基础播放流程完成后，通常还需要考虑：

1. **音频播放**：另选音频轨道，使用音频 `MediaCodec` 解码，再交给 `AudioTrack`；
2. **音视频同步**：以音频时钟、系统时钟或外部主时钟为基准，必要时丢帧或重复帧；
3. **拖动进度**：停止或暂停输入，调用 `extractor.seekTo()`，并对 Decoder 执行 `flush()`；
4. **暂停/恢复**：通过状态机协调 Decoder、Surface 和播放时钟；
5. **旋转和裁剪**：读取 `KEY_ROTATION` 以及 crop 信息；
6. **硬件兼容性**：不同厂商 Codec 对颜色格式、参考帧、异常流的处理可能不同；
7. **错误恢复**：捕获 `CodecException`，必要时释放并重新创建 Codec；
8. **DRM**：受保护内容需要 `MediaCrypto`，不能按普通文件路径处理；
9. **现代异步 API**：使用 `MediaCodec.Callback`，由 Codec 回调输入/输出缓冲区事件；
10. **视频录制**：将 `MediaCodec` 编码器、`MediaMuxer` 和输入 Surface 组合起来，流程方向与解码播放相反。

## 15. 最小检查清单

实现一个稳定的视频 Surface 播放器时，可以按下面清单检查：

- [ ] 等待 `Surface` 创建完成后再配置 Decoder；
- [ ] 从 `MediaExtractor` 选择正确的视频轨道；
- [ ] 使用轨道返回的完整 `MediaFormat` 配置 Codec；
- [ ] 输入端正确处理 `dequeueInputBuffer()` 超时；
- [ ] 每个普通样本都传递正确的 PTS，并调用 `extractor.advance()`；
- [ ] 文件结束时发送输入 EOS；
- [ ] 持续取输出，直到收到输出 EOS；
- [ ] 每个输出索引最终都调用 `releaseOutputBuffer()`；
- [ ] 解码循环运行在后台线程；
- [ ] 停止时等待后台线程退出后再销毁 Surface；
- [ ] `finally` 中释放 Decoder 和 Extractor；
- [ ] 循环时同时 `seekTo()`、`flush()` 和重置时间基准；
- [ ] 根据 PTS 控制播放节奏，而不是盲目尽快提交所有帧；
- [ ] 根据需求处理旋转、裁剪、音频和错误恢复。

掌握以上流程后，核心过程可以概括为：

> 选择视频轨道，读取压缩样本，送入解码器，取出输出状态，把输出缓冲区提交给 Surface，并在 EOS、停止和资源释放时正确收尾。
