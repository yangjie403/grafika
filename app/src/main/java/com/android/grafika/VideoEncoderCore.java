/*
 * Copyright 2014 Google Inc. All rights reserved.
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
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Surface 输入模式视频编码的核心封装类。
 *
 * <p>它把三个组件连接成一条完整的视频录制管线：</p>
 *
 * <pre>
 * OpenGL / Canvas / Camera
 *          │ 绘制到编码器输入 Surface
 *          ▼
 * MediaCodec 编码器（H.264）
 *          │ 编码后的 ByteBuffer
 *          ▼
 * MediaMuxer（MP4 封装）
 *          ▼
 * 输出文件
 * </pre>
 *
 * <p>创建对象后，调用者通过 {@link #getInputSurface()} 取得编码器输入 Surface，使用
 * EGL/OpenGL 或 Canvas 向其中提交视频帧。每一帧都必须设置正确的 presentation timestamp，
 * 并且通常要在下一次 {@code swapBuffers()} 之前调用 {@link #drainEncoder(boolean)}，
 * 及时取走编码器输出，防止编码器输出队列被填满后反过来阻塞输入端。</p>
 *
 * <p>本类不是完全线程安全的：同一个对象的编码器状态操作不能被多个线程随意并发调用。
 * 但可以由一个线程使用输入 Surface 提交帧，同时由另一个线程调用
 * {@code drainEncoder(false)} 排空输出；调用者仍需保证停止、释放等状态切换不会与这些
 * 操作并发发生。</p>
 *
 * <p>本类只封装视频轨道，不处理音频。MediaMuxer 的作用是把 MediaCodec 输出的 H.264
 * 基本码流和编码器提供的格式信息封装成可播放的 MP4 文件。</p>
 */
public class VideoEncoderCore {
    private static final String TAG = MainActivity.TAG;

    // 是否输出编码器格式、排空过程和时间戳等调试日志。
    private static final boolean VERBOSE = false;

    // TODO: these ought to be configurable as well
    // 编码格式：video/avc 表示 H.264/AVC 视频编码。
    private static final String MIME_TYPE = "video/avc";
    // 写入 MediaFormat 的目标帧率。实际帧的时间仍由调用者提交的 PTS 决定。
    private static final int FRAME_RATE = 30;
    // I 帧之间的目标间隔，单位为秒。I 帧越密，随机 seek 越容易，但码率通常也会更高。
    private static final int IFRAME_INTERVAL = 5;

    // MediaCodec.createInputSurface() 返回的编码器输入 Surface。
    // 上游通过它提交图像；本类不直接在 Surface 上绘制。
    private Surface mInputSurface;
    // MP4 封装器：把编码器输出的 H.264 样本写入文件。
    private MediaMuxer mMuxer;
    // H.264 MediaCodec 编码器：输入为 Surface，输出为压缩后的 ByteBuffer。
    private MediaCodec mEncoder;
    // 复用的输出信息对象，保存每次 dequeueOutputBuffer() 返回的 offset、size、PTS 和 flags。
    private MediaCodec.BufferInfo mBufferInfo;
    // 视频轨道在 MediaMuxer 中的索引；收到实际输出格式前为 -1。
    private int mTrackIndex;
    // MediaMuxer 是否已经 addTrack() 并 start()。
    private boolean mMuxerStarted;


    /**
     * 配置编码器和 MP4 封装器，并准备编码器输入 Surface。
     *
     * <p>初始化顺序如下：</p>
     * <ol>
     *     <li>创建视频 {@link MediaFormat}，指定编码尺寸、码率、帧率和 I 帧间隔；</li>
     *     <li>设置 {@link MediaCodecInfo.CodecCapabilities#COLOR_FormatSurface}，声明输入来自 Surface；</li>
     *     <li>创建并配置 H.264 编码器；</li>
     *     <li>从编码器取得输入 Surface，并启动编码器；</li>
     *     <li>创建 MediaMuxer，但暂不添加轨道或调用 {@code start()}。</li>
     * </ol>
     *
     * <p>不能在构造函数中立即启动 muxer，因为编码器真正的输出格式（尤其是 SPS/PPS 等
     * codec-specific data）要等编码器启动并开始处理数据后，通过
     * {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED} 才能取得。</p>
     *
     * @param width 视频编码宽度，单位为像素。
     * @param height 视频编码高度，单位为像素。
     * @param bitRate 目标视频码率，单位为 bit/s。
     * @param outputFile 输出 MP4 文件。
     * @throws IOException 创建 MediaMuxer 或打开输出文件失败时抛出。
     */
    public VideoEncoderCore(int width, int height, int bitRate, File outputFile)
            throws IOException {
        // BufferInfo 会在每次取出编码输出时被复用，减少对象分配。
        mBufferInfo = new MediaCodec.BufferInfo();

        // createVideoFormat() 先设置 MIME、宽度和高度，后续再补充编码器参数。
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // 声明编码器使用 Surface 输入，而不是通过 ByteBuffer 逐帧提交 YUV 数据。
        // 如果缺少这些参数，configure() 可能失败并给出不够直观的异常。
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 目标码率越高，通常画质越好，但输出文件也越大；实际效果还取决于内容和编码器。
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // 向编码器声明目标帧率，供码率控制等内部策略参考。
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        // 请求编码器大约每 5 秒插入一个 I 帧；I 帧不依赖前后参考帧，便于随机访问和恢复。
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // 创建 H.264 编码器。CONFIGURE_FLAG_ENCODE 表示这里创建的是编码器而不是解码器。
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // createInputSurface() 取得一个 BufferQueue 的生产端。调用者绘制并交换缓冲区后，
        // 编码器作为消费者读取图像并进行压缩。
        mInputSurface = mEncoder.createInputSurface();
        // 启动后才能提交帧，也才能逐步取得编码器实际输出格式和编码数据。
        mEncoder.start();

        // 创建 MP4 封装器。这里先创建对象，但不能马上 addTrack()/start()：
        // 编码器返回的初始 format 还不包含最终的 codec-specific data。
        // 等 drainEncoder() 收到 INFO_OUTPUT_FORMAT_CHANGED 后再启动 muxer。
        // 本类不封装音频，只把 H.264 视频基本码流写成 MP4。
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // -1 表示尚未把视频轨道添加到 muxer；false 表示 muxer 尚未 start。
        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * 返回编码器输入 Surface。
     *
     * <p>调用者可以把它包装成 EGL {@code WindowSurface}，然后按以下顺序提交一帧：</p>
     *
     * <pre>
     * drainEncoder(false);             // 先排空已有输出，避免输入端背压
     * inputWindowSurface.makeCurrent();
     * 绘制当前帧;
     * inputWindowSurface.setPresentationTime(timestampNanos);
     * inputWindowSurface.swapBuffers(); // 把当前帧交给编码器
     * </pre>
     *
     * <p>输入 Surface 的所有权和释放时机需要与调用者的 EGL 包装类协调。调用者不要在
     * 编码器仍在使用时直接释放它。</p>
     *
     * @return MediaCodec 创建的编码器输入 Surface。
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * 释放编码器和封装器资源。
     *
     * <p>正常结束流程应当是：</p>
     *
     * <pre>
     * drainEncoder(true);  // 发送 EOS，并取完最后的编码输出
     * release();           // stop/release MediaCodec，stop/release MediaMuxer
     * </pre>
     *
     * <p>调用者必须先完成 EOS 排空再调用本方法，否则文件可能缺少尾部数据或无法正确
     * 完成封装。此方法本身没有实现完全幂等的错误恢复逻辑，调用时应处于正确的生命周期状态。</p>
     */
    public void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            // stop() 结束编码器运行状态，release() 释放 MediaCodec 的底层资源。
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mMuxer != null) {
            // TODO: stop() throws an exception if you haven't fed it any data.  Keep track
            //       of frames submitted, and don't call stop() if we haven't written anything.
            // muxer 只有在 start() 后且写入过有效样本时才适合 stop()；当前实现假设正常流程
            // 已经写入数据，因此保留原有行为并记录这个边界条件。
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * 排空编码器当前已经产生的输出，并把有效编码样本转交给 MediaMuxer。
     *
     * <p>方法有两种工作模式：</p>
     * <ul>
     *   <li>{@code endOfStream == false}：只取走当前已经准备好的输出；暂时没有输出时
     *       立即返回，调用者可以继续绘制和提交下一帧；</li>
     *   <li>{@code endOfStream == true}：先调用 {@link MediaCodec#signalEndOfInputStream()}，
     *       再持续轮询，直到输出端也报告 EOS，确保编码器内部缓存的最后几帧全部写入文件。</li>
     * </ul>
     *
     * <p>典型调用顺序是：</p>
     *
     * <pre>
     * // 每帧提交前
     * drainEncoder(false);
     * drawFrame();
     * setPresentationTime(...);
     * swapBuffers();
     *
     * // 所有帧提交完后，只调用一次
     * drainEncoder(true);
     * release();
     * </pre>
     *
     * <p>第一次收到 {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED} 时，编码器才提供完整的
     * 输出格式。此时才能 {@link MediaMuxer#addTrack(MediaFormat) addTrack()} 并启动 muxer。
     * 后续的编码输出才可以通过 {@link MediaMuxer#writeSampleData(int, ByteBuffer, MediaCodec.BufferInfo)}
     * 写入 MP4。</p>
     *
     * <p>本类只封装视频，不处理音频。调用方应保证 EOS 模式只调用一次，并且在调用
     * {@link #release()} 之前完成。</p>
     *
     * @param endOfStream 是否结束输入并等待编码器输出 EOS。
     */
    public void drainEncoder(boolean endOfStream) {
        // 10ms 超时兼顾及时排空和避免线程无限阻塞。
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            // Surface 输入模式不能通过 queueInputBuffer() 发送空 EOS；使用专门的 API 告知
            // 编码器不再有新的 Surface 帧输入。
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        // 旧版同步 MediaCodec API 通过数组返回输出缓冲区。若收到 BUFFERS_CHANGED，
        // 需要重新取得数组引用。
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            // 一次调用会持续取输出，直到非 EOS 模式暂时没有数据，或 EOS 模式取到输出 EOS。
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 当前暂时没有编码输出。
                if (!endOfStream) {
                    // 普通模式下没有输出即认为本次排空完成，返回给调用者继续生产帧。
                    break;      // out of while
                } else {
                    // EOS 模式不能立即返回，因为编码器内部可能还有帧；继续轮询直到输出 EOS。
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // 对编码器来说通常不会发生，但兼容旧 API 的状态通知。
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 编码器输出格式发生变化。正常编码过程应在拿到第一个有效输出前发生一次。
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // 现在拿到了包含 SPS/PPS 等必要信息的最终格式，可以创建视频轨道并启动 muxer。
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                // 其他负数是 Codec 的状态通知或异常状态。原实现记录后忽略，继续尝试排空。
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                // 非负值表示拿到了一个可处理的编码输出缓冲区索引。
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Codec 配置数据已经包含在 INFO_OUTPUT_FORMAT_CHANGED 返回的格式中，
                    // 不作为普通媒体样本重复写入 muxer。
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    // 有效样本必须在 muxer 启动后写入；如果这里尚未启动，说明状态顺序异常。
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // BufferInfo 的 offset/size 描述有效编码数据在 ByteBuffer 中的范围。
                    // 调整 position/limit 后，MediaMuxer.writeSampleData() 只会读取这段数据。
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    // 写入一个视频样本；BufferInfo 同时携带该样本的 PTS 和 flags。
                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) {
                        Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                mBufferInfo.presentationTimeUs);
                    }
                }

                // 应用已经处理完这个输出缓冲区，归还给编码器，否则后续输出会被耗尽。
                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // 输出 EOS 表示编码器已经处理完输入端 EOS 之前提交的所有帧。
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }
}
