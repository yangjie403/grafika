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

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.Rect;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FlatShadedProgram;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * 本类是一个“OpenGL ES 屏幕显示 + 视频录制”的完整示例。
 *
 * <p>录制内容只有 SurfaceView 中的 GL 画面，不包含 Activity 的按钮、状态栏、导航栏或
 * 弹窗。当前类负责 EGL/GL 绘制以及把图像提交到编码器输入 Surface；
 * {@link TextureMovieEncoder2} 在独立线程中排空 MediaCodec 输出，
 * {@link VideoEncoderCore} 再使用 MediaMuxer 将编码数据封装成 MP4。</p>
 *
 * <p>本类故意使用普通 SurfaceView，而不是 GLSurfaceView，这样可以自行控制 EGL 配置、
 * EGLSurface 切换、渲染线程和资源释放顺序。EGL 会尝试创建 GLES 3 上下文；如果设备
 * 支持 GLES 3，就可以使用 glBlitFramebuffer()，把屏幕 framebuffer 的内容复制到编码器
 * Surface，减少一次重新绘制。</p>
 *
 * <p>Choreographer 在 UI 线程上提供接近 VSYNC 的回调，渲染线程只接收时间戳并执行真正
 * 的绘制。这样动画与显示刷新节奏同步，同时不会把 EGL、GL 和编码输入等较重工作放在
 * UI 线程上。本例没有让渲染线程直接接收 Choreographer 回调，而是由 UI 线程接收后
 * 通过 Handler 转发，以避免某些平台实现产生持久 JNI 引用、延长 Activity 生命周期。</p>
 *
 * <p>录制路径有三种：重复绘制、FBO 离屏纹理，以及 GLES 3 的 framebuffer blit。三者
 * 都使用同一个 EGLContext：FBO 不能像普通纹理那样简单地跨上下文共享，而同一上下文
 * 还可以直接在屏幕 framebuffer 与编码器 EGLSurface 之间切换。</p>
 *
 * <p>视频编码通常由硬件 H.264 编码器完成，CPU 主要承担 MediaCodec 输出排空和
 * MediaMuxer 磁盘写入。因此编码线程只负责消费输出，渲染线程只通过输入 Surface 提交
 * 图像，并通过 frameAvailableSoon() 提醒编码线程排空，从而降低编码器背压阻塞
 * eglSwapBuffers() 的概率。</p>
 *
 * <p>学习本类时可以把一帧理解为：VSYNC 时间戳到达 → 更新动画 → 判断是否丢帧 → 绘制
 * 屏幕 → 必要时把同一画面复制/绘制到编码器 → 设置 presentation timestamp → swapBuffers。
 * Surface 销毁时则反向执行：停止消息输入 → 停止编码 → 删除 GL 资源 → 释放 EGL。</p>
 *
 * <p>下面保留了原项目的英文说明，便于对照 Android Grafika 原始实现。</p>
 *
 * Demonstrates efficient display + recording of OpenGL rendering using an FBO.  This
 * records only the GL surface (i.e. not the app UI, nav bar, status bar, or alert dialog).
 * <p>
 * This uses a plain SurfaceView, rather than GLSurfaceView, so we have full control
 * over the EGL config and rendering.  When available, we use GLES 3, which allows us
 * to do recording with one extra copy instead of two.
 * <p>
 * We use Choreographer so our animation matches vsync, and a separate rendering
 * thread to keep the heavy lifting off of the UI thread.  Ideally we'd let the render
 * thread receive the Choreographer events directly, but that appears to be creating
 * a permanent JNI global reference to the render thread object, preventing it from
 * being garbage collected (which, in turn, causes the Activity to be retained).  So
 * instead we receive the vsync on the UI thread and forward it.
 * <p>
 * If the rendering is fairly simple, it may be more efficient to just render the scene
 * twice (i.e. configure for display, call draw(), configure for video, call draw()).  If
 * the video being created is at a lower resolution than the display, rendering at the lower
 * resolution may produce better-looking results than a downscaling blit.
 * <p>
 * To reduce the impact of recording on rendering (which is probably a fancy-looking game),
 * we want to perform the recording tasks on a separate thread.  The actual video encoding
 * is performed in a separate process by the hardware H.264 encoder, so feeding input into
 * the encoder requires little effort.  The MediaMuxer step runs on the CPU and performs
 * disk I/O, so we really want to drain the encoder on a separate thread.
 * <p>
 * Some other examples use a pair of EGL contexts, configured to share state.  We don't want
 * to do that here, because GLES3 allows us to improve performance by using glBlitFramebuffer(),
 * and framebuffer objects aren't shared.  So we use a single EGL context for rendering to
 * both the display and the video encoder.
 * <p>
 * It might appear that shifting the rendering for the encoder input to a different thread
 * would be advantageous, but in practice all of the work is done by the GPU, and submitting
 * the requests from different CPU cores isn't going to matter.
 * <p>
 * As always, we have to be careful about sharing state across threads.  By fully configuring
 * the encoder before starting the encoder thread, we ensure that the new thread sees a
 * fully-constructed object.  The encoder object then "lives" in the encoder thread.  The main
 * thread doesn't need to talk to it directly, because all of the input goes through Surface.
 * <p>
 * TODO: add another bouncing rect that uses decoded video as a texture.  Useful for
 * evaluating simultaneous video playback and recording.
 * <p>
 * TODO: show the MP4 file name somewhere in the UI so people can find it in the player
 */
public class RecordFBOActivity extends Activity implements SurfaceHolder.Callback,
        Choreographer.FrameCallback {
    private static final String TAG = MainActivity.TAG;

    // See the (lengthy) notes at the top of HardwareScalerActivity for thoughts about
    // Activity / Surface lifecycle management.

    /** 直接绘制两次：一次绘制到屏幕，一次绘制到编码器输入 Surface。 */
    private static final int RECMETHOD_DRAW_TWICE = 0;
    /** 默认策略：先绘制到离屏 FBO，再将颜色纹理绘制到两个输出。 */
    private static final int RECMETHOD_FBO = 1;
    /** GLES 3 策略：先绘制到屏幕 framebuffer，再调用 glBlitFramebuffer() 复制。 */
    private static final int RECMETHOD_BLIT_FRAMEBUFFER = 2;

    /** Activity 层的录制开关状态，同时用于更新按钮和状态文本。 */
    private boolean mRecordingEnabled = false;
    /** 当前 EGLContext 是否为 GLES 3；只有 GLES 3 才能使用 framebuffer blit。 */
    private boolean mBlitFramebufferAllowed = false;
    /** 用户在单选按钮中选择的录制策略。 */
    private int mSelectedRecordMethod;

    /** 持有 EGL/GL 状态的渲染线程；Surface 存在时创建，Surface 销毁时回收。 */
    private RenderThread mRenderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record_fbo);

        // FBO 是默认策略。此时还不知道设备是否支持 GLES 3，稍后由渲染线程回传版本。
        mSelectedRecordMethod = RECMETHOD_FBO;
        updateControls();

        // SurfaceView 的 Surface 是实际的屏幕绘制目标；回调会在 Surface 创建、尺寸变化和
        // 销毁时通知 Activity。GL 资源不能在 Surface 销毁后继续使用。
        SurfaceView sv = (SurfaceView) findViewById(R.id.fboActivity_surfaceView);
        sv.getHolder().addCallback(this);

        Log.d(TAG, "RecordFBOActivity: onCreate done");
    }

    @Override
    protected void onPause() {
        super.onPause();

        // TODO: we might want to stop recording here.  As it is, we continue "recording",
        //       which is pretty boring since we're not outputting any frames (test this
        //       by blanking the screen with the power button).

        // 移除已注册的 VSYNC 回调，停止继续向渲染线程投递帧消息。这里没有自动停止编码，
        // 所以暂停期间只是“不再提交新帧”，已有编码线程仍可能继续等待或收尾。
        Log.d(TAG, "onPause unhooking choreographer");
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 如果 Surface 尚未重建，渲染线程仍然存在，只需重新开始接收 VSYNC 通知。
        if (mRenderThread != null) {
            Log.d(TAG, "onResume re-hooking choreographer");
            Choreographer.getInstance().postFrameCallback(this);
        }

        updateControls();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder);

        // 输出文件放到应用私有目录。Surface 创建后才启动渲染线程，避免线程在无效 Surface
        // 上初始化 EGL window surface。
        File outputFile = new File(getFilesDir(), "fbo-gl-recording.mp4");
        SurfaceView sv = (SurfaceView) findViewById(R.id.fboActivity_surfaceView);
        mRenderThread = new RenderThread(sv.getHolder(), new ActivityHandler(this), outputFile,
                MiscUtils.getDisplayRefreshNsec(this));
        mRenderThread.setName("RecordFBO GL render");
        mRenderThread.start();
        // run() 会创建 Looper、Handler 和 EGLCore；等待 ready 后才能安全投递消息。
        mRenderThread.waitUntilReady();
        mRenderThread.setRecordMethod(mSelectedRecordMethod);

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceCreated();
        }

        // 从下一次 VSYNC 开始驱动动画和绘制。
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);
        // SurfaceHolder 回调运行在 UI 线程，不能直接调用渲染线程中的 GL API；通过 Handler
        // 转发尺寸，使 viewport、投影矩阵和 FBO 都在拥有 EGLContext 的线程中更新。
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceChanged(format, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // 必须等待渲染线程退出，避免 Surface 已销毁而渲染线程仍在绘制或交换 buffer。
        // onPause() 通常已经移除了 VSYNC 回调，但仍可能有一个帧消息正在执行。
        //
        // TODO: the RenderThread doesn't currently wait for the encoder / muxer to stop,
        //       so we can't use this as an indication that the .mp4 file is complete.

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendShutdown();
            try {
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // not expected
                throw new RuntimeException("join was interrupted", ie);
            }
        }
        mRenderThread = null;
        mRecordingEnabled = false;

        // 再次移除回调，防止 Surface 销毁后又收到一次 doFrame()。
        Choreographer.getInstance().removeFrameCallback(this);
        Log.d(TAG, "surfaceDestroyed complete");
    }

    /**
     * Choreographer 在接近 VSYNC 时调用的 UI 线程回调。
     *
     * <p>这里不直接绘制，只把纳秒时间戳转发给渲染线程，并立即注册下一次回调。
     * 时间戳使用系统单调时钟，渲染线程可以用它计算动画时间和帧耗时。</p>
     *
     * @see android.view.Choreographer.FrameCallback#doFrame(long)
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 先注册下一次回调，再投递当前帧，形成持续的 VSYNC 驱动循环。
            Choreographer.getInstance().postFrameCallback(this);
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    /**
     * Updates the GLES version string.
     * <p>
     * Called from the render thread (via ActivityHandler) after the EGL context is created.
     */
    void handleShowGlesVersion(int version) {
        // 该方法由 ActivityHandler 回到 UI 线程执行，不能从渲染线程直接改 View。
        TextView tv = (TextView) findViewById(R.id.glesVersionValue_text);
        tv.setText("" + version);
        if (version >= 3) {
            mBlitFramebufferAllowed = true;
            updateControls();
        }
    }

    /**
     * Updates the FPS counter.
     * <p>
     * Called periodically from the render thread (via ActivityHandler).
     */
    void handleUpdateFps(int tfps, int dropped) {
        // tfps 为“FPS × 1000”，这样可以绕过 Message 只能传 int 的限制保留小数。
        String str = getString(R.string.frameRateFormat, tfps / 1000.0f, dropped);
        TextView tv = (TextView) findViewById(R.id.frameRateValue_text);
        tv.setText(str);
    }

    /**
     * onClick handler for "record" button.
     * <p>
     * Ideally we'd grey out the button while in a state of transition, e.g. while the
     * MediaMuxer finishes creating the file, and in the (very brief) period before the
     * SurfaceView's surface is created.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        Log.d(TAG, "clickToggleRecording");
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            // 先更新 UI，再把状态消息交给渲染线程。真正创建/销毁编码器必须在 GL 线程完成，
            // 因为编码器输入 Surface 要和该线程的 EGLContext 配合使用。
            mRecordingEnabled = !mRecordingEnabled;
            updateControls();
            rh.setRecordingEnabled(mRecordingEnabled);
        }
    }

    /**
     * onClick handler for radio buttons.
     */
    public void onRadioButtonClicked(View view) {
        RadioButton rb = (RadioButton) view;
        if (!rb.isChecked()) {
            Log.d(TAG, "Got click on non-checked radio button");
            return;
        }

        // 单选按钮只改变渲染策略，不会立刻重建编码器；下一帧会读取新的策略。
        int id = rb.getId();
        if (id == R.id.recDrawTwice_radio) {
            mSelectedRecordMethod = RECMETHOD_DRAW_TWICE;
        } else if (id == R.id.recFbo_radio) {
            mSelectedRecordMethod = RECMETHOD_FBO;
        } else if (id == R.id.recFramebuffer_radio) {
            mSelectedRecordMethod = RECMETHOD_BLIT_FRAMEBUFFER;
        } else {
            throw new RuntimeException("Click from unknown id " + id);
        }

        Log.d(TAG, "Selected rec mode " + mSelectedRecordMethod);
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.setRecordMethod(mSelectedRecordMethod);
        }
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    private void updateControls() {
        // 所有控件状态集中在这里更新，避免生命周期回调、GLES 版本回调和点击回调各自维护
        // 一套不一致的 UI 状态。
        Button toggleRelease = (Button) findViewById(R.id.fboRecord_button);
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRelease.setText(id);

        RadioButton rb;
        rb = (RadioButton) findViewById(R.id.recDrawTwice_radio);
        rb.setChecked(mSelectedRecordMethod == RECMETHOD_DRAW_TWICE);
        rb = (RadioButton) findViewById(R.id.recFbo_radio);
        rb.setChecked(mSelectedRecordMethod == RECMETHOD_FBO);
        rb = (RadioButton) findViewById(R.id.recFramebuffer_radio);
        rb.setChecked(mSelectedRecordMethod == RECMETHOD_BLIT_FRAMEBUFFER);
        rb.setEnabled(mBlitFramebufferAllowed);

        TextView tv = (TextView) findViewById(R.id.nowRecording_text);
        if (mRecordingEnabled) {
            tv.setText(getString(R.string.nowRecording));
        } else {
            tv.setText("");
        }
    }


    /**
     * Handles messages sent from the render thread to the UI thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.
     */
    static class ActivityHandler extends Handler {
        private static final int MSG_GLES_VERSION = 0;
        private static final int MSG_UPDATE_FPS = 1;

        // 使用弱引用避免消息队列中的 Handler 消息反向持有 Activity，降低生命周期泄漏风险。
        // handleMessage() 运行在 UI 线程，因此只有在那里访问 Activity。
        private WeakReference<RecordFBOActivity> mWeakActivity;

        public ActivityHandler(RecordFBOActivity activity) {
            mWeakActivity = new WeakReference<RecordFBOActivity>(activity);
        }

        /**
         * Send the GLES version.
         * <p>
         * Call from non-UI thread.
         */
        public void sendGlesVersion(int version) {
            // sendMessage() 可以从渲染线程调用，消息最终由 UI 线程的 Looper 处理。
            sendMessage(obtainMessage(MSG_GLES_VERSION, version, 0));
        }

        /**
         * Send an FPS update.  "fps" should be in thousands of frames per second
         * (i.e. fps * 1000), so we can get fractional fps even though the Handler only
         * supports passing integers.
         * <p>
         * Call from non-UI thread.
         */
        public void sendFpsUpdate(int tfps, int dropped) {
            // arg1 保存放大 1000 倍的 FPS，arg2 保存丢帧数。
            sendMessage(obtainMessage(MSG_UPDATE_FPS, tfps, dropped));
        }

        @Override  // runs on UI thread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "ActivityHandler [" + this + "]: what=" + what);

            RecordFBOActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "ActivityHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_GLES_VERSION:
                    activity.handleShowGlesVersion(msg.arg1);
                    break;
                case MSG_UPDATE_FPS:
                    activity.handleUpdateFps(msg.arg1, msg.arg2);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }


    /**
     * 负责所有 EGL、OpenGL ES 绘制以及编码器输入 Surface 的提交。
     *
     * <p>该线程拥有唯一的 EGLContext。UI 线程不直接调用 GL API，只通过
     * {@link RenderHandler} 投递消息；这样可以保证所有 GL 对象都在拥有正确 EGLContext 的
     * 线程中创建、使用和销毁。线程内部还创建 WindowSurface，分别包装屏幕 Surface 和
     * MediaCodec 输入 Surface，并在两者之间切换当前 EGLSurface。</p>
     *
     * <p>VSYNC 回调每次只产生一个帧时间戳，但本线程可能因为 CPU/GPU 或编码器背压来不及
     * 完成上一帧。doFrame() 会在开始绘制前估算剩余时间，来不及完成时主动丢帧，避免越积
     * 越多地落后于显示节奏。</p>
     *
     * <p>必须在 Surface 创建后启动本线程。Surface 是 EGL window surface 的底层 native
     * 目标；如果过早创建 WindowSurface，可能得到无效的 EGLSurface 或在后续尺寸变化时
     * 使用错误的窗口状态。</p>
     */
    private static class RenderThread extends Thread {
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        /** 绑定到本线程 Looper 的消息处理器；volatile 让 UI 线程及时看到它。 */
        private volatile RenderHandler mHandler;

        /** 向 UI 线程发送 GLES 版本、FPS 和丢帧统计的 Handler。 */
        private ActivityHandler mActivityHandler;

        /** 用于同步线程初始化完成的锁对象。 */
        private Object mStartLock = new Object();
        /** run() 是否已经创建 Handler/EglCore，可以接收消息。 */
        private boolean mReady = false;

        /** SurfaceHolder 可能由 UI 线程更新，因此使用 volatile 保证引用可见。 */
        private volatile SurfaceHolder mSurfaceHolder;
        /** EGLDisplay、EGLContext 和 EGLConfig 的封装。 */
        private EglCore mEglCore;
        /** 屏幕窗口对应的 EGLSurface。 */
        private WindowSurface mWindowSurface;
        /** 绘制纯色几何图形的 GL program。 */
        private FlatShadedProgram mProgram;

        /** 以像素为单位的正交投影矩阵，左下角为坐标原点。 */
        private float[] mDisplayProjectionMatrix = new float[16];

        /** 三角形和矩形的共享几何数据；Sprite2d 保存各自的位置、缩放和颜色。 */
        private final Drawable2d mTriDrawable = new Drawable2d(Drawable2d.Prefab.TRIANGLE);
        private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);

        /** 一个旋转三角形、一个移动矩形、四个边框矩形和一个录制策略指示块。 */
        private Sprite2d mTri;
        private Sprite2d mRect;
        private Sprite2d mEdges[];
        private Sprite2d mRecordRect;
        /** 移动矩形速度，单位为 viewport 像素/秒。 */
        private float mRectVelX, mRectVelY;
        /** 移动矩形反弹时使用的内边界。 */
        private float mInnerLeft, mInnerTop, mInnerRight, mInnerBottom;

        /** 把离屏纹理绘制到输出 Surface 时使用的单位矩阵。 */
        private final float[] mIdentityMatrix;

        /** 上一次 VSYNC 时间戳，用于计算动画推进的时间差。 */
        private long mPrevTimeNanos;

        /** 屏幕刷新周期，用于判断本帧是否已经来不及绘制。 */
        private long mRefreshPeriodNanos;
        /** FPS 采样窗口的起始时间和已经统计的帧数。 */
        private long mFpsCountStartNanos;
        private int mFpsCountFrame;
        /** 累计丢帧数，以及上一帧是否丢失。 */
        private int mDroppedFrames;
        private boolean mPreviousWasDropped;

        /** FBO 的颜色纹理、framebuffer、深度 renderbuffer 和纹理绘制器。 */
        private int mOffscreenTexture;
        private int mFramebuffer;
        private int mDepthBuffer;
        private FullFrameRect mFullScreen;

        /** 是否录制、输出文件、编码器输入 EGLSurface 等录制状态。 */
        private boolean mRecordingEnabled;
        private File mOutputFile;
        private WindowSurface mInputWindowSurface;
        private TextureMovieEncoder2 mVideoEncoder;
        private int mRecordMethod;
        /** 用于隔帧录制：true 表示上一显示帧已经提交到视频。 */
        private boolean mRecordedPrevious;
        /** 固定 1280x720 输出画面中真正绘制内容的区域，用于留黑边保持比例。 */
        private Rect mVideoRect;


        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         */
        public RenderThread(SurfaceHolder holder, ActivityHandler ahandler, File outputFile,
                long refreshPeriodNs) {
            // holder 的 Surface 此时可能尚未存在；这里只保存引用，真正的 EGLSurface 在
            // surfaceCreated() 消息中创建。刷新周期来自显示设备，用于丢帧判断。
            mSurfaceHolder = holder;
            mActivityHandler = ahandler;
            mOutputFile = outputFile;
            mRefreshPeriodNanos = refreshPeriodNs;

            // mVideoRect 稍后根据窗口比例计算；输出视频固定为 1280x720。
            mVideoRect = new Rect();

            mIdentityMatrix = new float[16];
            Matrix.setIdentityM(mIdentityMatrix, 0);

            // Sprite2d 只保存场景对象的变换和颜色，实际顶点绘制在 draw() 中完成。
            mTri = new Sprite2d(mTriDrawable);
            mRect = new Sprite2d(mRectDrawable);
            mEdges = new Sprite2d[4];
            for (int i = 0; i < mEdges.length; i++) {
                mEdges[i] = new Sprite2d(mRectDrawable);
            }
            mRecordRect = new Sprite2d(mRectDrawable);
        }

        /**
         * Thread entry point.
         * <p>
         * The thread should not be started until the Surface associated with the SurfaceHolder
         * has been created.  That way we don't have to wait for a separate "surface created"
         * message to arrive.
         */
        @Override
        public void run() {
            // Looper/Handler 必须在本线程创建，消息处理才会回到渲染线程；EGLContext 也在
            // 本线程创建，后续所有 GL 调用都由这里串行执行。
            Looper.prepare();
            mHandler = new RenderHandler(this);
            // FLAG_RECORDABLE 让 EGLConfig 适合 MediaCodec 输入 Surface；TRY_GLES3 表示优先
            // GLES 3，若设备不支持则回退到 GLES 2。
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE | EglCore.FLAG_TRY_GLES3);
            synchronized (mStartLock) {
                mReady = true;
                // 通知 UI 线程：Handler 和 EglCore 已经建立，可以开始投递 Surface 消息。
                mStartLock.notify();
            }

            // 阻塞处理 Surface、VSYNC、录制开关等消息；收到 shutdown 后才退出。
            Looper.loop();

            Log.d(TAG, "looper quit");
            // Looper 退出后仍在当前 GL 线程，因此现在释放 GL 对象最安全；之后才释放 EGLCore。
            releaseGl();
            mEglCore.release();

            synchronized (mStartLock) {
                mReady = false;
            }
        }

        /**
         * Waits until the render thread is ready to receive messages.
         * <p>
         * Call from the UI thread.
         */
        public void waitUntilReady() {
            synchronized (mStartLock) {
                while (!mReady) {
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Shuts everything down.
         */
        private void shutdown() {
            Log.d(TAG, "shutdown");
            // 先停止编码器，再退出 Looper；这样停止消息在编码线程中异步收尾，当前 GL 线程
            // 不再提交新的编码帧。
            stopEncoder();
            Looper.myLooper().quit();
        }

        /**
         * Returns the render thread's Handler.  This may be called from any thread.
         */
        public RenderHandler getHandler() {
            // 可能在 run() 初始化前返回 null，调用方必须判空。
            return mHandler;
        }

        /**
         * Prepares the surface.
         */
        private void surfaceCreated() {
            // getSurface() 返回的是 SurfaceView 的 native window，WindowSurface 会把它包装
            // 成当前 EGLContext 可以绘制的 EGLSurface。
            Surface surface = mSurfaceHolder.getSurface();
            prepareGl(surface);
        }

        /**
         * Prepares window surface and GL state.
         */
        private void prepareGl(Surface surface) {
            Log.d(TAG, "prepareGl");

            // false 表示这是屏幕输出，不是 MediaCodec 输入 Surface。
            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mWindowSurface.makeCurrent();

            // 用纹理 program 把离屏颜色纹理铺满当前输出 Surface；FBO 路径会复用它两次。
            mFullScreen = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));

            // FlatShadedProgram 用于绘制三角形、矩形和边框等纯色几何图形。
            mProgram = new FlatShadedProgram();

            // draw() 会根据需要修改 clear color；这里设置一个安全的初始值。
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            // 场景只有 2D 图形，不需要深度测试或背面剔除。
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);

            // Don't need backface culling.  (If you're feeling pedantic, you can turn it on to
            // make sure we're defining our shapes correctly.)
            GLES20.glDisable(GLES20.GL_CULL_FACE);

            // 把实际创建的 GLES 版本回传 UI；UI 据此决定是否允许 framebuffer blit 单选项。
            mActivityHandler.sendGlesVersion(mEglCore.getGlVersion());
        }

       /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing.
         * (Called from RenderHandler.)
         */
        private void surfaceChanged(int width, int height) {
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            // 尺寸改变后旧的离屏附件尺寸也不再匹配，因此先重新创建 width x height 的 FBO，
            // 再设置 viewport 和投影矩阵。
            prepareFramebuffer(width, height);

            // 屏幕绘制使用完整窗口；切到编码器时会临时改为 mVideoRect。
            GLES20.glViewport(0, 0, width, height);

            // 使用像素坐标的正交投影，左下角为 (0, 0)，右上角为 (width, height)。
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

            int smallDim = Math.min(width, height);

            // 根据窗口短边初始化图形大小、位置和速度。投影仍然使用真实像素尺寸，因此在
            // 不同宽高比的设备上，正方形不会被拉伸。
            mTri.setColor(0.1f, 0.9f, 0.1f);
            mTri.setScale(smallDim / 4.0f, smallDim / 4.0f);
            mTri.setPosition(width / 2.0f, height / 2.0f);
            mRect.setColor(0.9f, 0.1f, 0.1f);
            mRect.setScale(smallDim / 8.0f, smallDim / 8.0f);
            mRect.setPosition(width / 2.0f, height / 2.0f);
            mRectVelX = 1 + smallDim / 4.0f;
            mRectVelY = 1 + smallDim / 5.0f;

            // left edge
            float edgeWidth = 1 + width / 64.0f;
            mEdges[0].setScale(edgeWidth, height);
            mEdges[0].setPosition(edgeWidth / 2.0f, height / 2.0f);
            // right edge
            mEdges[1].setScale(edgeWidth, height);
            mEdges[1].setPosition(width - edgeWidth / 2.0f, height / 2.0f);
            // top edge
            mEdges[2].setScale(width, edgeWidth);
            mEdges[2].setPosition(width / 2.0f, height - edgeWidth / 2.0f);
            // bottom edge
            mEdges[3].setScale(width, edgeWidth);
            mEdges[3].setPosition(width / 2.0f, edgeWidth / 2.0f);

            mRecordRect.setColor(1.0f, 1.0f, 1.0f);
            mRecordRect.setScale(edgeWidth * 2f, edgeWidth * 2f);
            mRecordRect.setPosition(edgeWidth / 2.0f, edgeWidth / 2.0f);

            // 移动矩形只在四条边框内部反弹。
            mInnerLeft = mInnerBottom = edgeWidth;
            mInnerRight = width - 1 - edgeWidth;
            mInnerTop = height - 1 - edgeWidth;

            Log.d(TAG, "mTri: " + mTri);
            Log.d(TAG, "mRect: " + mRect);
        }

        /**
         * Prepares the off-screen framebuffer.
         */
        private void prepareFramebuffer(int width, int height) {
            GlUtil.checkGlError("prepareFramebuffer start");

            int[] values = new int[1];

            // 创建颜色纹理。FBO 不直接把颜色存到屏幕，而是把每个像素写入这张纹理，之后
            // 可以在不同输出 Surface 上重复使用同一份渲染结果。
            GLES20.glGenTextures(1, values, 0);
            GlUtil.checkGlError("glGenTextures");
            mOffscreenTexture = values[0];   // expected > 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mOffscreenTexture);
            GlUtil.checkGlError("glBindTexture " + mOffscreenTexture);

            // 分配 width x height 的 RGBA 纹理存储；null 表示只分配存储，不上传初始像素。
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            // 设置采样和边缘模式。线性放大可减少缩放锯齿，CLAMP_TO_EDGE 防止采样越过边界。
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GlUtil.checkGlError("glTexParameter");

            // 创建并绑定 FBO；此后对颜色和深度附件的设置都针对这个 FBO。
            GLES20.glGenFramebuffers(1, values, 0);
            GlUtil.checkGlError("glGenFramebuffers");
            mFramebuffer = values[0];    // expected > 0
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
            GlUtil.checkGlError("glBindFramebuffer " + mFramebuffer);

            // 创建深度 renderbuffer。虽然当前二维场景关闭了深度测试，但保留深度附件使
            // FBO 结构完整，也便于扩展为需要深度的绘制。
            GLES20.glGenRenderbuffers(1, values, 0);
            GlUtil.checkGlError("glGenRenderbuffers");
            mDepthBuffer = values[0];    // expected > 0
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glBindRenderbuffer " + mDepthBuffer);

            // 为深度附件分配与窗口同尺寸的 16 位深度存储。
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                    width, height);
            GlUtil.checkGlError("glRenderbufferStorage");

            // 把深度 renderbuffer 和颜色纹理挂到 FBO 上；gl_FragColor 会写入颜色纹理。
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                    GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glFramebufferRenderbuffer");
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, mOffscreenTexture, 0);
            GlUtil.checkGlError("glFramebufferTexture2D");

            // 检查颜色、深度附件尺寸和格式是否满足 FBO 完整性要求。
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Framebuffer not complete, status=" + status);
            }

            // 恢复默认 framebuffer 0。后续屏幕绘制应写入窗口 Surface，而不是继续写入离屏 FBO。
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            GlUtil.checkGlError("prepareFramebuffer done");
        }

        /**
         * Releases most of the GL resources we currently hold.
         * <p>
         * Does not release EglCore.
         */
        private void releaseGl() {
            GlUtil.checkGlError("releaseGl start");

            int[] values = new int[1];

            // releaseGl() 在渲染线程、EGLContext 仍可用时执行。先释放依赖 EGLSurface/GL
            // context 的对象，最后由 run() 再释放 EglCore。
            if (mWindowSurface != null) {
                mWindowSurface.release();
                mWindowSurface = null;
            }
            if (mProgram != null) {
                mProgram.release();
                mProgram = null;
            }
            if (mOffscreenTexture > 0) {
                values[0] = mOffscreenTexture;
                GLES20.glDeleteTextures(1, values, 0);
                mOffscreenTexture = -1;
            }
            if (mFramebuffer > 0) {
                values[0] = mFramebuffer;
                GLES20.glDeleteFramebuffers(1, values, 0);
                mFramebuffer = -1;
            }
            if (mDepthBuffer > 0) {
                values[0] = mDepthBuffer;
                GLES20.glDeleteRenderbuffers(1, values, 0);
                mDepthBuffer = -1;
            }
            if (mFullScreen != null) {
                // FullFrameRect 内部包含纹理绘制 program。这里的 false 是原实现行为；释放
                // 完成后 makeNothingCurrent()，避免 EGLContext 继续绑定已销毁 Surface。
                mFullScreen.release(false); // TODO: should be "true"; must ensure mEglCore current
                mFullScreen = null;
            }

            GlUtil.checkGlError("releaseGl done");

            mEglCore.makeNothingCurrent();
        }

        /**
         * Updates the recording state.  Stops or starts recording as needed.
         */
        private void setRecordingEnabled(boolean enabled) {
            if (enabled == mRecordingEnabled) {
                return;
            }
            // 编码器的创建、输入 EGLSurface 的包装和停止都在渲染线程串行完成，避免同一
            // EGLContext 同时被多个线程操作。
            if (enabled) {
                startEncoder();
            } else {
                stopEncoder();
            }
            mRecordingEnabled = enabled;
        }

        /**
         * Changes the method we use to render frames to the encoder.
         */
        private void setRecordMethod(int recordMethod) {
            Log.d(TAG, "RT: setRecordMethod " + recordMethod);
            // 只切换下一次 doFrame() 采用的分支，不需要重新创建 FBO 或编码器。
            mRecordMethod = recordMethod;
        }

        /**
         * Creates the video encoder object and starts the encoder thread.  Creates an EGL
         * surface for encoder input.
         */
        private void startEncoder() {
            Log.d(TAG, "starting to record");
            // 统一输出 1280x720，避免把设备窗口的任意尺寸直接交给编码器。某些硬件编码器
            // 对非典型尺寸、尤其是非 16 对齐尺寸支持不好，因此通过黑边保持画面比例。
            final int BIT_RATE = 4000000;   // 4Mbps
            final int VIDEO_WIDTH = 1280;
            final int VIDEO_HEIGHT = 720;
            int windowWidth = mWindowSurface.getWidth();
            int windowHeight = mWindowSurface.getHeight();
            float windowAspect = (float) windowHeight / (float) windowWidth;
            int outWidth, outHeight;
            if (VIDEO_HEIGHT > VIDEO_WIDTH * windowAspect) {
                // limited by narrow width; reduce height
                outWidth = VIDEO_WIDTH;
                outHeight = (int) (VIDEO_WIDTH * windowAspect);
            } else {
                // limited by short height; restrict width
                outHeight = VIDEO_HEIGHT;
                outWidth = (int) (VIDEO_HEIGHT / windowAspect);
            }
            int offX = (VIDEO_WIDTH - outWidth) / 2;
            int offY = (VIDEO_HEIGHT - outHeight) / 2;
            // mVideoRect 是视频坐标中的有效画面区域；区域以外保持黑色，形成 pillarbox 或
            // letterbox。注意 OpenGL 的 viewport 坐标原点在左下角。
            mVideoRect.set(offX, offY, offX + outWidth, offY + outHeight);
            Log.d(TAG, "Adjusting window " + windowWidth + "x" + windowHeight +
                    " to +" + offX + ",+" + offY + " " +
                    mVideoRect.width() + "x" + mVideoRect.height());

            // 先创建 MediaCodec 输入 Surface，再用同一个 EglCore 包装它；之后 doFrame() 会
            // 在屏幕 EGLSurface 和该输入 EGLSurface 之间切换。
            VideoEncoderCore encoderCore;
            try {
                encoderCore = new VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT,
                        BIT_RATE, mOutputFile);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            mInputWindowSurface = new WindowSurface(mEglCore, encoderCore.getInputSurface(), true);
            mVideoEncoder = new TextureMovieEncoder2(encoderCore);
        }

        /**
         * Stops the video encoder if it's running.
         */
        private void stopEncoder() {
            if (mVideoEncoder != null) {
                Log.d(TAG, "stopping recorder, mVideoEncoder=" + mVideoEncoder);
                // stopRecording() 只是向编码线程发送 EOS/停止消息，通常会异步返回；因此
                // 这里不能把它当作 MP4 已经完成写入的同步屏障。
                mVideoEncoder.stopRecording();
                // TODO: wait (briefly) until it finishes shutting down so we know file is
                //       complete, or have a callback that updates the UI
                mVideoEncoder = null;
            }
            if (mInputWindowSurface != null) {
                // EGLSurface 只依赖编码器输入 Surface；释放它不会释放共享的 EglCore。
                mInputWindowSurface.release();
                mInputWindowSurface = null;
            }
        }

        /**
         * Advance state and draw frame in response to a vsync event.
         */
        private void doFrame(long timeStampNanos) {
            // 这条消息来自 UI 线程的 Choreographer，但下面的全部工作在渲染线程执行。
            // 一个 timeStamp 对应一次显示节奏；录制时仍以同一个时间戳提交视频 PTS。
            // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
            // recording is too expensive, maybe the CPU frequency governor thinks we're
            // not doing and wants to drop the clock frequencies -- we need to drop frames
            // to catch up.  The "timeStampNanos" value is based on the system monotonic
            // clock, as is System.nanoTime(), so we can compare the values directly.
            //
            // Our clumsy collision detection isn't sophisticated enough to deal with large
            // time gaps, but it's nearly cost-free, so we go ahead and do the computation
            // either way.
            //
            // We can reduce the overhead of recording, as well as the size of the movie,
            // by recording at ~30fps instead of the display refresh rate.  As a quick hack
            // we just record every-other frame, using a "recorded previous" flag.

            update(timeStampNanos);

            // timeStampNanos 和 System.nanoTime() 使用同一个单调时钟。若从收到 VSYNC 到
            // 当前时刻已经接近下一个刷新周期，继续绘制只会让画面越来越滞后，因此主动丢掉
            // 本帧；动画状态已经更新，下一帧会从最新状态继续。
            long diff = System.nanoTime() - timeStampNanos;
            long max = mRefreshPeriodNanos - 2000000;   // if we're within 2ms, don't bother
            if (diff > max) {
                // too much, drop a frame
                Log.d(TAG, "diff is " + (diff / 1000000.0) + " ms, max " + (max / 1000000.0) +
                        ", skipping render");
                mRecordedPrevious = false;
                mPreviousWasDropped = true;
                mDroppedFrames++;
                return;
            }

            boolean swapResult;

            if (!mRecordingEnabled || mRecordedPrevious) {
                // 未录制时每个 VSYNC 都绘制屏幕；录制时 mRecordedPrevious=true 表示上一
                // 次已经提交视频，所以当前帧只更新屏幕，从而大约每两个显示帧录制一帧。
                mRecordedPrevious = false;
                // Render the scene, swap back to front.
                draw();
                swapResult = mWindowSurface.swapBuffers();
            } else {
                mRecordedPrevious = true;

                // recording
                if (mRecordMethod == RECMETHOD_DRAW_TWICE) {
                    // 策略一：屏幕和编码器各绘制一次。优点是视频可以直接按目标尺寸绘制，
                    // 缺点是场景顶点/片元工作执行两遍。
                    //Log.d(TAG, "MODE: draw 2x");

                    // 先绘制并提交屏幕帧；此时当前 EGLSurface 是 mWindowSurface。
                    draw();
                    swapResult = mWindowSurface.swapBuffers();

                    // 通知编码线程尽快排空旧输出，防止 MediaCodec 输出队列造成背压。
                    mVideoEncoder.frameAvailableSoon();
                    // 切换当前 EGLSurface 到编码器输入 Surface。后面的 GL 绘制不会出现在
                    // 屏幕上，而是作为一帧送入 MediaCodec。
                    mInputWindowSurface.makeCurrent();
                    // If we don't set the scissor rect, the glClear() we use to draw the
                    // light-grey background will draw outside the viewport and muck up our
                    // letterboxing.  Might be better if we disabled the test immediately after
                    // the glClear().  Of course, if we were clearing the frame background to
                    // black it wouldn't matter.
                    //
                    // We do still need to clear the pixels outside the scissor rect, of course,
                    // or we'll get garbage at the edges of the recording.  We can either clear
                    // the whole thing and accept that there will be a lot of overdraw, or we
                    // can issue multiple scissor/clear calls.  Some GPUs may have a special
                    // optimization for zeroing out the color buffer.
                    //
                    // For now, be lazy and zero the whole thing.  At some point we need to
                    // examine the performance here.
                    // 先清成黑色，保证 mVideoRect 外部的 letterbox/pillarbox 没有旧数据。
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    // 只在有效画面区域绘制，并使用 scissor 防止 draw() 的清屏覆盖黑边。
                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                    GLES20.glScissor(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    draw();
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                    // Surface 的 presentation timestamp 会成为视频帧的 PTS，影响播放时序。
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // 恢复屏幕 viewport 和当前 EGLSurface，下一次非录制分支仍能正常绘制屏幕。
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    mWindowSurface.makeCurrent();

                } else if (mEglCore.getGlVersion() >= 3 &&
                        mRecordMethod == RECMETHOD_BLIT_FRAMEBUFFER) {
                    // 策略二：GLES 3 framebuffer blit。先把场景绘制到屏幕 framebuffer，
                    // 再把它复制到编码器 Surface；场景本身只执行一次。
                    //Log.d(TAG, "MODE: blitFramebuffer");
                    // 绘制到屏幕 back buffer，但暂时不 swap，这样内容仍可作为 read framebuffer。
                    draw();

                    mVideoEncoder.frameAvailableSoon();
                    // makeCurrentReadFrom() 将编码器 Surface 设为 draw surface，将屏幕
                    // Surface 设为 read surface；glBlitFramebuffer() 随后从屏幕读取像素。
                    mInputWindowSurface.makeCurrentReadFrom(mWindowSurface);
                    // 先把编码器目标清成黑色，避免 mVideoRect 之外残留上一帧的垃圾像素。
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GlUtil.checkGlError("before glBlitFramebuffer");
                    Log.v(TAG, "glBlitFramebuffer: 0,0," + mWindowSurface.getWidth() + "," +
                            mWindowSurface.getHeight() + "  " + mVideoRect.left + "," +
                            mVideoRect.top + "," + mVideoRect.right + "," + mVideoRect.bottom +
                            "  COLOR_BUFFER GL_NEAREST");
                    // 把屏幕 framebuffer 的完整区域缩放复制到视频有效区域。GL_NEAREST 与
                    // 这里的像素复制语义一致，也避免额外的纹理采样状态。
                    GLES30.glBlitFramebuffer(
                            0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight(),
                            mVideoRect.left, mVideoRect.top, mVideoRect.right, mVideoRect.bottom,
                            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
                    int err;
                    if ((err = GLES30.glGetError()) != GLES30.GL_NO_ERROR) {
                        Log.w(TAG, "ERROR: glBlitFramebuffer failed: 0x" +
                                Integer.toHexString(err));
                    }
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // 编码帧已经提交后，切回屏幕 Surface 并 swap，真正显示刚才绘制的画面。
                    mWindowSurface.makeCurrent();
                    swapResult = mWindowSurface.swapBuffers();

                } else {
                    // 策略三（默认 FBO）：场景只绘制到离屏 FBO 一次，再通过纹理复制到两个
                    // 输出。与 framebuffer blit 不同，这条路径在 GLES 2 也能工作。
                    //Log.d(TAG, "MODE: offscreen + blit 2x");
                    // 绑定离屏 FBO，draw() 的颜色输出会进入 mOffscreenTexture。
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
                    GlUtil.checkGlError("glBindFramebuffer");
                    draw();

                    // 恢复默认 framebuffer，把离屏颜色纹理绘制到屏幕并提交。
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GlUtil.checkGlError("glBindFramebuffer");
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    swapResult = mWindowSurface.swapBuffers();

                    // 再把同一张离屏纹理绘制到编码器输入 Surface；这里不需要再次执行场景
                    // 的三角形/矩形绘制，只需执行一次全屏纹理绘制。
                    mVideoEncoder.frameAvailableSoon();
                    mInputWindowSurface.makeCurrent();
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // 恢复屏幕 viewport 和当前 EGLSurface，保持下一帧的 GL 状态正确。
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    mWindowSurface.makeCurrent();
                }
            }

            // 能走到这里说明本帧没有被 CPU 时间预算丢弃。
            mPreviousWasDropped = false;

            if (!swapResult) {
                // Surface 可能在 Activity 停止时失效；swap 失败后退出渲染线程，避免继续
                // 对无效 native window 提交 GL 命令。
                // This can happen if the Activity stops without waiting for us to halt.
                Log.w(TAG, "swapBuffers failed, killing renderer thread");
                shutdown();
                return;
            }

            // 每 120 个显示帧计算一次 FPS。内部使用“FPS × 1000”的整数，交给 UI 时再还原
            // 小数；丢帧数则从渲染线程累计后一起上报。
            // Update the FPS counter.
            //
            // Ideally we'd generate something approximate quickly to make the UI look
            // reasonable, then ease into longer sampling periods.
            final int NUM_FRAMES = 120;
            final long ONE_TRILLION = 1000000000000L;
            if (mFpsCountStartNanos == 0) {
                mFpsCountStartNanos = timeStampNanos;
                mFpsCountFrame = 0;
            } else {
                mFpsCountFrame++;
                if (mFpsCountFrame == NUM_FRAMES) {
                    // compute thousands of frames per second
                    long elapsed = timeStampNanos - mFpsCountStartNanos;
                    mActivityHandler.sendFpsUpdate((int)(NUM_FRAMES * ONE_TRILLION / elapsed),
                            mDroppedFrames);

                    // reset
                    mFpsCountStartNanos = timeStampNanos;
                    mFpsCountFrame = 0;
                }
            }
        }

        /**
         * We use the time delta from the previous event to determine how far everything
         * moves.  Ideally this will yield identical animation sequences regardless of
         * the device's actual refresh rate.
         */
        private void update(long timeStampNanos) {
            // 动画使用 VSYNC 时间戳而不是“本次方法执行耗时”。这样渲染线程偶尔忙碌时，
            // 物体仍按照真实经过的时间移动，而不是按 CPU 调度次数移动。
            // Compute time from previous frame.
            long intervalNanos;
            if (mPrevTimeNanos == 0) {
                intervalNanos = 0;
            } else {
                intervalNanos = timeStampNanos - mPrevTimeNanos;

                final long ONE_SECOND_NANOS = 1000000000L;
                if (intervalNanos > ONE_SECOND_NANOS) {
                    // Activity 长时间暂停或 Surface 重建时，巨大的 delta 会让物体瞬移出屏幕；
                    // 把它当作第一帧，保持动画从当前位置平滑恢复。
                    // A gap this big should only happen if something paused us.  We can
                    // either cap the delta at one second, or just pretend like this is
                    // the first frame and not advance at all.
                    Log.d(TAG, "Time delta too large: " +
                            (double) intervalNanos / ONE_SECOND_NANOS + " sec");
                    intervalNanos = 0;
                }
            }
            mPrevTimeNanos = timeStampNanos;

            final float ONE_BILLION_F = 1000000000.0f;
            final float elapsedSeconds = intervalNanos / ONE_BILLION_F;

            // 三角形每 3 秒旋转 360 度，即每秒 120 度；角度增量与刷新率无关。
            final int SECS_PER_SPIN = 3;
            float angleDelta = (360.0f / SECS_PER_SPIN) * elapsedSeconds;
            mTri.setRotation(mTri.getRotation() + angleDelta);

            // 矩形按速度移动，碰到内边界就反转速度。碰撞检测是近似的，可能略微越过边框；
            // 由于边框最后绘制，视觉上不明显。
            float xpos = mRect.getPositionX();
            float ypos = mRect.getPositionY();
            float xscale = mRect.getScaleX();
            float yscale = mRect.getScaleY();
            xpos += mRectVelX * elapsedSeconds;
            ypos += mRectVelY * elapsedSeconds;
            if ((mRectVelX < 0 && xpos - xscale/2 < mInnerLeft) ||
                    (mRectVelX > 0 && xpos + xscale/2 > mInnerRight+1)) {
                mRectVelX = -mRectVelX;
            }
            if ((mRectVelY < 0 && ypos - yscale/2 < mInnerBottom) ||
                    (mRectVelY > 0 && ypos + yscale/2 > mInnerTop+1)) {
                mRectVelY = -mRectVelY;
            }
            mRect.setPosition(xpos, ypos);
        }

        /**
         * Draws the scene.
         */
        private void draw() {
            GlUtil.checkGlError("draw start");

            // 使用灰色内容背景，把输出视频中为保持比例而补的黑色区域区分出来。
            // Clear to a non-black color to make the content easily differentiable from
            // the pillar-/letter-boxing.
            GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 先画场景主体，再画边框和左下角录制策略指示块。
            mTri.draw(mProgram, mDisplayProjectionMatrix);
            mRect.draw(mProgram, mDisplayProjectionMatrix);
            for (int i = 0; i < 4; i++) {
                if (false && mPreviousWasDropped) {
                    mEdges[i].setColor(1.0f, 0.0f, 0.0f);
                } else {
                    mEdges[i].setColor(0.5f, 0.5f, 0.5f);
                }
                mEdges[i].draw(mProgram, mDisplayProjectionMatrix);
            }

            // 左下角色块用于观察当前录制路径：红色=重复绘制，绿色=FBO，蓝色=GLES 3 blit。
            switch (mRecordMethod) {
                case RECMETHOD_DRAW_TWICE:
                    mRecordRect.setColor(1.0f, 0.0f, 0.0f);
                    break;
                case RECMETHOD_FBO:
                    mRecordRect.setColor(0.0f, 1.0f, 0.0f);
                    break;
                case RECMETHOD_BLIT_FRAMEBUFFER:
                    mRecordRect.setColor(0.0f, 0.0f, 1.0f);
                    break;
                default:
            }
            mRecordRect.draw(mProgram, mDisplayProjectionMatrix);

            GlUtil.checkGlError("draw done");
        }
    }

    /**
     * 渲染线程的消息处理器：把 UI 线程的生命周期、VSYNC 和控件操作转发给 RenderThread。
     *
     * <p>该对象必须在 RenderThread 中创建，这样 Handler 绑定的是渲染线程自己的 Looper，
     * {@link #handleMessage(Message)} 才会在正确的线程执行。UI 线程只调用 send 方法，
     * 不直接碰 EGL 或 OpenGL 对象。</p>
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_RECORDING_ENABLED = 3;
        private static final int MSG_RECORD_METHOD = 4;
        private static final int MSG_SHUTDOWN = 5;

        // Looper 退出后 Handler 会失效；弱引用仍可避免异常生命周期下消息队列强持有线程。
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            // 构造发生在渲染线程，因此 Handler 自动绑定到渲染线程 Looper。
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface created" message.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceCreated() {
            // SurfaceHolder 的 Surface 已经创建，渲染线程现在可以创建 WindowSurface 和 GL 对象。
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format,
                int width, int height) {
            // format 对本例没有影响；只把宽高通过 arg1/arg2 传给渲染线程。
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p>
         * Call from UI thread.
         */
        public void sendDoFrame(long frameTimeNanos) {
            // Message.arg1/arg2 都是 int，因此把 long 拆成高 32 位和低 32 位传递；接收端
            // 需要用无符号掩码恢复低位，避免符号扩展破坏时间戳。
            sendMessage(obtainMessage(RenderHandler.MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /**
         * Enable or disable recording.
         * <p>
         * Call from non-UI thread.
         */
        public void setRecordingEnabled(boolean enabled) {
            // boolean 用 1/0 编码到 arg1，实际 startEncoder()/stopEncoder() 在渲染线程执行。
            sendMessage(obtainMessage(MSG_RECORDING_ENABLED, enabled ? 1 : 0, 0));
        }

        /**
         * Set the method used to render a frame for the encoder.
         * <p>
         * Call from non-UI thread.
         */
        public void setRecordMethod(int recordMethod) {
            // 录制策略是小整数，直接放入 arg1。
            sendMessage(obtainMessage(MSG_RECORD_METHOD, recordMethod, 0));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendShutdown() {
            // shutdown() 会停止编码器并退出渲染线程 Looper；Activity 随后 join 等待其结束。
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_CREATED:
                    // 所有分支都在 RenderThread 上运行，因此可以安全调用 GL API。
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    // 高低位合并回 Choreographer 的纳秒时间戳。
                    long timestamp = (((long) msg.arg1) << 32) |
                                     (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_RECORDING_ENABLED:
                    renderThread.setRecordingEnabled(msg.arg1 != 0);
                    break;
                case MSG_RECORD_METHOD:
                    renderThread.setRecordMethod(msg.arg1);
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
               default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}
