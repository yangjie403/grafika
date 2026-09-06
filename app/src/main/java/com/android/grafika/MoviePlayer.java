/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * 将影片文件中的视频轨道解码并输出到 {@link Surface}。
 *
 * <p>这个类只负责“播放管线”，不负责界面控件，也不负责音频。整体流程可以概括为：</p>
 *
 * <pre>
 * 文件
 *   │
 *   ├─ MediaExtractor：读取容器中的压缩视频样本（例如 H.264 NAL 单元）
 *   │
 *   ├─ MediaCodec：把压缩样本解码成视频帧
 *   │
 *   └─ Surface：MediaCodec 直接把解码帧送到这里，由系统图形管线显示
 * </pre>
 *
 * <p>{@link #play()} 是阻塞式方法，因此通常由 {@link PlayTask} 放在独立线程中执行。
 * UI 线程只负责创建任务、请求停止，以及接收播放结束通知。</p>
 *
 * <p>注意：这里没有把解码后的像素读回 CPU。{@code decoder.configure(..., surface, ...)}
 * 让解码器直接使用 Surface 作为输出目标，避免了一次昂贵的内存拷贝。</p>
 *
 * <p>TODO: needs more advanced shuttle controls (pause/resume, skip)</p>
 */
public class MoviePlayer {
    private static final String TAG = MainActivity.TAG;
    // 打开后可观察输入/输出缓冲区、时间戳和循环等调试信息。正常运行时关闭，避免日志过多。
    private static final boolean VERBOSE = false;

    // 复用同一个 BufferInfo，避免在每一帧输出时重复创建对象。
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    // 播放线程读取，调用 requestStop() 的线程写入，所以必须保证跨线程可见性。
    // volatile 只能保证“看到最新值”，不负责复杂的线程同步；这里的单个布尔标志已足够。
    private volatile boolean mIsStopRequested;

    // 下面这些字段在构造时确定，播放期间只读（循环开关除外）。
    private File mSourceFile;
    private Surface mOutputSurface;
    FrameCallback mFrameCallback;
    // true 表示读到输入 EOS 后回到文件开头继续播放。
    private boolean mLoop;
    // 构造时从视频轨道的 MediaFormat 中读取，供外部调整 View 的宽高比。
    private int mVideoWidth;
    private int mVideoHeight;


    /**
     * 由管理播放界面的类实现的回调。
     *
     * <p>播放线程结束后，PlayTask 会通过 Handler 把回调切回创建任务时绑定的线程
     * （通常是 UI 线程），因此实现类可以在这里更新按钮和其他控件。</p>
     */
    public interface PlayerFeedback {
        /** 播放自然结束，或收到停止请求并完成资源清理后调用。 */
        void playbackStopped();
    }


    /**
     * 每次输出视频帧时调用的回调。
     *
     * <p>MoviePlayer 不自行 sleep 来控制播放速度，而是把解码样本的时间戳交给这个回调。
     * 例如 {@code SpeedControlCallback} 可以根据相邻帧的 presentation time 安排等待，
     * 从而让“释放输出缓冲区”的节奏接近原视频的帧率。</p>
     *
     * <p>这些方法都在播放线程调用，不要在其中直接操作 Android UI 控件。</p>
     */
    public interface FrameCallback {
        /**
         * 在即将把输出缓冲区交给 Surface 前调用。
         *
         * @param presentationTimeUsec 该帧期望的展示时间，单位为微秒；它来自输入样本的
         *                              presentation timestamp。
         */
        void preRender(long presentationTimeUsec);

        /**
         * {@link MediaCodec#releaseOutputBuffer(int, boolean)} 返回后调用。
         *
         * <p>这个方法返回只表示缓冲区已经提交给 Surface/图形管线，并不保证此刻已经在屏幕
         * 上显示。因此它适合做“提交完成”的通知，不适合当作屏幕扫描完成的通知。</p>
         *
         * TODO: is this actually useful?
         */
        void postRender();

        /**
         * 循环播放时，最后一帧已经提交并且 Extractor/Decoder 已经重置后调用。
         *
         * <p>新一轮通常又从接近 0 的时间戳开始。回调可以在这里清零自己的计时基准，
         * 否则可能把下一轮的时间戳误认为是上一轮的延续。</p>
         */
        void loopReset();
    }


    /**
     * 创建一个 MoviePlayer，并提前读取视频尺寸。
     *
     * <p>构造函数会临时创建一个 MediaExtractor，只为了找到视频轨道并读取宽高，随后立即
     * 释放它。真正播放时，{@link #play()} 会重新创建 Extractor 和 Decoder。这样做意味着一个
     * MoviePlayer 实例主要是一次播放配置，而不是长期持有的解码器。</p>
     *
     * @param sourceFile 要打开的视频文件。
     * @param outputSurface 解码帧要发送到的 Surface；调用者负责保证它在播放期间有效。
     * @param frameCallback 帧节奏回调，可以为 null（此时不做额外的时间控制）。
     * @throws IOException 无法打开媒体文件或读取媒体信息时抛出。
     */
    public MoviePlayer(File sourceFile, Surface outputSurface, FrameCallback frameCallback)
            throws IOException {
        mSourceFile = sourceFile;
        mOutputSurface = outputSurface;
        mFrameCallback = frameCallback;

        // 先探测文件，取出视频轨道的基本属性。这里只做 metadata 探测，不开始解码。
        // TODO: consider leaving the extractor open.  Should be able to just seek back to
        //       the start after each iteration of play.  Need to rearrange the API a bit --
        //       currently play() is taking an all-in-one open+work+release approach.
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(sourceFile.toString());
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + mSourceFile);
            }
            extractor.selectTrack(trackIndex);

            MediaFormat format = extractor.getTrackFormat(trackIndex);
            // 宽高来自编码轨道的 MediaFormat，外部可用它保持 TextureView/SurfaceView 的宽高比。
            mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (VERBOSE) {
                Log.d(TAG, "Video size is " + mVideoWidth + "x" + mVideoHeight);
            }
        } finally {
            // 构造阶段的探测器不再使用，必须释放；真正播放会使用另一个探测器。
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /** @return 视频编码尺寸的宽度，单位为像素。 */
    public int getVideoWidth() {
        return mVideoWidth;
    }

    /** @return 视频编码尺寸的高度，单位为像素。 */
    public int getVideoHeight() {
        return mVideoHeight;
    }

    /**
     * 设置循环模式。
     *
     * <p>循环不是重新创建 MoviePlayer，而是在同一个播放循环中把 Extractor seek 到开头，
     * 并让 Decoder flush 后重新接受输入。</p>
     */
    public void setLoopMode(boolean loopMode) {
        mLoop = loopMode;
    }

    /**
     * 请求停止播放，但不等待播放线程真正结束。
     *
     * <p>方法只设置一个跨线程可见的标志；播放线程会在下一次工作循环开始时检查它，
     * 然后从 {@link #doExtract(MediaExtractor, int, MediaCodec, FrameCallback)} 返回，
     * 最终由 {@link #play()} 的 finally 释放 Decoder 和 Extractor。</p>
     *
     * <p>如果调用者必须确认 Surface 不会再收到帧，应在此之后调用
     * {@link PlayTask#waitForStop()}。</p>
     */
    public void requestStop() {
        mIsStopRequested = true;
    }

    /**
     * 解码视频并把视频帧发送到 Surface；这是实际执行播放的阻塞方法。
     *
     * <p>调用顺序是：创建 Extractor → 选择视频轨道 → 根据轨道 MIME 类型创建 Decoder →
     * 将 Decoder 配置为输出到 Surface → 启动 Decoder → 进入 doExtract 工作循环 → finally
     * 中停止并释放所有资源。</p>
     *
     * <p>此方法不会在普通播放结束前返回，也不会因为 {@link #requestStop()} 只设置标志就
     * 立即返回；它要等工作循环检查到标志并退出。不要在 UI 线程直接调用。</p>
     */
    public void play() throws IOException {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;

        // MediaExtractor 对“文件不存在”的报错通常不够直观，先主动检查可读性，给出更明确的异常。
        if (!mSourceFile.canRead()) {
            throw new FileNotFoundException("Unable to read " + mSourceFile);
        }

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(mSourceFile.toString());
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + mSourceFile);
            }
            extractor.selectTrack(trackIndex);

            MediaFormat format = extractor.getTrackFormat(trackIndex);

            // 根据轨道 MIME 类型创建解码器，例如 video/avc 对应 H.264 解码器。
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            // 必须使用 Extractor 返回的完整 MediaFormat 配置 Decoder。除了宽高和 MIME 类型外，
            // 其中还包含 CSD-0/CSD-1 等 codec-specific data（例如 SPS/PPS），缺少这些数据
            // 时解码器可能无法正确初始化。传入 Surface 后，输出帧走 Surface 渲染路径。
            decoder.configure(format, mOutputSurface, null, 0);
            decoder.start();

            doExtract(extractor, trackIndex, decoder, mFrameCallback);
        } finally {
            // 无论正常结束、请求停止还是发生异常，都要按 Decoder → Extractor 的顺序释放资源。
            // Decoder 仍可能持有对输入/输出资源的引用，所以先 stop/release Decoder 更稳妥。
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    /**
     * 在容器的所有轨道中查找第一条视频轨道。
     *
     * <p>一个 MP4 可能同时包含视频、音频、字幕等轨道；本类是 video-only，所以只选择
     * MIME 类型以 {@code video/} 开头的轨道，其余轨道留给调用者之外的逻辑处理。</p>
     *
     * @return 视频轨道索引；找不到视频轨道时返回 -1。
     */
    private static int selectTrack(MediaExtractor extractor) {
        // 选择找到的第一条视频轨道，忽略其他视频轨道和所有非视频轨道。
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }

    /**
     * Extractor 与 Decoder 之间的核心工作循环。
     *
     * <p>每一轮最多做两件事：</p>
     * <ol>
     *   <li>从 Extractor 取一个压缩样本，放进 Decoder 的可用输入缓冲区；</li>
     *   <li>从 Decoder 取一个已经解码好的输出缓冲区，并决定是否提交给 Surface。</li>
     * </ol>
     *
     * <p>输入和输出是异步队列：提交第 N 个压缩样本后，不一定马上得到第 N 个解码帧，
     * 因为解码器可能需要参考帧、重排序帧，或者内部还在启动。因此循环不能简单地
     * “提交一个样本后等待一个输出”，而是持续为输入队列补数据，同时轮询输出。</p>
     *
     * <p>{@code TIMEOUT_USEC} 是每次 dequeue 的最大等待时间。短超时可以避免某一侧长时间
     * 阻塞，使循环有机会及时处理另一侧，但也会带来更多轮询；这是吞吐、延迟和 CPU 开销之间
     * 的折中。</p>
     */
    private void doExtract(MediaExtractor extractor, int trackIndex, MediaCodec decoder,
            FrameCallback frameCallback) {
        // 下面的策略需要在“持续喂满输入”和“及时取出输出”之间取得平衡。

        // 10ms 的轮询超时：不让任一侧无限期阻塞，同时给 Codec 一点时间完成工作。
        final int TIMEOUT_USEC = 10000;
        // 旧版 MediaCodec API 通过数组暴露输入缓冲区；每个输入缓冲区由 index 标识。
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        // 仅用于日志统计，不参与解码逻辑。
        int inputChunk = 0;
        // 用来统计“第一次提交输入”到“第一次拿到输出”的启动延迟。
        long firstInputTimeNsec = -1;

        // outputDone 表示已经处理完输出 EOS；inputDone 表示已经向 Decoder 送入输入 EOS。
        // 两者不同：送入 EOS 后，Decoder 仍可能需要输出若干已经排队的帧。
        boolean outputDone = false;
        boolean inputDone = false;
        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "loop");
            if (mIsStopRequested) {
                Log.d(TAG, "Stop requested");
                return;
            }

            // 阶段一：尽可能向 Decoder 的输入队列补充一个样本。
            if (!inputDone) {
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    if (firstInputTimeNsec == -1) {
                        firstInputTimeNsec = System.nanoTime();
                    }
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                    // readSampleData 把当前 Extractor 样本复制到 inputBuf 的 offset=0 处。
                    // 它不会替我们维护 ByteBuffer 的 position/limit；queueInputBuffer 的 offset
                    // 和 size 参数才是告诉 Codec 应读取哪一段数据的依据。
                    int chunkSize = extractor.readSampleData(inputBuf, 0);
                    if (chunkSize < 0) {
                        // 当前样本不存在，表示输入流结束。向 Codec 发送一个空缓冲区并设置 EOS，
                        // 告诉它“之前排队的数据处理完后，不会再有输入”。
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        if (VERBOSE) Log.d(TAG, "sent input EOS");
                    } else {
                        if (extractor.getSampleTrackIndex() != trackIndex) {
                            // 理论上已 selectTrack，所以这里应该始终相等；不相等说明输入文件或
                            // 平台实现出现异常，但仍记录并继续处理当前样本。
                            Log.w(TAG, "WEIRD: got sample from track " +
                                    extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }
                        long presentationTimeUs = extractor.getSampleTime();
                        // 把压缩数据、有效长度和原始 presentation timestamp 一起交给 Decoder。
                        // 这个时间戳会随着输出 BufferInfo 传回来，供 preRender 控制显示节奏。
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/);
                        if (VERBOSE) {
                            Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize);
                        }
                        inputChunk++;
                        // advance 让 Extractor 指向当前轨道的下一个样本。
                        extractor.advance();
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available");
                }
            }

            // 阶段二：尝试取出一个解码输出。即使 inputDone=true，也必须继续执行这里，
            // 因为 Decoder 可能还没有吐完输入 EOS 之前已经接收的数据。
            if (!outputDone) {
                int decoderStatus = decoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 暂时没有输出；下一轮继续补输入/取输出。
                    if (VERBOSE) Log.d(TAG, "no output from decoder available");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // 使用 Surface 输出时不直接访问输出 ByteBuffer，因此无需重新取得输出数组。
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 解码器报告实际输出格式变化。这里不需要处理，因为输出直接交给 Surface，
                    // 但保留日志有助于排查分辨率/颜色格式等问题。
                    MediaFormat newFormat = decoder.getOutputFormat();
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: " + newFormat);
                } else if (decoderStatus < 0) {
                    throw new RuntimeException(
                            "unexpected result from decoder.dequeueOutputBuffer: " +
                                    decoderStatus);
                } else { // decoderStatus >= 0
                    if (firstInputTimeNsec != 0) {
                        // Log the delay from the first buffer of input to the first buffer
                        // of output.
                        long nowNsec = System.nanoTime();
                        Log.d(TAG, "startup lag " + ((nowNsec-firstInputTimeNsec) / 1000000.0) + " ms");
                        firstInputTimeNsec = 0;
                    }
                    // decoderStatus >= 0 表示拿到了一个有效的输出缓冲区索引。
                    boolean doLoop = false;
                    if (VERBOSE) Log.d(TAG, "surface decoder given buffer " + decoderStatus +
                            " (size=" + mBufferInfo.size + ")");
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 输出 EOS 表示 Codec 已经把输入 EOS 之前的内容全部处理完。
                        // 注意：EOS 可能和最后一帧一起到达，所以仍要先按 doRender 处理当前缓冲区。
                        if (VERBOSE) Log.d(TAG, "output EOS");
                        if (mLoop) {
                            doLoop = true;
                        } else {
                            outputDone = true;
                        }
                    }

                    // Surface 模式下，输出缓冲区的像素不需要由应用读取。
                    // size=0 的缓冲区通常只是控制信号（例如 EOS），不应当作为画面提交。
                    boolean doRender = (mBufferInfo.size != 0);

                    // releaseOutputBuffer(..., true) 是 Surface 模式真正的“提交画面”动作：
                    // Codec 会把该帧交给 Surface（SurfaceView 或 TextureView 背后的 Surface）。
                    // 应用无法精确控制它何时出现在物理屏幕上，但可以通过在 release 之前等待，
                    // 控制帧提交节奏。因此 preRender 放在 release 前，postRender 放在 release 后。
                    if (doRender && frameCallback != null) {
                        frameCallback.preRender(mBufferInfo.presentationTimeUs);
                    }
                    decoder.releaseOutputBuffer(decoderStatus, doRender);
                    if (doRender && frameCallback != null) {
                        frameCallback.postRender();
                    }

                    if (doLoop) {
                        // 一轮结束后的重置顺序很重要：Extractor 回到开头，Codec 清空旧状态，
                        // 然后重新允许输入。否则旧一轮的 EOS 状态会阻止下一轮解码。
                        Log.d(TAG, "Reached EOS, looping");
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        inputDone = false;
                        decoder.flush();    // reset decoder state
                        frameCallback.loopReset();
                    }
                }
            }
        }
    }

    /**
     * 播放线程辅助类。
     *
     * <p>MoviePlayer 的解码循环是阻塞式的，不能放在 UI 线程。PlayTask 创建名为
     * {@code "Movie Player"} 的工作线程运行它，并提供以下线程协作：</p>
     * <ul>
     *   <li>{@link #requestStop()}：异步设置停止标志，立即返回；</li>
     *   <li>{@link #waitForStop()}：阻塞等待工作线程完成资源释放；</li>
     *   <li>{@link PlayerFeedback#playbackStopped()}：通过 Handler 发回创建任务时的线程，
     *       让 UI 可以安全更新播放状态。</li>
     * </ul>
     *
     * <p>一个 PlayTask 对应一次播放生命周期；播放结束后通常由外部丢弃它并创建新的任务。</p>
     */
    public static class PlayTask implements Runnable {
        private static final int MSG_PLAY_STOPPED = 0;

        private MoviePlayer mPlayer;
        private PlayerFeedback mFeedback;
        // 这个开关在 execute() 时复制到 MoviePlayer，避免播放开始后再改变配置。
        private boolean mDoLoop;
        private Thread mThread;
        // LocalHandler 在构造 PlayTask 的线程上创建，用于把“播放已停止”消息投递回该线程。
        private LocalHandler mLocalHandler;

        // mStopped 由播放线程写入，等待线程通过这把锁获得可见性并被唤醒。
        private final Object mStopLock = new Object();
        private boolean mStopped = false;

        /**
         * 准备一个播放任务。
         *
         * @param player 已配置好输入文件、输出 Surface 和帧回调的播放器。
         * @param feedback 播放结束时通知界面的回调。
         */
        public PlayTask(MoviePlayer player, PlayerFeedback feedback) {
            mPlayer = player;
            mFeedback = feedback;

            mLocalHandler = new LocalHandler();
        }

        /** 设置循环模式；必须在 {@link #execute()} 前调用。 */
        public void setLoopMode(boolean loopMode) {
            mDoLoop = loopMode;
        }

        /**
         * 创建并启动播放线程。
         *
         * <p>先把 PlayTask 的循环配置同步给 MoviePlayer，再启动线程，确保工作线程一开始
         * 就使用正确的播放模式。</p>
         */
        public void execute() {
            mPlayer.setLoopMode(mDoLoop);
            mThread = new Thread(this, "Movie Player");
            mThread.start();
        }

        /**
         * 异步请求停止；可从任意线程调用，不等待停止完成。
         * 若要等待完成，请随后调用 {@link #waitForStop()}。
         */
        public void requestStop() {
            mPlayer.requestStop();
        }

        /**
         * 等待播放线程结束。
         *
         * <p>只能从 PlayTask 之外的线程调用，否则会等待自己而死锁。InterruptedException
         * 在这里被忽略，因为这个方法的契约是一直等到播放真正停止。</p>
         */
        public void waitForStop() {
            synchronized (mStopLock) {
                while (!mStopped) {
                    try {
                        mStopLock.wait();
                    } catch (InterruptedException ie) {
                        // discard
                    }
                }
            }
        }

        @Override
        public void run() {
            try {
                // 所有耗时的文件读取、Codec 调用和帧节奏控制都发生在这个工作线程。
                mPlayer.play();
            } catch (IOException ioe) {
                // 当前实现把播放 IO 异常转换为未检查异常；finally 仍会执行，等待者也会被唤醒。
                throw new RuntimeException(ioe);
            } finally {
                // 先唤醒 waitForStop() 的调用者，表示 MoviePlayer 已经离开 play()，资源清理完成。
                synchronized (mStopLock) {
                    mStopped = true;
                    mStopLock.notifyAll();
                }

                // playbackStopped() 不能直接在播放线程调用，否则 UI 回调会跑错线程；
                // 通过 Handler 把消息投递到创建 LocalHandler 时绑定的 Looper。
                mLocalHandler.sendMessage(
                        mLocalHandler.obtainMessage(MSG_PLAY_STOPPED, mFeedback));
            }
        }

        /**
         * 处理播放结束消息的轻量 Handler。
         *
         * <p>Handler 的关键作用不是执行解码，而是切换回合适的线程上下文，让
         * PlayerFeedback 可以安全地更新 UI。</p>
         */
        private static class LocalHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                int what = msg.what;

                switch (what) {
                    case MSG_PLAY_STOPPED:
                        PlayerFeedback fb = (PlayerFeedback) msg.obj;
                        fb.playbackStopped();
                        break;
                    default:
                        throw new RuntimeException("Unknown msg " + what);
                }
            }
        }
    }
}
