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

package com.android.grafika.gles;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

/**
 * EGL 的核心状态管理类，负责维护 {@link EGLDisplay}、{@link EGLContext} 和 {@link EGLConfig}。
 *
 * <p>可以把 EGL 理解为“把 OpenGL ES 接到 Android 原生窗口上的桥梁”：
 * {@code EGLDisplay} 表示与系统显示设备的连接，{@code EGLConfig} 描述颜色缓冲区等配置，
 * {@code EGLContext} 保存 OpenGL ES 的对象和状态。创建 EGLSurface 后，调用
 * {@link #makeCurrent(EGLSurface)}，当前线程才能执行针对该 context 的 GL 操作。</p>
 *
 * <p>一个 context 同一时刻只能绑定到一个线程；同一个线程也必须先切换到正确的 context
 * 才能使用该 context 创建的纹理、Shader 和 FBO。因此本类不是线程安全的，通常应由专门的
 * GL 线程独占使用。</p>
 */
public final class EglCore {
    /** 日志标签，统一使用 {@link GlUtil#TAG}。 */
    private static final String TAG = GlUtil.TAG;

    /**
     * 要求 EGLConfig 支持录制。
     *
     * <p>当 EGLSurface 的输出目标是 MediaCodec 编码器的 input Surface 时应设置该标志，
     * 这样 EGL 会优先选择适合视频编码器消费的像素格式，减少额外转换。</p>
     */
    public static final int FLAG_RECORDABLE = 0x01;

    /** 请求创建 GLES 3 context；设备不支持时自动回退到 GLES 2。未设置时直接使用 GLES 2。 */
    public static final int FLAG_TRY_GLES3 = 0x02;

    /** Android 扩展属性：要求创建出的 surface 可被视频编码器直接使用。 */
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    /** EGL 与系统显示设备的连接。 */
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    /** OpenGL ES 的执行上下文，纹理、Shader 等对象都属于某个 context。 */
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    /** 创建 EGLSurface 时使用的像素格式配置。 */
    private EGLConfig mEGLConfig = null;
    /** 实际创建成功的 GLES 客户端版本。 */
    private int mGlVersion = -1;


    /** 创建一个不共享 context、默认使用 GLES 2 的 EGL 核心对象。 */
    public EglCore() {
        this(null, 0);
    }

    /**
     * 初始化 EGLDisplay、选择 EGLConfig 并创建 GLES context。
     *
     * <p>初始化顺序是：取得 display → {@code eglInitialize()} → 选择 config → 尝试创建
     * GLES 3 → 失败时创建 GLES 2。若传入 shared context，纹理和 Shader 等可共享资源可以
     * 在两个 context 之间复用，但每个 context 仍需要在使用前绑定到当前线程。</p>
     *
     * @param sharedContext 要共享资源的已有 context；传 {@code null} 表示不共享
     * @param flags 配置标志，例如 {@link #FLAG_RECORDABLE}、{@link #FLAG_TRY_GLES3}
     */
    public EglCore(EGLContext sharedContext, int flags) {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("EGL already set up");
        }

        if (sharedContext == null) {
            sharedContext = EGL14.EGL_NO_CONTEXT;
        }

        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        // 如果调用方提出请求，先尝试 GLES 3；创建失败不会立即抛异常，而是继续回退到 GLES 2。
        if ((flags & FLAG_TRY_GLES3) != 0) {
            //Log.d(TAG, "正在尝试 GLES 3");
            EGLConfig config = getConfig(flags, 3);
            if (config != null) {
                int[] attrib3_list = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL14.EGL_NONE
                };
                EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, sharedContext,
                        attrib3_list, 0);

                if (EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                    //Log.d(TAG, "已取得 GLES 3 配置");
                    mEGLConfig = config;
                    mEGLContext = context;
                    mGlVersion = 3;
                }
            }
        }
        if (mEGLContext == EGL14.EGL_NO_CONTEXT) {  // 设备仅支持 GLES 2，或 GLES 3 创建失败
            //Log.d(TAG, "正在尝试 GLES 2");
            EGLConfig config = getConfig(flags, 2);
            if (config == null) {
                throw new RuntimeException("Unable to find a suitable EGLConfig");
            }
            int[] attrib2_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, sharedContext,
                    attrib2_list, 0);
            checkEglError("eglCreateContext");
            mEGLConfig = config;
            mEGLContext = context;
            mGlVersion = 2;
        }

        // 通过查询确认驱动最终创建的客户端版本，便于排查设备兼容性问题。
        int[] values = new int[1];
        EGL14.eglQueryContext(mEGLDisplay, mEGLContext, EGL14.EGL_CONTEXT_CLIENT_VERSION,
                values, 0);
        Log.d(TAG, "EGLContext created, client version " + values[0]);
    }

    /**
     * 按颜色通道、可渲染版本和录制能力选择一个 EGLConfig。
     *
     * <p>这里选择 RGBA8888，即每个颜色通道 8 bit。深度和模板缓冲没有开启，因为本项目
     * 的示例主要绘制二维纹理，不需要深度测试或模板测试。调用 {@code eglChooseConfig()}
     * 只返回一个候选配置，简化了示例代码。</p>
     *
     * @param flags 构造函数传入的配置标志
     * @param version 目标 GLES 版本，只应为 2 或 3
     * @return 匹配的配置；找不到时返回 {@code null}
     */
    private EGLConfig getConfig(int flags, int version) {
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;
        if (version >= 3) {
            renderableType |= EGLExt.EGL_OPENGL_ES3_BIT_KHR;
        }

        // 实际 surface 通常是 RGBA 或 RGBX。即使某些输出不需要 alpha，也保留 8 bit alpha，
        // 这样 glReadPixels() 读取到 GL_RGBA 时不容易触发额外的格式转换。
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                //EGL14.EGL_DEPTH_SIZE, 16,       // 如需深度测试可打开
                //EGL14.EGL_STENCIL_SIZE, 8,      // 如需模板测试可打开
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_NONE, 0,      // 为 EGL_RECORDABLE_ANDROID 预留的位置
                EGL14.EGL_NONE
        };
        if ((flags & FLAG_RECORDABLE) != 0) {
            // Android 的 recordable 属性不是标准 EGL14 常量，所以在这里动态替换占位项。
            attribList[attribList.length - 3] = EGL_RECORDABLE_ANDROID;
            attribList[attribList.length - 2] = 1;
        }
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            Log.w(TAG, "unable to find RGB8888 / " + version + " EGLConfig");
            return null;
        }
        return configs[0];
    }

    /**
     * 释放 display、context 及其关联的 EGL 线程状态。
     *
     * <p>必须在拥有当前 context 的 GL 线程调用。释放前先解除当前绑定，避免 context 或
     * surface 仍处于 current 状态；完成后当前线程不再有 EGL context。</p>
     */
    public void release() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            // Android 的 EGLDisplay 使用引用计数；每次 eglInitialize() 都应对应一次 eglTerminate()。
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }

        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        mEGLConfig = null;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                // 这里的兜底能力有限：finalizer 不一定运行在持有 EGL 状态的线程上，
                // 如果其他线程仍把 surface 或 context 设为 current，就无法保证完整释放。
                // 因此显式 release() 仍然是首选；这里只在日志中提示可能发生泄漏。
                Log.w(TAG, "WARNING: EglCore was not explicitly released -- state may be leaked");
                release();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * 销毁指定 EGLSurface。
     *
     * <p>如果它仍被某个 context 作为 current surface 使用，底层可能会延迟实际销毁，
     * 因此调用方仍需遵守“先切换/解除 current，再释放 surface”的生命周期顺序。</p>
     */
    public void releaseSurface(EGLSurface eglSurface) {
        EGL14.eglDestroySurface(mEGLDisplay, eglSurface);
    }

    /**
     * 从 Android {@link Surface} 或 {@link SurfaceTexture} 创建窗口型 EGLSurface。
     *
     * <p>窗口 EGLSurface 是 GL 的绘制目标，绘制完成后通过 {@link #swapBuffers(EGLSurface)}
     * 将 back buffer 提交给系统窗口或编码器。若目标是 MediaCodec，创建本对象时应带上
     * {@link #FLAG_RECORDABLE}。</p>
     *
     * @param surface Android Surface 或 SurfaceTexture
     * @return 新创建的 EGLSurface
     */
    public EGLSurface createWindowSurface(Object surface) {
        if (!(surface instanceof Surface) && !(surface instanceof SurfaceTexture)) {
            throw new RuntimeException("invalid surface: " + surface);
        }

        // 创建窗口 surface，并把 EGL 的绘制缓冲区连接到传入的 Android Surface。
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, surface,
                surfaceAttribs, 0);
        checkEglError("eglCreateWindowSurface");
        if (eglSurface == null) {
            throw new RuntimeException("surface was null");
        }
        return eglSurface;
    }

    /**
     * 创建离屏 Pbuffer EGLSurface。
     *
     * <p>Pbuffer 没有 Android 窗口，适合做离屏渲染、GL 能力查询或性能测试。它仍然需要
     * 通过 {@link #makeCurrent(EGLSurface)} 绑定后才能进行 GL 绘制。</p>
     */
    public EGLSurface createOffscreenSurface(int width, int height) {
        int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };
        EGLSurface eglSurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, mEGLConfig,
                surfaceAttribs, 0);
        checkEglError("eglCreatePbufferSurface");
        if (eglSurface == null) {
            throw new RuntimeException("surface was null");
        }
        return eglSurface;
    }

    /** 让本对象的 context 在当前线程生效，并将同一个 surface 同时作为读、写目标。 */
    public void makeCurrent(EGLSurface eglSurface) {
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            // 这通常意味着调用顺序错误：还没有完成 EGL 初始化就尝试绑定 surface。
            Log.d(TAG, "NOTE: makeCurrent w/o display");
        }
        if (!EGL14.eglMakeCurrent(mEGLDisplay, eglSurface, eglSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * 让本对象的 context 在当前线程生效，并分别指定绘制 surface 和读取 surface。
     *
     * <p>读、写 surface 可以不同，例如从一个 EGLSurface 读取像素，同时向另一个 surface
     * 绘制；普通场景使用单参数版本即可。</p>
     */
    public void makeCurrent(EGLSurface drawSurface, EGLSurface readSurface) {
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            // 这通常意味着调用顺序错误：还没有完成 EGL 初始化就尝试绑定 surface。
            Log.d(TAG, "NOTE: makeCurrent w/o display");
        }
        if (!EGL14.eglMakeCurrent(mEGLDisplay, drawSurface, readSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent(draw,read) failed");
        }
    }

    /** 解除当前线程上的 EGL context 和读写 surface 绑定。 */
    public void makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * 交换前后缓冲区，将当前帧提交给窗口系统或编码器。
     *
     * @return {@code true} 表示交换成功，{@code false} 表示失败
     */
    public boolean swapBuffers(EGLSurface eglSurface) {
        return EGL14.eglSwapBuffers(mEGLDisplay, eglSurface);
    }

    /**
     * 为 EGLSurface 设置当前帧的呈现时间戳。
     *
     * <p>该时间戳通常会传递给 MediaCodec，用于生成正确的视频 PTS；单位为纳秒。</p>
     */
    public void setPresentationTime(EGLSurface eglSurface, long nsecs) {
        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, eglSurface, nsecs);
    }

    /** 判断本对象的 context 以及指定 surface 是否正是当前线程的 current 状态。 */
    public boolean isCurrent(EGLSurface eglSurface) {
        return mEGLContext.equals(EGL14.eglGetCurrentContext()) &&
            eglSurface.equals(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
    }

    /** 查询 EGLSurface 的一个整数属性，例如 {@link EGL14#EGL_WIDTH} 或 {@link EGL14#EGL_HEIGHT}。 */
    public int querySurface(EGLSurface eglSurface, int what) {
        int[] value = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, eglSurface, what, value, 0);
        return value[0];
    }

    /** 查询 EGL 的字符串属性，例如扩展列表或供应商信息。 */
    public String queryString(int what) {
        return EGL14.eglQueryString(mEGLDisplay, what);
    }

    /** 返回该 context 实际配置的 GLES 版本，目前为 2 或 3。 */
    public int getGlVersion() {
        return mGlVersion;
    }

    /** 将当前线程的 EGLDisplay、EGLContext 和绘制 EGLSurface 输出到日志，便于调试绑定问题。 */
    public static void logCurrent(String msg) {
        EGLDisplay display;
        EGLContext context;
        EGLSurface surface;

        display = EGL14.eglGetCurrentDisplay();
        context = EGL14.eglGetCurrentContext();
        surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        Log.i(TAG, "Current EGL (" + msg + "): display=" + display + ", context=" + context +
                ", surface=" + surface);
    }

    /** 检查最近一次 EGL 调用是否产生错误；错误会立即转换为异常，避免后续状态继续扩散。 */
    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }
}
