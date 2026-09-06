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

import android.os.Bundle;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.IOException;

/**
 * 同时将两路视频解码并输出到两个 {@link TextureView} 的示例 Activity。
 *
 * <p>这个示例最值得关注的地方不是“同时播放两个视频”本身，而是它尝试模拟实时视频流：
 * Activity 因旋转屏幕而重建时，不停止两个解码线程，也不重新创建解码器，而是保留原来的
 * {@link SurfaceTexture} 和播放线程，再把它们重新绑定到新 Activity 中的 TextureView。</p>
 *
 * <p>生命周期分为两种情况：</p>
 * <ul>
 *   <li>配置变化（例如横竖屏切换）：旧 Activity 被销毁，新 Activity 创建，但
 *       {@code isFinishing()} 为 false。此时保留两个 {@link VideoBlob}、解码器、SurfaceTexture
 *       和播放线程，只更新它们对应的 TextureView；</li>
 *   <li>真正离开页面（例如按返回键）：{@code isFinishing()} 为 true。此时请求两个播放线程
 *       停止，并释放对 VideoBlob 的静态引用。</li>
 * </ul>
 *
 * <p>两个视频的实际解码流程仍然由 {@link MoviePlayer} 完成。当前 Activity 只负责把每个
 * TextureView 的 SurfaceTexture 包装成 Surface，并协调 Activity 生命周期与后台线程。</p>
 *
 * <p>TODO: consider shutting down when the screen is turned off, to preserve battery.</p>
 */
public class DoubleDecodeActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    // 本示例固定同时播放两路视频，并在布局中上下排列两个 TextureView。
    private static final int VIDEO_COUNT = 2;

    // Activity 因配置变化重建时，普通实例字段会丢失；使用静态字段可以在同一进程内保留
    // VideoBlob 和播放状态。注意：static 不能跨进程被杀或应用进程重启保存数据。
    private static boolean sVideoRunning = false;
    private static VideoBlob[] sBlob = new VideoBlob[VIDEO_COUNT];

    /**
     * 创建或重新连接两个视频播放对象。
     *
     * <p>第一次进入页面时创建 VideoBlob。旋转后再次进入时，sVideoRunning 仍为 true，
     * 因此不创建新的解码线程，只把新布局中的两个 TextureView 交给已有 VideoBlob 重新绑定。</p>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_double_decode);

        if (!sVideoRunning) {
            // 每个 VideoBlob 对应一条独立的视频文件、一个 TextureView、一个 Surface 和一个
            // PlayMovieThread。两个对象互不共享解码器和输出 Surface。
            sBlob[0] = new VideoBlob((TextureView) findViewById(R.id.double1_texture_view),
                    ContentManager.MOVIE_SLIDERS, 0);
            sBlob[1] = new VideoBlob((TextureView) findViewById(R.id.double2_texture_view),
                    ContentManager.MOVIE_EIGHT_RECTS, 1);
            sVideoRunning = true;
        } else {
            // 配置变化后，VideoBlob 仍然持有旧的 SurfaceTexture；这里让它监听新 View，并
            // 将保存的 SurfaceTexture 设置到新 TextureView 上，使原来的解码输出继续可见。
            sBlob[0].recreateView((TextureView) findViewById(R.id.double1_texture_view));
            sBlob[1].recreateView((TextureView) findViewById(R.id.double2_texture_view));
        }
    }

    /**
     * Activity 暂停时根据“是否正在结束”决定保留还是停止视频。
     *
     * <p>仅仅因为旋转导致的暂停不能停止播放，否则每次旋转都会重建两个 MediaCodec，
     * 这既昂贵，也不能模拟连续的实时流。真正结束 Activity 时则必须请求停止，避免后台
     * 解码线程继续向即将失效的 SurfaceTexture 写入数据。</p>
     */
    @Override
    protected void onPause() {
        super.onPause();

        // isFinishing() 只表示当前 Activity 是否真的要结束；配置变化通常返回 false。
        boolean finishing = isFinishing();
        Log.d(TAG, "isFinishing: " + finishing);
        for (int i = 0; i < VIDEO_COUNT; i++) {
            if (finishing) {
                // requestStop() 是异步请求。当前示例没有等待线程真正退出，原代码中的 TODO
                // 也指出了这里存在进一步完善空间。
                sBlob[i].stopPlayback();
                // 清空数组元素，使新一轮进入页面时可以重新创建 VideoBlob 和播放资源。
                sBlob[i] = null;
            }
        }
        // 如果只是配置变化，保持 true；如果页面结束，下一次创建 Activity 时重新初始化。
        sVideoRunning = !finishing;
        Log.d(TAG, "onPause complete");
    }


    /**
     * 一路视频的播放封装对象。
     *
     * <p>一个 VideoBlob 把以下对象绑定在一起：</p>
     * <ul>
     *   <li>当前要显示视频的 TextureView；</li>
     *   <li>保存下来的 SurfaceTexture；</li>
     *   <li>由 SurfaceTexture 创建的 Surface，作为 MediaCodec 输出目标；</li>
     *   <li>实际执行 MoviePlayer 的 PlayMovieThread；</li>
     *   <li>控制帧节奏的 SpeedControlCallback。</li>
     * </ul>
     *
     * <p>旋转屏幕时，Activity 和 TextureView 会重建，但我们希望 MediaCodec 不重建。为此
     * 必须继续持有解码器输出所依赖的 SurfaceTexture。TextureView 在销毁旧 EGL 关联时会
     * 调用监听器，返回 false 可以告诉它不要释放仍由 VideoBlob 持有的 SurfaceTexture；
     * 新 Activity 再通过 {@link #recreateView(TextureView)} 将其交给新的 TextureView。</p>
     */
    private static class VideoBlob implements TextureView.SurfaceTextureListener {
        // 为两路视频生成不同的日志标签，便于区分线程和回调来自哪个 TextureView。
        private final String LTAG;
        // 当前 Activity 实例中的 TextureView；旋转后会替换为新 Activity 的 View。
        private TextureView mTextureView;
        // ContentManager 中的视频标识，不是数组下标；用于查找实际媒体文件路径。
        private int mMovieTag;

        // 跨 Activity 重建保存的 SurfaceTexture。它是 MediaCodec 输出链路的核心连接点。
        private SurfaceTexture mSavedSurfaceTexture;
        // 持续执行循环播放的后台线程；旋转时保留，真正退出页面时停止。
        private PlayMovieThread mPlayThread;
        // 同一条视频使用同一个回调对象，使播放节奏控制状态随播放线程一起保留。
        private SpeedControlCallback mCallback;

        /**
         * 创建一路视频的封装对象。
         *
         * @param view 要显示视频的 TextureView。
         * @param movieTag 要播放的视频标识，由 ContentManager 解析为实际文件路径。
         * @param ordinal 该 VideoBlob 的序号，仅用于生成日志标签。
         */
        public VideoBlob(TextureView view, int movieTag, int ordinal) {
            LTAG = TAG + ordinal;
            Log.d(LTAG, "VideoBlob: tag=" + movieTag + " view=" + view);
            mMovieTag = movieTag;

            // 回调对象不随 Activity 的 View 重建而重建，播放时间基准可以保持连续。
            mCallback = new SpeedControlCallback();

            // 先绑定 TextureView；真正创建 Surface 和播放线程要等 SurfaceTexture 可用。
            recreateView(view);
        }

        /**
         * 将 VideoBlob 绑定到当前 Activity 的 TextureView。
         *
         * <p>该方法有两种使用场景：</p>
         * <ul>
         *   <li>首次创建 VideoBlob：设置监听器，等待 TextureView 提供 SurfaceTexture；</li>
         *   <li>Activity 重建后：设置新监听器，并把之前保存的 SurfaceTexture 重新交给新 View。</li>
         * </ul>
         */
        public void recreateView(TextureView view) {
            Log.d(LTAG, "recreateView: " + view);
            mTextureView = view;
            // 回调通常在 UI 线程触发；播放线程则通过 Surface 接收解码输出。
            mTextureView.setSurfaceTextureListener(this);
            if (mSavedSurfaceTexture != null) {
                Log.d(LTAG, "using saved st=" + mSavedSurfaceTexture);
                // 复用旧 SurfaceTexture，让已有 Surface/MediaCodec 输出链路继续工作。
                view.setSurfaceTexture(mSavedSurfaceTexture);
            }
        }

        /**
         * 请求停止播放并解除 SurfaceTexture 的保留状态。
         *
         * <p>此方法只发送停止请求，不等待 PlayMovieThread 结束。mSavedSurfaceTexture 置空
         * 还承担一个生命周期信号的作用：后续 onSurfaceTextureDestroyed() 返回 true，允许
         * TextureView 真正释放 SurfaceTexture。</p>
         */
        public void stopPlayback() {
            Log.d(LTAG, "stopPlayback");
            // MoviePlayer 的停止标志由播放线程检查；这里不直接 stop Thread，因为强制终止
            // Java 线程可能导致 MediaCodec 和 Surface 资源处于不一致状态。
            mPlayThread.requestStop();
            // TODO: wait for the playback thread to stop so we don't kill the Surface
            //       before the video stops

            // 页面真的结束后不再需要该 SurfaceTexture。置空同时通知
            // onSurfaceTextureDestroyed()：这次可以让 TextureView 释放它。
            mSavedSurfaceTexture = null;
        }

        /**
         * TextureView 的 SurfaceTexture 已经可用。
         *
         * <p>第一次回调时，使用 TextureView 提供的 SurfaceTexture 创建 Surface，并启动
         * 播放线程。旋转重建后，如果 mSavedSurfaceTexture 非空，说明旧播放线程仍在运行，
         * 新回调不应再创建第二个播放器。</p>
         */
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            Log.d(LTAG, "onSurfaceTextureAvailable size=" + width + "x" + height + ", st=" + st);

            if (mSavedSurfaceTexture == null) {
                // 首次进入：保存 TextureView 提供的 SurfaceTexture，后续旋转时继续复用它。
                mSavedSurfaceTexture = st;

                // ContentManager 根据标识找到视频文件；Surface(st) 将 SurfaceTexture 包装为
                // MediaCodec 可以写入的 Surface。PlayMovieThread 构造时会立即启动线程。
                File sliders = ContentManager.getInstance().getPath(mMovieTag);
                mPlayThread = new PlayMovieThread(sliders, new Surface(st), mCallback);
            } else {
                // 旋转重建路径：已有 SurfaceTexture 和播放线程，不要重复创建播放器。
                // Can't do it here in Android <= 4.4.  The TextureView doesn't add a
                // listener on the new SurfaceTexture, so it never sees any updates.
                // Needs to happen from activity onCreate() -- see recreateView().
                //Log.d(LTAG, "using saved st=" + mSavedSurfaceTexture);
                //mTextureView.setSurfaceTexture(mSavedSurfaceTexture);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            // 只改变 View 的显示尺寸，不改变解码器和 Surface 的所有权；当前示例只记录日志。
            Log.d(LTAG, "onSurfaceTextureSizeChanged size=" + width + "x" + height + ", st=" + st);
        }

        /**
         * TextureView 即将销毁其 SurfaceTexture 时调用。
         *
         * <p>回调发生时，TextureView 已经把 SurfaceTexture 与当前 EGL 上下文解除关联，
         * 因此本例不需要额外 detach。返回值决定 TextureView 是否继续释放对象：</p>
         * <ul>
         *   <li>返回 {@code false}：Activity 只是重建，VideoBlob 仍持有它，保留以便新 View 复用；</li>
         *   <li>返回 {@code true}：播放已结束，VideoBlob 不再需要它，允许框架释放。</li>
         * </ul>
         */
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            Log.d(LTAG, "onSurfaceTextureDestroyed st=" + st);
            // mSavedSurfaceTexture 非空表示要跨 Activity 重建保留；为空表示正在真正关闭。
            return (mSavedSurfaceTexture == null);
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            // 每当 TextureView 收到新帧都会回调这里；视频已经由 MediaCodec 直接输出，
            // Activity 不需要逐帧读取或绘制，因此保持空实现。
            //Log.d(TAG, "onSurfaceTextureUpdated st=" + st);
        }
    }

    /**
     * 将一个视频文件循环播放到一个 Surface 的后台线程。
     *
     * <p>每个 VideoBlob 都有一个独立的 PlayMovieThread。线程中创建 MoviePlayer、开启循环
     * 模式并调用阻塞式 {@link MoviePlayer#play()}；因此媒体读取和解码不会阻塞 UI 线程。</p>
     *
     * <p>当前线程对象在构造函数中立即启动，并且拥有传入的 Surface：播放结束时由线程释放
     * 该 Surface。调用者只负责提供有效的 Surface，不应在播放线程结束前自行释放它。</p>
     */
    private static class PlayMovieThread extends Thread {
        // 播放线程需要读取的媒体文件。
        private final File mFile;
        // MediaCodec 的输出目标；由本线程在 finally 中释放。
        private final Surface mSurface;
        // 控制原始帧率的回调，与 MoviePlayer 一起运行在本线程。
        private final SpeedControlCallback mCallback;
        // 在线程启动后异步创建；requestStop() 依赖它已经完成初始化。
        private MoviePlayer mMoviePlayer;

        /**
         * 创建并立即启动播放线程。
         * <p>
         * 本对象接管 Surface 的释放责任，并在新线程中访问它。播放结束后，无论正常结束还是
         * 出现 IOException，finally 都会释放 Surface。
         *
         * @param file 要播放的视频文件。
         * @param surface MediaCodec 输出目标；本线程负责释放。
         * @param callback 帧速率控制回调。
         */
        public PlayMovieThread(File file, Surface surface, SpeedControlCallback callback) {
            mFile = file;
            mSurface = surface;
            mCallback = callback;

            // Thread.start() 会异步调用 run()；构造函数返回后播放线程开始执行。
            start();
        }

        /**
         * 请求 MoviePlayer 停止播放，但不等待线程退出。
         *
         * <p>应从 UI 线程调用；MoviePlayer 会在解码循环中检查停止标志，退出后执行 finally
         * 释放 Surface。由于 mMoviePlayer 在后台线程中创建，调用时必须确保它已经初始化。</p>
         */
        public void requestStop() {
            mMoviePlayer.requestStop();
        }

        @Override
        public void run() {
            try {
                // 解码器必须在播放线程中创建和使用；MoviePlayer.play() 会阻塞直到视频结束
                // 或收到停止请求。
                mMoviePlayer = new MoviePlayer(mFile, mSurface, mCallback);
                // 两路视频都持续循环，直到 Activity 真正结束并发送停止请求。
                mMoviePlayer.setLoopMode(true);
                mMoviePlayer.play();
            } catch (IOException ioe) {
                Log.e(TAG, "movie playback failed", ioe);
            } finally {
                // 当前线程拥有 mSurface，因此在播放结束后释放它，断开对 SurfaceTexture 的引用。
                mSurface.release();
                Log.d(TAG, "PlayMovieThread stopping");
            }
        }
    }
}
