# MediaMuxer 使用详解

`MediaMuxer` 是 Android 提供的媒体封装器。它负责把已经编码好的音频或视频数据写入
MP4、WebM 等容器文件，但它本身不负责编码，也不负责采集摄像头或麦克风数据。

最重要的一句话是：

> `MediaCodec` 负责把原始数据编码成 H.264/AAC 等压缩数据，`MediaMuxer` 负责把这些压缩数据按照容器格式写入文件。

本文使用 Java 介绍 `MediaMuxer` 的工作原理、标准生命周期、Surface 输入编码流程、
音视频多轨封装、时间戳处理和常见错误。项目中的相关实现包括：

- [`VideoEncoderCore`](app/src/main/java/com/android/grafika/VideoEncoderCore.java)：视频编码器 + `MediaMuxer`；
- [`TextureMovieEncoder2`](app/src/main/java/com/android/grafika/TextureMovieEncoder2.java)：在独立线程排空编码器输出；
- [`CircularEncoder`](app/src/main/java/com/android/grafika/CircularEncoder.java)：把环形缓冲区中的编码数据保存为 MP4；
- [`ScreenRecordActivity`](app/src/main/java/com/android/grafika/ScreenRecordActivity.java)：屏幕录制和 `MediaMuxer` 示例。

---

## 1. MediaMuxer 解决什么问题

以 H.264 视频为例，`MediaCodec` 输出的是 H.264 elementary stream（基本码流），其中包含
编码后的视频样本，但它还不是一个可以直接播放的 MP4 文件：

```text
摄像头 / OpenGL / 原始 YUV
              |
              v
       MediaCodec 编码器
              |
              | H.264 压缩样本 + 时间戳 + flags
              v
        MediaMuxer 封装
              |
              v
          MP4 文件
```

MP4 容器除了保存样本数据，还需要保存：

- 视频轨道或音频轨道的格式信息；
- H.264 的 SPS/PPS 等 codec-specific data；
- 每个样本的展示时间戳（PTS）；
- 样本是否为关键帧等标志；
- 轨道时长、索引和容器元数据。

`MediaMuxer` 根据 `MediaFormat` 和 `MediaCodec.BufferInfo` 完成这些封装工作。

### 1.1 MediaMuxer 不负责编码

下面这些数据不能直接传给 `MediaMuxer.writeSampleData()`：

- 摄像头输出的原始 NV21/YUV 数据；
- OpenGL 绘制得到的 RGBA 像素；
- `Bitmap`；
- 麦克风采集的裸 PCM 数据。

这些数据必须先经过编码器：

- 原始视频 → H.264、HEVC 等视频编码器；
- 原始音频 → AAC、Opus 等音频编码器。

`writeSampleData()` 接收的是编码器输出的压缩样本，以及描述该样本的
`MediaCodec.BufferInfo`。

---

## 2. MediaMuxer 的核心 API

```java
MediaMuxer muxer = new MediaMuxer(
        outputPath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

int trackIndex = muxer.addTrack(encodedFormat);
muxer.start();
muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo);
muxer.stop();
muxer.release();
```

各方法的职责如下：

| API | 作用 | 调用时机 |
| --- | --- | --- |
| `new MediaMuxer(path, format)` | 创建封装器并打开输出文件 | 最早调用 |
| `addTrack(MediaFormat)` | 添加一条音频或视频轨道 | `start()` 之前 |
| `setOrientationHint(int)` | 设置视频旋转元数据 | `start()` 之前 |
| `setLocation(float, float)` | 写入拍摄地理位置元数据 | `start()` 之前，设备支持时使用 |
| `start()` | 完成轨道配置，开始接受样本 | 所有轨道添加完之后 |
| `writeSampleData(...)` | 写入一个编码样本 | `start()` 之后 |
| `stop()` | 完成文件尾部信息并关闭写入 | EOS 排空之后 |
| `release()` | 释放 native 资源和文件句柄 | 最后调用 |

### 2.1 严格的状态顺序

一个 `MediaMuxer` 的正常状态转换是：

```text
创建
  |
  v
添加所有轨道 addTrack()
  |
  v
启动 start()
  |
  v
写入样本 writeSampleData()
  |
  v
停止 stop()
  |
  v
释放 release()
```

不能这样使用：

```java
muxer.writeSampleData(trackIndex, buffer, info); // 错误：还没有 start()
muxer.addTrack(format);                          // 错误：start() 之后不能添加轨道
muxer.start();
muxer.start();                                   // 错误：不能重复 start()
```

### 2.2 `addTrack()` 的 MediaFormat 从哪里来

对于编码器，应该使用编码器报告的最终输出格式：

```java
if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
    MediaFormat outputFormat = encoder.getOutputFormat();
    trackIndex = muxer.addTrack(outputFormat);
}
```

不要简单地重新创建一个只有 MIME、宽度和高度的 `MediaFormat`。H.264 的 SPS/PPS、
音频的 AudioSpecificConfig 等初始化数据通常会包含在编码器输出格式中。缺少这些数据，
生成的文件可能无法播放，或者只能在部分设备上播放。

`INFO_OUTPUT_FORMAT_CHANGED` 一般在编码器启动并处理过输入后出现，而且一个编码器通常只会
报告一次。收到它之后才有足够的信息调用 `addTrack()`。

---

## 3. `MediaCodec.BufferInfo` 如何描述样本

`writeSampleData()` 的第三个参数是 `MediaCodec.BufferInfo`：

```java
public static final class BufferInfo {
    public int offset;              // 编码数据在 ByteBuffer 中的起始位置
    public int size;                // 当前样本的字节数
    public long presentationTimeUs; // 展示时间戳，单位是微秒
    public int flags;               // 关键帧、EOS、codec config 等标志
}
```

常用 flags：

| flag | 含义 |
| --- | --- |
| `BUFFER_FLAG_KEY_FRAME` | 当前视频样本是关键帧，也叫同步帧/I 帧 |
| `BUFFER_FLAG_CODEC_CONFIG` | 编码器配置数据，例如 SPS/PPS；通常已经放入输出格式，不直接写成普通样本 |
| `BUFFER_FLAG_END_OF_STREAM` | 编码器输出端已经到达 EOS |

写样本前要让 `ByteBuffer` 的 position/limit 与 `BufferInfo` 的有效范围一致：

```java
ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);

if (encodedData != null && info.size > 0) {
    encodedData.position(info.offset);
    encodedData.limit(info.offset + info.size);
    muxer.writeSampleData(trackIndex, encodedData, info);
}
```

注意：`info.presentationTimeUs` 是微秒，不是纳秒。如果上游使用 EGL 的
`eglPresentationTimeANDROID()`，通常拿到的是纳秒，需要转换：

```java
long presentationTimeUs = presentationTimeNs / 1000L;
```

在 `Surface` 输入编码流程中，应用通常给 EGL 帧设置纳秒时间戳，编码器随后在输出的
`BufferInfo.presentationTimeUs` 中提供微秒时间戳；写入 `MediaMuxer` 时直接使用编码器给出的
值即可。

---

## 4. 标准的视频封装流程

单路视频的完整流程可以概括为：

1. 创建并配置 `MediaCodec` 视频编码器；
2. 创建编码器输入 Surface，或者准备 ByteBuffer 输入；
3. 创建 `MediaMuxer`，但暂时不要 `start()`；
4. 向编码器持续提交视频帧；
5. 循环调用 `dequeueOutputBuffer()` 排空编码器输出；
6. 收到 `INFO_OUTPUT_FORMAT_CHANGED` 时调用 `addTrack()` 和 `start()`；
7. 收到普通输出样本时调用 `writeSampleData()`；
8. 所有输入提交完成后发送 EOS；
9. 继续排空，直到输出样本带有 `BUFFER_FLAG_END_OF_STREAM`；
10. 调用 `muxer.stop()`，最后调用 `release()`。

关键点是：**输入 EOS 不等于输出已经排空**。编码器内部可能还缓存着 B 帧、参考帧或延迟
输出，因此必须继续调用 `dequeueOutputBuffer()`，直到真正收到输出 EOS。

---

## 5. 示例一：Surface 输入的视频编码与 MP4 封装

Surface 输入适合摄像头、OpenGL、屏幕录制等场景。应用不需要把 RGBA/YUV 像素读回 CPU，
而是直接在编码器提供的输入 Surface 上绘制。

下面的示例展示一个可复用的 Java 类。它负责：

- 创建 H.264 `MediaCodec`；
- 创建编码器输入 `Surface`；
- 创建 `MediaMuxer`；
- 处理输出格式变化；
- 写入编码样本；
- 发送 EOS 并正确停止。

真正的 EGL 初始化和绘制可以参考项目中的 [`RecordFBOActivity`](app/src/main/java/com/android/grafika/RecordFBOActivity.java)。

### 5.1 Surface 输入编码器

```java
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 使用 MediaCodec 的 Surface 输入，将 OpenGL 绘制结果封装为 MP4。
 *
 * <p>调用方需要把 getInputSurface() 包装成 EGLWindowSurface，并在该 Surface 上绘制。
 * 本类不负责 EGL，也不负责绘制。</p>
 */
public final class SimpleSurfaceVideoEncoder {
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL_SEC = 2;
    private static final int TIMEOUT_USEC = 10_000;

    private final MediaCodec encoder;
    private final Surface inputSurface;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private int videoTrack = -1;
    private boolean muxerStarted;
    private boolean released;

    public SimpleSurfaceVideoEncoder(int width, int height, int bitRate,
            File outputFile) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();

        // 此时还不能 addTrack() 或 start()。编码器的最终输出格式尚未产生，
        // SPS/PPS 等 codec-specific data 还没有从 encoder.getOutputFormat() 取得。
        muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    /**
     * 排空当前已经产生的编码输出。
     *
     * @param endOfStream true 表示不再提交新帧，并一直等待编码器输出 EOS
     */
    public void drainEncoder(boolean endOfStream) {
        if (endOfStream) {
            // Surface 输入编码器必须使用这个 API 发送输入 EOS；不能 queueInputBuffer()。
            encoder.signalEndOfInputStream();
        }

        while (true) {
            int status = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    // 非 EOS 模式：当前暂时没有更多输出，本轮排空结束。
                    return;
                }
                // EOS 模式：即使暂时没有输出，也必须继续等待最终 EOS。
                continue;
            }

            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IllegalStateException("编码器重复报告输出格式变化");
                }

                MediaFormat outputFormat = encoder.getOutputFormat();
                videoTrack = muxer.addTrack(outputFormat);
                muxer.start();
                muxerStarted = true;
                continue;
            }

            if (status < 0) {
                // 例如 INFO_OUTPUT_BUFFERS_CHANGED（旧 API 设备可能出现）。
                // 这类状态不是普通输出 buffer，不能当成数组下标使用。
                continue;
            }

            ByteBuffer encodedData = encoder.getOutputBuffer(status);
            if (encodedData == null) {
                throw new IllegalStateException("编码器输出 ByteBuffer 为空");
            }

            boolean codecConfig = (bufferInfo.flags
                    & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (codecConfig) {
                // CSD 已包含在 INFO_OUTPUT_FORMAT_CHANGED 返回的 MediaFormat 中，
                // 不应再把这个配置 buffer 当作普通视频样本写入 MP4。
                bufferInfo.size = 0;
            }

            if (bufferInfo.size > 0) {
                if (!muxerStarted) {
                    throw new IllegalStateException("尚未启动 MediaMuxer");
                }

                encodedData.position(bufferInfo.offset);
                encodedData.limit(bufferInfo.offset + bufferInfo.size);
                muxer.writeSampleData(videoTrack, encodedData, bufferInfo);
            }

            boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            encoder.releaseOutputBuffer(status, false);

            if (eos) {
                // 只有收到输出端 EOS，才能确认编码器内部缓存已经排空。
                return;
            }
        }
    }

    /**
     * 停止编码并完成 MP4 文件。
     *
     * <p>调用方必须先停止向 inputSurface 绘制，然后调用本方法。</p>
     */
    public void stop() {
        if (released) {
            return;
        }

        try {
            // 发送 EOS，并持续 drain，直到 output buffer 带 EOS 标志。
            drainEncoder(true);
        } finally {
            if (muxerStarted) {
                muxer.stop();
                muxerStarted = false;
            }
            muxer.release();

            inputSurface.release();
            encoder.stop();
            encoder.release();
            released = true;
        }
    }
}
```

### 5.2 使用这个编码器绘制一帧

`MediaCodec.createInputSurface()` 返回的 `Surface` 不是普通的 Canvas 画布。对于 OpenGL
场景，需要把它包装成 EGL window surface：

```java
SimpleSurfaceVideoEncoder recorder = new SimpleSurfaceVideoEncoder(
        1280,
        720,
        4_000_000,
        new File(getFilesDir(), "demo.mp4"));

// 伪代码：EglCore 和 WindowSurface 的具体实现可以复用本项目 gles 包。
WindowSurface encoderSurface = new WindowSurface(
        eglCore,
        recorder.getInputSurface(),
        true);

encoderSurface.makeCurrent();
drawScene();

// WindowSurface.setPresentationTime() 的参数是纳秒。
encoderSurface.setPresentationTime(frameTimeNanos);
encoderSurface.swapBuffers();

// 只通知/触发排空，不需要把图像 ByteBuffer 传给 MediaMuxer。
recorder.drainEncoder(false);

encoderSurface.release();
recorder.stop();
```

实际项目通常把 `drainEncoder(false)` 放在独立编码线程中。原因是编码器输出队列有限，
如果长时间不排空，渲染线程最终可能在 `swapBuffers()` 处因背压阻塞。

本项目的 `TextureMovieEncoder2` 使用 Handler 将排空操作放到编码线程：

```text
渲染线程：draw -> setPresentationTime -> swapBuffers
       -> frameAvailableSoon()

编码线程：drainEncoder(false)
       -> writeSampleData()
       -> MediaMuxer 写入 MP4
```

---

## 6. 示例二：从已有编码输出直接封装

如果应用已经获得了编码器输出，就不需要再创建 `MediaCodec`。只要有：

1. 一份包含 CSD 的最终输出 `MediaFormat`；
2. 一个或多个编码样本 `ByteBuffer`；
3. 每个样本对应的 `BufferInfo`；

就可以直接调用 `MediaMuxer`：

```java
public static void muxVideoSamples(
        File outputFile,
        MediaFormat encodedVideoFormat,
        List<ByteBuffer> samples,
        List<MediaCodec.BufferInfo> infos) throws IOException {
    if (samples.size() != infos.size()) {
        throw new IllegalArgumentException("samples 和 infos 数量不一致");
    }

    MediaMuxer muxer = new MediaMuxer(
            outputFile.getAbsolutePath(),
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    boolean started = false;

    try {
        int track = muxer.addTrack(encodedVideoFormat);
        muxer.start();
        started = true;

        for (int i = 0; i < samples.size(); i++) {
            ByteBuffer sample = samples.get(i).duplicate();
            MediaCodec.BufferInfo info = infos.get(i);

            if (info.size <= 0) {
                continue;
            }

            sample.position(info.offset);
            sample.limit(info.offset + info.size);
            muxer.writeSampleData(track, sample, info);
        }
    } finally {
        if (started) {
            muxer.stop();
        }
        muxer.release();
    }
}
```

这里使用 `duplicate()` 是为了避免改变调用方持有的原始 `ByteBuffer` 的 position 和 limit。
如果样本来自环形缓冲区，还必须保证在 `writeSampleData()` 返回前，底层数据不会被覆盖。
项目的 [`CircularEncoderBuffer`](app/src/main/java/com/android/grafika/CircularEncoderBuffer.java)
正是通过管理编码数据和元信息来解决这个问题。

---

## 7. 示例三：音视频多轨封装

一个包含音视频的 MP4 通常有两条轨道：

```text
video encoder -> video MediaFormat -> addTrack() --+
                                                    +-> muxer.start()
audio encoder -> audio MediaFormat -> addTrack() --+

video sample -> writeSampleData(videoTrack, ...)
audio sample -> writeSampleData(audioTrack, ...)
```

关键规则：**所有轨道都必须添加完成后才能调用 `muxer.start()`。**

因此，视频格式先到达时不能立即启动一个最终需要音频的 muxer；需要先保存视频格式，
等待音频格式也到达。音频和视频编码器通常在不同线程运行，所以要用锁或线程安全的协调器。

下面是一个简化的多轨协调器：

```java
public final class AudioVideoMuxer {
    private final Object lock = new Object();
    private final MediaMuxer muxer;

    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean started;
    private boolean stopped;

    public AudioVideoMuxer(File outputFile) throws IOException {
        muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** 在视频编码器收到 INFO_OUTPUT_FORMAT_CHANGED 时调用。 */
    public void setVideoFormat(MediaFormat format) {
        synchronized (lock) {
            checkNotStopped();
            if (videoFormat != null) {
                throw new IllegalStateException("视频格式重复设置");
            }
            videoFormat = format;
            tryStartLocked();
        }
    }

    /** 在音频编码器收到 INFO_OUTPUT_FORMAT_CHANGED 时调用。 */
    public void setAudioFormat(MediaFormat format) {
        synchronized (lock) {
            checkNotStopped();
            if (audioFormat != null) {
                throw new IllegalStateException("音频格式重复设置");
            }
            audioFormat = format;
            tryStartLocked();
        }
    }

    private void tryStartLocked() {
        if (started || videoFormat == null || audioFormat == null) {
            return;
        }

        videoTrack = muxer.addTrack(videoFormat);
        audioTrack = muxer.addTrack(audioFormat);
        muxer.start();
        started = true;
    }

    /** 从视频编码线程写入一个编码样本。 */
    public void writeVideo(ByteBuffer data, MediaCodec.BufferInfo info) {
        synchronized (lock) {
            writeLocked(videoTrack, data, info);
        }
    }

    /** 从音频编码线程写入一个编码样本。 */
    public void writeAudio(ByteBuffer data, MediaCodec.BufferInfo info) {
        synchronized (lock) {
            writeLocked(audioTrack, data, info);
        }
    }

    private void writeLocked(int track, ByteBuffer data, MediaCodec.BufferInfo info) {
        checkNotStopped();
        if (!started || track < 0) {
            // 真实项目不能简单丢弃这里的样本；应先暂存，等两个轨道都准备好后再写入。
            throw new IllegalStateException("两个轨道尚未全部准备好");
        }

        if (info.size <= 0) {
            return;
        }

        ByteBuffer sample = data.duplicate();
        sample.position(info.offset);
        sample.limit(info.offset + info.size);
        muxer.writeSampleData(track, sample, info);
    }

    public void stop() {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            try {
                if (started) {
                    muxer.stop();
                }
            } finally {
                muxer.release();
                stopped = true;
            }
        }
    }

    private void checkNotStopped() {
        if (stopped) {
            throw new IllegalStateException("Muxer 已经停止");
        }
    }
}
```

这个示例为了突出 `MediaMuxer` 的状态管理，省略了“轨道尚未启动时的样本缓存”。生产代码
通常需要在 `started == false` 时缓存样本，或者让两个编码器先完成格式协商，再开始正式取出
和写入样本。不能因为另一个轨道还没有准备好就随意丢弃视频关键帧或音频数据。

### 7.1 音视频时间戳

音频和视频的 `presentationTimeUs` 必须使用同一个时间基准。常见方案是使用同一套单调时钟：

```java
long startNs = System.nanoTime();

long videoPtsUs = (videoFrameTimeNs - startNs) / 1000L;
long audioPtsUs = (audioCaptureTimeNs - startNs) / 1000L;
```

不要让视频使用“编码帧序号推导的时间戳”，而音频使用“录音设备时间戳”，却不做校准；两者
时间基准不同会造成逐渐漂移或开头不同步。

常见原则：

- PTS 通常从 0 或一个非负基准开始；
- 同一轨道的 PTS 应单调递增；
- 音频样本的 PTS 要根据实际采集样本数计算，而不是只根据 Java 方法调用次数计算；
- 视频使用 EGL/Codec 的呈现时间戳；
- 如果必须丢帧，应同时考虑时间戳是否仍然连续、是否需要保留关键帧。

---

## 8. EOS：正确停止编码和封装

停止流程不是简单的：

```java
encoder.stop();
muxer.stop();
```

正确顺序应该是：

```text
停止提交新帧
       |
       v
向 MediaCodec 发送输入 EOS
       |
       v
持续 dequeueOutputBuffer()
       |
       v
收到带 BUFFER_FLAG_END_OF_STREAM 的输出
       |
       v
muxer.stop()
       |
       v
muxer.release()
encoder.stop()/release()
```

### 8.1 Surface 输入编码器

```java
encoder.signalEndOfInputStream();
drainEncoder(true); // drainEncoder(true) 内部持续等待输出 EOS
```

### 8.2 ByteBuffer 输入编码器

ByteBuffer 输入则需要向编码器队列一个空输入 buffer，并设置 EOS flag：

```java
int inputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
if (inputIndex >= 0) {
    encoder.queueInputBuffer(
            inputIndex,
            0,
            0,
            lastPtsUs,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
}
```

之后仍然要持续排空输出，直到输出 buffer 的 flags 中出现
`BUFFER_FLAG_END_OF_STREAM`。

### 8.3 没有写入任何样本时的注意事项

如果编码器在输出格式变化前就停止，或者整个录制期间没有输出任何有效样本，调用
`muxer.stop()` 在部分设备上可能抛出异常。生产代码应维护：

- `muxerStarted`：是否已经调用 `muxer.start()`；
- `writtenSampleCount`：是否实际写入过样本；
- 是否已经收到输出 EOS。

至少要避免在未启动的 muxer 上调用 `stop()`：

```java
if (muxerStarted) {
    muxer.stop();
}
muxer.release();
```

如果已经启动但没有写入样本，是否调用 `stop()` 还要结合目标 Android 版本和设备行为处理，
必要时记录日志并删除不完整文件。

---

## 9. 本项目中的 MediaMuxer 调用链

以 `RecordFBOActivity` 为例，完整链路是：

```text
RecordFBOActivity.RenderThread
    |
    | new VideoEncoderCore(...)
    v
VideoEncoderCore
    |
    | MediaCodec.configure(COLOR_FormatSurface)
    | MediaCodec.createInputSurface()
    | new MediaMuxer(...)
    v
编码器输入 Surface
    |
    | EGLWindowSurface.makeCurrent()
    | OpenGL 绘制
    | setPresentationTime()
    | swapBuffers()
    v
MediaCodec H.264 编码器
    |
    | dequeueOutputBuffer()
    v
TextureMovieEncoder2 编码线程
    |
    | INFO_OUTPUT_FORMAT_CHANGED
    | muxer.addTrack(outputFormat)
    | muxer.start()
    | muxer.writeSampleData(track, buffer, info)
    v
MP4 文件
```

`VideoEncoderCore.drainEncoder()` 中的关键逻辑是：

```java
if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
    MediaFormat newFormat = mEncoder.getOutputFormat();
    mTrackIndex = mMuxer.addTrack(newFormat);
    mMuxer.start();
    mMuxerStarted = true;
} else if (encoderStatus >= 0) {
    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];

    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
        mBufferInfo.size = 0;
    }

    if (mBufferInfo.size != 0) {
        encodedData.position(mBufferInfo.offset);
        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
        mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
    }

    mEncoder.releaseOutputBuffer(encoderStatus, false);
}
```

这里的设计要点是：

1. `MediaMuxer` 创建得比较早，但启动得比较晚；
2. 必须等待编码器输出格式变化，取得包含 CSD 的最终 `MediaFormat`；
3. 只把有效编码样本写入 muxer；
4. 停止时使用 EOS 模式排空最后几帧；
5. 编码输出排空放在独立线程，避免磁盘 I/O 影响 GL 渲染。

---

## 10. 旋转、位置和文件输出

### 10.1 视频旋转

`MediaMuxer.setOrientationHint()` 写入的是容器元数据，不会旋转像素：

```java
MediaMuxer muxer = new MediaMuxer(
        outputPath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

// 必须在 muxer.start() 之前调用。
muxer.setOrientationHint(90);

int track = muxer.addTrack(outputFormat);
muxer.start();
```

如果目标播放器不尊重旋转元数据，就需要在编码前通过矩阵真正旋转画面，而不能只依赖
`setOrientationHint()`。

### 10.2 地理位置

部分场景可以写入经纬度元数据：

```java
muxer.setLocation(latitude, longitude);
```

该方法同样必须在 `start()` 之前调用，并且应遵守应用的位置权限和隐私要求。

### 10.3 输出路径

`MediaMuxer` 不负责创建复杂目录。应先确保父目录存在，并确认文件路径有写权限：

```java
File outputFile = new File(context.getFilesDir(), "recording.mp4");
File parent = outputFile.getParentFile();
if (parent != null && !parent.exists() && !parent.mkdirs()) {
    throw new IOException("无法创建输出目录：" + parent);
}

MediaMuxer muxer = new MediaMuxer(
        outputFile.getAbsolutePath(),
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
```

应用私有目录通常不需要额外存储权限。向公共媒体库输出时，应结合 `MediaStore` 和当前
Android 版本的分区存储规则处理，而不是直接假设任意绝对路径都可写。

---

## 11. 常见错误与排查方式

### 错误一：`start() called in an invalid state`

常见原因：

- 没有先 `addTrack()`；
- `addTrack()` 之后又重复 `start()`；
- 多轨场景中轨道状态管理混乱。

排查：记录每条轨道的 index、`muxerStarted` 状态和 `MediaFormat`。

### 错误二：`writeSampleData called before start`

说明 `writeSampleData()` 调用得太早。必须等：

```text
INFO_OUTPUT_FORMAT_CHANGED
    -> getOutputFormat()
    -> addTrack()
    -> start()
    -> writeSampleData()
```

### 错误三：文件生成了但无法播放

重点检查：

- 是否使用了编码器的最终输出格式，而不是手写简化格式；
- 是否漏写了 `csd-0`、`csd-1`；
- 是否错误地把 `BUFFER_FLAG_CODEC_CONFIG` 当普通样本写入；
- `BufferInfo.offset` 和 `size` 是否正确设置；
- PTS 是否单调递增、是否出现负数；
- 是否在 EOS 后继续写入；
- 是否执行了 `muxer.stop()` 和 `muxer.release()`。

### 错误四：调用 `muxer.stop()` 崩溃

常见原因：

- muxer 从未 `start()`；
- 没有写入有效样本；
- 文件或轨道数据不完整；
- 多线程同时写入或停止。

建议使用单一封装线程，或者使用锁保护 muxer 状态，并在 finally 中根据
`muxerStarted` 做条件释放。

### 错误五：录制结束时最后几帧丢失

停止后立即调用 `muxer.stop()`，没有等待编码器输出 EOS，编码器内部缓存的最后几帧就不会
写入文件。必须执行：

```java
signalEndOfInputStream();
while (!outputEos) {
    drainEncoder();
}
```

### 错误六：音视频不同步

常见原因：

- 音频、视频使用了不同的时钟；
- 把纳秒直接当成微秒写入 `BufferInfo`；
- 音频采样率变化但 PTS 仍按固定帧率增加；
- 录制过程中丢帧后没有正确处理时间戳；
- 两条轨道没有从相同的时间基准开始。

---

## 12. 推荐的工程结构

一个较清晰的录制器可以拆成三层：

```text
采集/绘制层
    - Camera、OpenGL、AudioRecord、MediaProjection
    - 负责产生原始数据或向编码器输入 Surface 绘制

编码层
    - MediaCodec
    - 负责产生 H.264/AAC 压缩样本
    - 负责报告最终 MediaFormat

封装层
    - MediaMuxer
    - 负责 addTrack、start、writeSampleData、stop、release
```

线程建议：

- UI 线程只处理按钮、权限和状态显示；
- GL 线程负责 EGL/Surface 绘制；
- 编码线程负责 `dequeueOutputBuffer()` 和写入 muxer；
- 音频编码线程负责音频样本，但通过一个线程安全的 muxer 协调器写入；
- 停止流程由一个明确的 owner 统一协调，避免多个线程同时 `stop()`。

如果使用本项目已有实现，可以优先阅读：

1. [`VideoEncoderCore`](app/src/main/java/com/android/grafika/VideoEncoderCore.java) 的
   `drainEncoder(boolean)`；
2. [`TextureMovieEncoder2`](app/src/main/java/com/android/grafika/TextureMovieEncoder2.java)
   的编码线程和 EOS 处理；
3. [`CircularEncoder`](app/src/main/java/com/android/grafika/CircularEncoder.java) 的
   `saveVideo(File)`/保存环形缓冲区流程；
4. [`ScreenRecordActivity`](app/src/main/java/com/android/grafika/ScreenRecordActivity.java)
   的屏幕录制输出处理。

---

## 13. 最小记忆版

只需要记住下面这套顺序：

```java
MediaMuxer muxer = new MediaMuxer(path,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

// 从 MediaCodec.INFO_OUTPUT_FORMAT_CHANGED 取得最终格式
int track = muxer.addTrack(encoder.getOutputFormat());
muxer.start();

// 对每个有效编码输出执行
encodedBuffer.position(info.offset);
encodedBuffer.limit(info.offset + info.size);
muxer.writeSampleData(track, encodedBuffer, info);

// 编码器 EOS 已经排空后
muxer.stop();
muxer.release();
```

而完整录制流程是：

```text
原始数据/GL 画面
    -> MediaCodec 编码
    -> 等待 INFO_OUTPUT_FORMAT_CHANGED
    -> addTrack(format)
    -> start()
    -> writeSampleData(track, encodedBuffer, bufferInfo)
    -> 发送 EOS 并继续 drain
    -> stop()
    -> release()
```
