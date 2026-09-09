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
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 生成测试视频的抽象基类。
 *
 * <p>Grafika 的 {@link MovieEightRects} 和 {@link MovieSliders} 都继承自本类。子类只负责
 * 描述“每一帧画什么”以及视频的编码参数；本类统一负责把 OpenGL ES 渲染结果编码为 H.264，
 * 再使用 {@link MediaMuxer} 封装成 MP4 文件。</p>
 *
 * <p>完整的数据通路如下：</p>
 * <pre>
 * 子类 generateFrame()
 *        -> 当前 EGLSurface（MediaCodec 的输入 Surface）
 *        -> eglSwapBuffers()
 *        -> MediaCodec H.264 编码器
 *        -> 编码输出 ByteBuffer
 *        -> MediaMuxer
 *        -> MP4 文件
 * </pre>
 *
 * <p>本类使用的是 MediaCodec 的 Surface 输入模式，而不是把 YUV 或 RGB 字节数组直接
 * 送给编码器：先创建带 {@link MediaCodecInfo.CodecCapabilities#COLOR_FormatSurface}
 * 的编码器，再通过 {@link MediaCodec#createInputSurface()} 得到 Surface，最后使用
 * {@link EglCore} 和 {@link WindowSurface} 把 OpenGL ES 的绘制结果提交到该 Surface。</p>
 *
 * <p>一次视频生成通常由同一个后台线程顺序执行：初始化编码器 → 绘制并提交每帧 →
 * 排空编码输出 → 发送 EOS → 排空剩余输出 → 释放资源。EGL context 和其 GL 对象具有
 * 线程绑定要求，因此这些操作不能随意跨线程调用。</p>
 */
public abstract class GeneratedMovie implements Content {
    /** 与主 Activity 统一的日志标签。 */
    private static final String TAG = MainActivity.TAG;
    /** 是否输出详细编码日志；默认关闭以减少实验过程中的日志量。 */
    private static final boolean VERBOSE = false;

    /** I 帧间隔，单位为秒；传给 MediaFormat.KEY_I_FRAME_INTERVAL。 */
    private static final int IFRAME_INTERVAL = 5;

    /** 子类在 create() 成功完成后置为 true，表示该对象已经生成过视频。 */
    protected boolean mMovieReady = false;

    // 以下字段表示一次编码过程中的“活动状态”，create() 完成后应由 releaseEncoder() 释放。
    /** 保存编码器输出缓冲区的偏移、大小、时间戳和 flags。 */
    private MediaCodec.BufferInfo mBufferInfo;
    /** H.264 视频编码器。输入来自 mInputSurface，输出是编码后的 ByteBuffer。 */
    private MediaCodec mEncoder;
    /** MP4 封装器，把编码器输出写入视频轨道。 */
    private MediaMuxer mMuxer;
    /** 与编码器输入 Surface 关联的 EGL 核心对象。 */
    private EglCore mEglCore;
    /** 包装 MediaCodec 输入 Surface 的 EGL window surface。 */
    private WindowSurface mInputSurface;
    /** MediaMuxer 中视频轨道的索引；输出格式确定前为 -1。 */
    private int mTrackIndex;
    /** MediaMuxer 是否已经添加视频轨道并调用 start()。 */
    private boolean mMuxerStarted;

    /**
     * 创建视频内容。
     *
     * <p>子类应在此方法中定义编码参数和帧动画，通常按以下顺序调用本类的保护方法：</p>
     * <ol>
     *     <li>调用 {@link #prepareEncoder(String, int, int, int, int, File)} 初始化编码器。</li>
     *     <li>循环绘制每一帧，先 {@link #drainEncoder(boolean) drainEncoder(false)}，再提交帧。</li>
     *     <li>调用 {@link #drainEncoder(boolean) drainEncoder(true)} 发送 EOS 并取完剩余输出。</li>
     *     <li>在 finally 中调用 {@link #releaseEncoder()} 释放资源。</li>
     * </ol>
     *
     * <p>通常由 ContentManager 的后台任务调用，不能在 UI 线程执行，因为编码和 GL 绘制
     * 可能持续较长时间。</p>
     *
     * @param outputFile 生成的 MP4 文件
     * @param prog 接收当前生成进度的回调
     */
    public abstract void create(File outputFile, ContentManager.ProgressUpdater prog);

    /**
     * 判断指定编码器是否是已知的软件 H.264 编码器。
     *
     * <p>Surface 输入通常要求硬件或支持 Surface 模式的编码器。这个方法只识别 Android
     * 软件编码器的名称，用于在创建输入 Surface 失败时给出更明确的错误信息；它不是通用的
     * Codec 能力检测方法。</p>
     *
     * @param codec 要检查的 MediaCodec
     * @return 编码器名称为 {@code OMX.google.h264.encoder} 时返回 {@code true}
     */
    private static boolean isSoftwareCodec(MediaCodec codec) {
        String codecName = codec.getCodecInfo().getName();

        return ("OMX.google.h264.encoder".equals(codecName));
    }

    /**
     * 初始化视频编码器、MP4 封装器以及 EGL 输入 Surface。
     *
     * <p>方法的关键步骤：</p>
     * <ol>
     *     <li>创建视频 {@link MediaFormat}，设置 Surface 输入、码率、帧率和 I 帧间隔。</li>
     *     <li>创建并配置 MediaCodec 编码器。</li>
     *     <li>从编码器取得输入 Surface，并用 recordable EGLConfig 包装它。</li>
     *     <li>让 EGL 输入 Surface current，然后启动编码器。</li>
     *     <li>创建 MediaMuxer，但暂不 start；必须等编码器报告实际输出格式后才能添加轨道。</li>
     * </ol>
     *
     * <p>MediaCodec 的输出格式可能包含编码器运行后才确定的 SPS/PPS 等 codec-specific
     * 数据，因此不能仅根据最初的 MediaFormat 启动 muxer。真正的 muxer 启动发生在
     * {@link #drainEncoder(boolean)} 收到 {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED} 时。</p>
     *
     * @param mimeType 视频 MIME 类型，通常为 {@code video/avc}
     * @param width 视频宽度，单位为像素
     * @param height 视频高度，单位为像素
     * @param bitRate 编码码率，单位为 bit/s
     * @param framesPerSecond 目标帧率
     * @param outputFile 输出 MP4 文件
     * @throws IOException 创建 MediaMuxer 失败时抛出
     */
    protected void prepareEncoder(String mimeType, int width, int height, int bitRate,
            int framesPerSecond, File outputFile) throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);

        // 设置编码器关键参数。部分参数缺失时，MediaCodec.configure() 可能只抛出难以定位的异常。
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // 创建并配置编码器，取得可用于输入的 Surface，再用 WindowSurface 处理 EGL 绘制。
        mEncoder = MediaCodec.createEncoderByType(mimeType);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Log.v(TAG, "encoder is " + mEncoder.getCodecInfo().getName());
        Surface surface;
        try {
            surface = mEncoder.createInputSurface();
        } catch (IllegalStateException ise) {
            // 这是第一次尝试通过 Surface 输入编码；如果能判断失败原因，就给出更具体的提示。
            // TODO: 错误文本可以进一步移到 strings.xml，以支持国际化。
            if (isSoftwareCodec(mEncoder)) {
                throw new RuntimeException("Can't use input surface with software codec: " +
                        mEncoder.getCodecInfo().getName(),
                        ise);
            } else {
                throw new RuntimeException("Failed to create input surface", ise);
            }
        }
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        mInputSurface = new WindowSurface(mEglCore, surface, true);
        mInputSurface.makeCurrent();
        mEncoder.start();

        // 创建 MediaMuxer，但此时还不能添加视频轨道或调用 start()；实际输出格式要等编码器
        // 启动并处理数据后才能取得。这里不封装音频，只把 MediaCodec 输出的 H.264 基本码流
        // 封装为 MP4。
        if (VERBOSE) Log.d(TAG, "output will go to " + outputFile);
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * 释放编码过程中的全部资源。
     *
     * <p>该方法允许在初始化只完成一部分时调用，因此每个字段都先判空。正常流程中应在
     * {@code create()} 的 finally 块调用，确保编码失败、IO 异常或 GL 异常时也不会长期占用
     * MediaCodec、EGLSurface 和文件句柄。</p>
     */
    protected void releaseEncoder() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * 将当前 EGLSurface 中已经绘制好的帧提交给编码器。
     *
     * <p>这里通过 {@link WindowSurface#setPresentationTime(long)} 设置帧的 PTS，再调用
     * {@code eglSwapBuffers()} 将 back buffer 交给 MediaCodec。调用方应在提交下一帧前
     * 适当排空编码器输出；否则编码器输入队列已满时，交换缓冲区可能阻塞。</p>
     *
     * @param presentationTimeNsec 当前帧的呈现时间戳，单位为纳秒
     */
    protected void submitFrame(long presentationTimeNsec) {
        // 输入队列满时 eglSwapBuffers() 可能阻塞。调用方在提交下一帧前排空编码器输出，
        // 可以避免“提交线程被阻塞，因无法排空输出而一直无法解除阻塞”的死锁式情况。
        mInputSurface.setPresentationTime(presentationTimeNsec);
        mInputSurface.swapBuffers();
    }

    /**
     * 排空编码器当前已经产生的所有输出数据。
     *
     * <p>当 {@code endOfStream == false} 时，方法在暂时没有输出时返回，调用方可以继续
     * 绘制下一帧；当 {@code endOfStream == true} 时，先向编码器发送输入结束标志 EOS，
     * 然后持续轮询直到收到输出端 EOS，确保最后几帧不会丢失。EOS 模式通常只应在所有帧
     * 提交完成后调用一次。</p>
     *
     * <p>第一次收到 {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED} 时，使用编码器实际输出
     * 格式创建视频轨道并启动 MediaMuxer。之后每个有效输出 buffer 都根据 BufferInfo 的
     * offset、size、presentationTimeUs 和 flags 写入 muxer。</p>
     *
     * @param endOfStream 是否结束输入并等待编码器输出 EOS
     */
    protected void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 暂时没有输出数据。
                if (!endOfStream) {
                    break;      // 非 EOS 模式下暂时排空完成
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // 编码器通常不会触发该状态，但如果发生则重新取得输出 buffer 数组。
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式应在正式输出 buffer 前报告，并且整个编码过程只能发生一次。
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // 已取得编码器最终输出格式，现在可以创建视频轨道并启动 muxer。
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // 非致命状态，记录后忽略。
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // codec 配置数据已经包含在 INFO_OUTPUT_FORMAT_CHANGED 对应的格式中，忽略此 buffer。
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // 按 BufferInfo 的 offset 和 size 限定 ByteBuffer 可写入 muxer 的有效范围。
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // 已收到编码器 EOS，排空循环结束
                }
            }
        }
    }
}
