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
 * 封装“Surface 输入型”视频编码所需核心组件的类。
 *
 * <p>它不接收 YUV/RGB 字节数组，而是让调用方把 OpenGL ES 绘制结果写入
 * {@link #getInputSurface()} 返回的 Surface。典型的使用链路如下：</p>
 * <pre>
 * VideoEncoderCore 构造函数
 *     -> MediaFormat + MediaCodec 编码器
 *     -> MediaCodec.createInputSurface()
 *     -> 调用方用 EGL WindowSurface 包装输入 Surface
 *     -> OpenGL ES 绘制一帧
 *     -> 设置呈现时间戳并 swapBuffers()
 *     -> drainEncoder() 取出 H.264 数据
 *     -> MediaMuxer 封装为 MP4
 * </pre>
 *
 * <p>构造完成后，调用方需要把输入 Surface 连接到 EGL context，并在每帧提交前提供
 * 正确的 presentation timestamp。编码器输出不能无限积压，因此通常应在提交新帧前
 * 调用 {@link #drainEncoder(boolean) drainEncoder(false)}，让编码器输出缓冲区保持可用。</p>
 *
 * <p>类本身不是完全线程安全的。唯一允许的并发模式是：一个线程使用输入 Surface 进行
 * EGL/OpenGL ES 绘制，另一个线程调用 {@link #drainEncoder(boolean)} 读取编码器输出。
 * 构造、{@link #release()} 和状态切换仍应由调用方按照明确的线程生命周期管理。</p>
 */
public class VideoEncoderCore {
    /** 与主 Activity 统一的日志标签。 */
    private static final String TAG = MainActivity.TAG;
    /** 是否输出详细编码日志。 */
    private static final boolean VERBOSE = false;

    // TODO: 这些编码参数也可以改为构造函数参数，使调用方能够自由选择编码格式和帧率。
    /** H.264/AVC 的 MIME 类型。 */
    private static final String MIME_TYPE = "video/avc";
    /** 固定目标帧率，单位为 FPS。 */
    private static final int FRAME_RATE = 30;
    /** I 帧间隔，单位为秒；数值越小通常越容易随机 seek，但码率可能更高。 */
    private static final int IFRAME_INTERVAL = 5;

    /** MediaCodec 创建的输入 Surface；调用方通过 EGL 向它提交图像。 */
    private Surface mInputSurface;
    /** 将 H.264 编码数据封装成 MP4 的 muxer。 */
    private MediaMuxer mMuxer;
    /** Surface 输入型 H.264 编码器。 */
    private MediaCodec mEncoder;
    /** 复用的编码输出元数据对象。 */
    private MediaCodec.BufferInfo mBufferInfo;
    /** MediaMuxer 中视频轨道的索引；输出格式确定前为 -1。 */
    private int mTrackIndex;
    /** 是否已经添加视频轨道并启动 muxer。 */
    private boolean mMuxerStarted;


    /**
     * 配置编码器和 MP4 封装器，并准备编码器输入 Surface。
     *
     * <p>初始化过程分为两部分：</p>
     * <ol>
     *     <li>创建 {@link MediaFormat}，指定分辨率、码率、帧率、I 帧间隔和 Surface 输入格式。</li>
     *     <li>创建并启动 MediaCodec，取得它的输入 Surface，再创建 MediaMuxer。</li>
     * </ol>
     *
     * <p>此时 muxer 还没有启动，因为编码器的最终输出格式（包括 codec-specific data，
     * 例如 H.264 的 SPS/PPS）要等编码器开始工作后，通过
     * {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED} 才能取得。真正的轨道添加和 muxer
     * 启动在 {@link #drainEncoder(boolean)} 中完成。</p>
     *
     * <p>本类只创建输入 Surface，不创建 EGL context。调用方必须使用合适的 EGLConfig，
     * 通常需要带有 recordable 属性，然后把该 Surface 包装成 EGL window surface。</p>
     *
     * @param width 输出视频宽度，单位为像素
     * @param height 输出视频高度，单位为像素
     * @param bitRate 视频目标码率，单位为 bit/s
     * @param outputFile 输出 MP4 文件
     * @throws IOException 创建 MediaMuxer 失败时抛出
     */
    public VideoEncoderCore(int width, int height, int bitRate, File outputFile)
            throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // 设置编码器关键参数。某些参数缺失时，MediaCodec.configure() 可能只抛出难以定位的异常。
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // 创建并配置编码器，取得供 EGL 绘制使用的输入 Surface。EGL 包装由调用方负责。
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();

        // 创建 MediaMuxer，但此时不能添加轨道或调用 start()；编码器运行后才能取得最终输出格式。
        // 本类不封装音频，只把 MediaCodec 输出的 H.264 基本码流封装为 MP4。
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * 返回编码器输入 Surface。
     *
     * <p>调用方通常会把它传给 {@code WindowSurface}，然后在该 EGLSurface 上绘制视频帧。
     * 每次提交帧前还应设置呈现时间戳，再调用 EGL 的 swapBuffers()。</p>
     *
     * @return MediaCodec 的输入 Surface
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * 释放编码器和 MP4 封装器资源。
     *
     * <p>正常流程应先调用 {@link #drainEncoder(boolean) drainEncoder(true)}，等待编码器
     * 输出 EOS 后再释放。若在没有写入任何样本时直接停止 muxer，某些设备可能抛异常，
     * 这是原实现 TODO 中特别提醒的边界情况。</p>
     *
     * <p>本方法不释放调用方为输入 Surface 创建的 EGLSurface 或 EGLContext；那些对象的
     * 生命周期由调用方管理。MediaCodec 释放后，输入 Surface 也不能继续使用。</p>
     */
    public void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mMuxer != null) {
            // TODO: 如果没有向 muxer 写入任何数据，stop() 可能抛异常。应记录已提交帧数，
            //       在确实没有写入样本时避免调用 stop()。
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * 排空编码器输出，并把有效 H.264 数据写入 MP4 muxer。
     *
     * <p>当 {@code endOfStream == false} 时，方法在暂时没有输出数据时返回，调用方可以
     * 继续提交下一帧；当 {@code endOfStream == true} 时，先调用
     * {@link MediaCodec#signalEndOfInputStream()}，再持续轮询直到收到编码器 EOS，确保最后
     * 几帧都被写入文件。EOS 模式通常只应在所有帧提交完成后调用一次。</p>
     *
     * <p>首次收到 {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED} 时，使用编码器提供的最终
     * MediaFormat 添加视频轨道并启动 muxer。普通输出 buffer 则根据 BufferInfo 的 offset、
     * size、时间戳和 flags 写入 muxer。codec config buffer 已经包含在输出格式中，会被忽略。</p>
     *
     * <p>这里的 muxer 只封装视频，不录制音频；输出文件是 MP4，而不是裸 H.264 码流。</p>
     *
     * @param endOfStream 是否结束编码输入并等待输出端 EOS
     */
    public void drainEncoder(boolean endOfStream) {
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
                    break;      // 非 EOS 模式下本轮排空完成
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // 编码器通常不会触发该状态；若发生，则重新取得输出 buffer 数组。
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 输出格式应在正式输出 buffer 前报告，并且整个编码过程只应发生一次。
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // 已取得编码器最终输出格式，现在可以添加视频轨道并启动 muxer。
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
                    // codec 配置数据已经包含在 INFO_OUTPUT_FORMAT_CHANGED 对应的格式中，忽略该 buffer。
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // 按 BufferInfo 的 offset 和 size 限定有效数据范围，再交给 muxer 写入。
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) {
                        Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                mBufferInfo.presentationTimeUs);
                    }
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
