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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * 负责协调“外部 OpenGL ES 渲染线程”和“视频编码线程”的异步录制控制器。
 *
 * <p>本类的名字中虽然有 MovieEncoder，但它本身不负责绘制帧，也不调用
 * {@code eglSwapBuffers()}。真正的帧生产者（例如 {@link RecordFBOActivity} 的渲染线程）
 * 会把画面绘制到 {@link VideoEncoderCore#getInputSurface()} 对应的 EGLSurface；
 * 本类只负责在另一个线程中读取 MediaCodec 的编码输出，并交给 MediaMuxer。</p>
 *
 * <p>线程分工如下：</p>
 * <pre>
 * 外部渲染线程                          编码线程（本类创建）
 *     |                                      |
 *     | 在编码器输入 Surface 上绘制           | drainEncoder(false)
 *     | 设置 PTS + swapBuffers()              | 读取 H.264 输出
 *     |                                      | 写入 MediaMuxer
 *     | frameAvailableSoon() ---------------->| Handler 消息
 * </pre>
 *
 * <p>为什么每帧都要触发一次 {@code drainEncoder(false)}？Surface 输入编码器内部存在有限的
 * 缓冲区。如果只不断向输入 Surface 提交帧，却不及时取走编码输出，编码器的输出队列会
 * 堵塞，最终导致生产者线程在 {@code swapBuffers()} 处阻塞。这里通过 Handler 唤醒编码线程
 * 排空输出；排空动作可能发生在新帧提交前，也可能发生在提交后，两种顺序都能起到解除
 * 背压的作用。</p>
 *
 * <p>停止录制时，编码线程收到停止消息后调用
 * {@link VideoEncoderCore#drainEncoder(boolean) drainEncoder(true)}，向编码器发送 EOS，
 * 等待最后的编码数据写入 MP4，再释放 {@link VideoEncoderCore} 并退出 Looper。</p>
 *
 * <p>本类不是完全线程安全的。公开控制方法设计为由外部线程调用，实际编码状态和
 * {@link VideoEncoderCore} 由编码线程串行管理。TODO：该实现是旧版
 * {@code TextureMovieEncoder} 的简化/实验版本，后续可以统一两套编码器控制模型。</p>
 */
public class TextureMovieEncoder2 implements Runnable {
    /** 与主 Activity 统一的日志标签。 */
    private static final String TAG = MainActivity.TAG;
    /** 是否输出详细编码线程日志。 */
    private static final boolean VERBOSE = false;

    /** 请求编码线程完成 EOS 排空并停止。 */
    private static final int MSG_STOP_RECORDING = 1;
    /** 提醒编码线程尽快排空 MediaCodec 输出。 */
    private static final int MSG_FRAME_AVAILABLE = 2;

    // ----- 仅由编码线程访问 -----
    /** 底层编码器核心；编码线程负责读取输出并在停止时释放。 */
    private VideoEncoderCore mVideoEncoder;

    // ----- 由多个线程访问 -----
    /** 绑定在编码线程 Looper 上的 Handler；volatile 保证外部线程可见。 */
    private volatile EncoderHandler mHandler;

    /** 保护 mReady、mRunning 和 mHandler 初始化状态的同步对象。 */
    private Object mReadyFence = new Object();
    /** 编码线程是否已经创建 Looper/Handler 并可以接收消息。 */
    private boolean mReady;
    /** 录制控制器是否仍处于运行状态。 */
    private boolean mRunning;


    /**
     * 启动编码线程并接管传入的 {@link VideoEncoderCore}。
     *
     * <p>该构造函数应在外部线程调用。它会创建一个专用线程，并等待该线程完成
     * {@link Looper#prepare()}、创建 {@link EncoderHandler} 的初始化步骤后才返回，
     * 因此构造函数返回时可以安全调用 {@link #frameAvailableSoon()} 或 {@link #stopRecording()}。</p>
     *
     * <p>这里没有重新创建 VideoEncoderCore；调用方应在构造之前完成编码器和输入 Surface
     * 的初始化。编码线程只负责排空和最终释放这个对象。</p>
     *
     * @param encoderCore 已配置并启动的底层编码器核心
     */
    public TextureMovieEncoder2(VideoEncoderCore encoderCore) {
        Log.d(TAG, "Encoder: startRecording()");

        mVideoEncoder = encoderCore;

        synchronized (mReadyFence) {
            if (mRunning) {
                Log.w(TAG, "Encoder thread already running");
                return;
            }
            mRunning = true;
            new Thread(this, "TextureMovieEncoder").start();
            while (!mReady) {
                try {
                    mReadyFence.wait();
                } catch (InterruptedException ie) {
                    // 忽略中断，继续等待编码线程完成初始化。
                }
            }
        }
    }

    /**
     * 异步请求停止录制。
     *
     * <p>该方法只向编码线程发送停止消息，立即返回；此时 MP4 文件可能还没有完成。
     * 编码线程收到消息后会先发送 EOS 并排空最后输出，再释放编码器并退出 Looper。
     * 因此调用方如果需要确认文件已经完整生成，应额外增加完成回调或等待机制。</p>
     *
     * @throws NullPointerException 如果编码线程尚未准备好或已经退出，mHandler 为空
     */
    public void stopRecording() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING));
        // 停止、排空和文件封装都在编码线程完成，不阻塞调用方（通常是 UI 线程）。
    }

    /**
     * 返回编码线程是否仍处于运行状态。
     *
     * <p>该状态表示控制器线程是否存活，不等价于“MP4 文件已经写入有效帧”。</p>
     *
     * @return 编码线程尚未退出时返回 {@code true}
     */
    public boolean isRecording() {
        synchronized (mReadyFence) {
            return mRunning;
        }
    }

    /**
     * 提醒编码线程：外部渲染线程即将提交一帧或刚刚提交了一帧。
     *
     * <p>该方法不传递图像数据，也不负责绘制或交换输入 Surface，只发送一个消息让编码线程
     * 调用 {@code drainEncoder(false)}。它的目标是及时清理编码输出，避免外部生产者在
     * 下一次 {@code swapBuffers()} 时因编码器背压而阻塞。</p>
     *
     * <p>方法是异步的，调用后立即返回。若编码线程尚未创建 Handler，则直接忽略请求。</p>
     */
    public void frameAvailableSoon() {
        synchronized (mReadyFence) {
            if (!mReady) {
                return;
            }
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_FRAME_AVAILABLE));
    }

    /**
     * 编码线程入口：建立 Looper/Handler，并持续处理控制消息。
     *
     * <p>{@link Looper#prepare()} 为当前线程创建消息循环，Handler 绑定到当前线程的
     * MessageQueue。收到停止消息后，Handler 会主动调用 {@code Looper.quit()}，循环返回，
     * 最后清理 mReady、mRunning 和 mHandler 状态。</p>
     *
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        // 为编码线程建立 Looper，并创建绑定到该 Looper 的 Handler。
        Looper.prepare();
        synchronized (mReadyFence) {
            mHandler = new EncoderHandler(Looper.myLooper(), this);
            mReady = true;
            mReadyFence.notify();
        }
        Looper.loop();

        Log.d(TAG, "Encoder thread exiting");
        synchronized (mReadyFence) {
            mReady = mRunning = false;
            mHandler = null;
        }
    }


    /**
     * 编码线程的消息处理器。
     *
     * <p>Handler 必须在编码线程中创建，这样 {@link #handleMessage(Message)} 就会在编码线程
     * 执行。使用 {@link WeakReference} 避免 Handler 因消息队列长期存在而强引用整个控制器；
     * 控制器被回收后，消息会被安全忽略。</p>
     */
    private static class EncoderHandler extends Handler {
        /** 对外层控制器的弱引用。 */
        private WeakReference<TextureMovieEncoder2> mWeakEncoder;

        /** 将 Handler 绑定到当前（编码）线程的 Looper。 */
        public EncoderHandler(Looper looper, TextureMovieEncoder2 encoder) {
            super(looper);
            mWeakEncoder = new WeakReference<TextureMovieEncoder2>(encoder);
        }

        /** 按消息类型串行执行排空或停止操作。 */
        @Override  // 在编码线程执行
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Object obj = inputMessage.obj;

            TextureMovieEncoder2 encoder = mWeakEncoder.get();
            if (encoder == null) {
                Log.w(TAG, "EncoderHandler.handleMessage: encoder is null");
                return;
            }

            switch (what) {
                case MSG_STOP_RECORDING:
                    encoder.handleStopRecording();
                    // EOS 和资源释放完成后再退出消息循环，保证停止消息按顺序处理完毕。
                    Looper.myLooper().quit();
                    break;
                case MSG_FRAME_AVAILABLE:
                    encoder.handleFrameAvailable();
                    break;
                default:
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }

    /**
     * 处理“即将有新帧”的通知。
     *
     * <p>这里不读取帧，也不操作 EGLSurface，只排空 MediaCodec 当前可取出的编码数据。
     * 实际图像已经由外部渲染线程通过 VideoEncoderCore 的输入 Surface 提交。</p>
     */
    private void handleFrameAvailable() {
        if (VERBOSE) Log.d(TAG, "handleFrameAvailable");
        mVideoEncoder.drainEncoder(false);
    }

    /**
     * 在编码线程中完成停止流程。
     *
     * <p>先以 EOS 模式排空编码器，确保延迟输出的尾帧写入 muxer；再释放 VideoEncoderCore。
     * 方法返回后，Handler 会退出 Looper，编码线程最终把运行状态设置为 false。</p>
     */
    private void handleStopRecording() {
        Log.d(TAG, "handleStopRecording");
        mVideoEncoder.drainEncoder(true);
        mVideoEncoder.release();
    }
}
