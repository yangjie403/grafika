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

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * EGLSurface 的通用封装基类。
 *
 * <p>一个 {@link EglCore} 可以关联多个 EGLSurface，例如一个窗口 surface 用于显示，
 * 另一个窗口 surface 连接 MediaCodec，或者一个 Pbuffer 用于离屏渲染。本类把这些 surface
 * 的创建、尺寸查询、current 切换、交换缓冲区和截图操作统一起来。</p>
 *
 * <p>本类不持有 Android {@link android.view.Surface} 的所有权；具体的资源释放策略由
 * {@link WindowSurface} 和 {@link OffscreenSurface} 决定。</p>
 */
public class EglSurfaceBase {
    /** 日志标签。 */
    protected static final String TAG = GlUtil.TAG;

    /** 所属的 EGL 核心对象；同一个 EglCore 可以服务多个 EGLSurface。 */
    protected EglCore mEglCore;

    /** 当前封装的底层 EGLSurface；NO_SURFACE 表示尚未创建或已经释放。 */
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
    /** 缓存的宽度；窗口 surface 使用 -1，表示每次从 EGL 查询。 */
    private int mWidth = -1;
    /** 缓存的高度；窗口 surface 使用 -1，表示每次从 EGL 查询。 */
    private int mHeight = -1;

    /** 由子类调用，关联一个已经初始化好的 EglCore。 */
    protected EglSurfaceBase(EglCore eglCore) {
        mEglCore = eglCore;
    }

    /**
     * 创建窗口型 EGLSurface。
     *
     * <p>窗口底层可以是 {@link android.view.Surface} 或 {@link android.graphics.SurfaceTexture}。
     * 窗口尺寸可能在运行时变化，因此这里不缓存尺寸，而是在 {@link #getWidth()} 和
     * {@link #getHeight()} 中动态查询。</p>
     *
     * @param surface Android Surface 或 SurfaceTexture
     */
    public void createWindowSurface(Object surface) {
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("surface already created");
        }
        mEGLSurface = mEglCore.createWindowSurface(surface);

        // 不缓存窗口宽高，因为底层窗口可能异步改变尺寸；动态查询才能观察到最新值。
        //mWidth = mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH);
        //mHeight = mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT);
    }

    /** 创建指定大小的离屏 Pbuffer，并缓存其固定宽高。 */
    public void createOffscreenSurface(int width, int height) {
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("surface already created");
        }
        mEGLSurface = mEglCore.createOffscreenSurface(width, height);
        mWidth = width;
        mHeight = height;
    }

    /**
     * 返回 EGLSurface 宽度，单位为像素。
     *
     * <p>窗口 surface 的尺寸由底层 Android Surface 决定，可能在 surfaceChanged 回调期间
     * 仍处于更新过程中；Pbuffer 的尺寸则是创建时指定的固定值。</p>
     */
    public int getWidth() {
        if (mWidth < 0) {
            return mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH);
        } else {
            return mWidth;
        }
    }

    /** 返回 EGLSurface 高度，单位为像素。 */
    public int getHeight() {
        if (mHeight < 0) {
            return mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT);
        } else {
            return mHeight;
        }
    }

    /** 释放底层 EGLSurface，并清除尺寸缓存；不负责释放 Android Surface 对象。 */
    public void releaseEglSurface() {
        mEglCore.releaseSurface(mEGLSurface);
        mEGLSurface = EGL14.EGL_NO_SURFACE;
        mWidth = mHeight = -1;
    }

    /** 将所属 EglCore 的 context 和当前 surface 绑定到调用线程。 */
    public void makeCurrent() {
        mEglCore.makeCurrent(mEGLSurface);
    }

    /**
     * 将当前 surface 作为绘制目标，同时把另一个 surface 作为读取目标绑定到当前线程。
     *
     * <p>这种读写分离模式主要用于 EGL 的跨 surface 操作；普通绘制调用 {@link #makeCurrent()}
     * 即可。</p>
     */
    public void makeCurrentReadFrom(EglSurfaceBase readSurface) {
        mEglCore.makeCurrent(mEGLSurface, readSurface.mEGLSurface);
    }

    /**
     * 交换 EGLSurface 的前后缓冲区，把当前帧发布到窗口或编码器。
     *
     * @return {@code true} 表示成功，{@code false} 表示 EGL 报告失败
     */
    public boolean swapBuffers() {
        boolean result = mEglCore.swapBuffers(mEGLSurface);
        if (!result) {
            Log.d(TAG, "WARNING: swapBuffers() failed");
        }
        return result;
    }

    /**
     * 设置当前 EGLSurface 的帧呈现时间戳。
     *
     * @param nsecs 时间戳，单位为纳秒
     */
    public void setPresentationTime(long nsecs) {
        mEglCore.setPresentationTime(mEGLSurface, nsecs);
    }

    /**
     * 读取当前 EGLSurface 的像素并保存为 PNG 文件。
     *
     * <p>调用前必须保证本对象的 EGLSurface 是 current。实现通过 {@code glReadPixels()} 从
     * 左下角开始读取 RGBA 字节，再交给 Android Bitmap 编码。OpenGL 的坐标原点通常在左下角，
     * 而图像坐标习惯上从左上角开始，因此截图方向可能与屏幕显示方向相反，这是理解截图结果
     * 时需要注意的点。</p>
     *
     * @param file 输出 PNG 文件
     * @throws IOException 创建或写入文件失败
     */
    public void saveFrame(File file) throws IOException {
        if (!mEglCore.isCurrent(mEGLSurface)) {
            throw new RuntimeException("Expected EGL context/surface is not current");
        }

        // glReadPixels() 写入的是连续的 RGBA 字节。这里使用 direct ByteBuffer，既满足 GLES
        // 对 native buffer 的要求，也可以直接交给 Bitmap.copyPixelsFromBuffer()。
        // 注意 GL 的像素行从底部开始，因此输出 PNG 可能上下颠倒；本工具类不额外翻转行。

        String filename = file.toString();

        int width = getWidth();
        int height = getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        GLES20.glReadPixels(0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        GlUtil.checkGlError("glReadPixels");
        buf.rewind();

        BufferedOutputStream bos = null;
        try {
            bos = new BufferedOutputStream(new FileOutputStream(filename));
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
            bmp.recycle();
        } finally {
            if (bos != null) bos.close();
        }
        Log.d(TAG, "Saved " + width + "x" + height + " frame as '" + filename + "'");
    }
}
