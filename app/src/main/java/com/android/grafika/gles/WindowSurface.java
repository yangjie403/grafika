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
import android.view.Surface;

/**
 * Android 窗口与 EGLSurface 之间的封装。
 *
 * <p>它可以把 {@link Surface} 或 {@link SurfaceTexture} 作为 EGL 的绘制目标。典型用途包括：
 * 把 GL 帧显示到 SurfaceView/TextureView，把 GL 帧送入 MediaCodec 编码器，或者把相机/视频
 * 解码器写入 SurfaceTexture 后再用 GL 采样。建议显式调用 {@link #release()}，最好放在
 * finally 代码块中。</p>
 *
 * <p>注意 EGLSurface 的释放和 Android Surface 对象的释放是两件事；只有使用 Surface 构造函数
 * 并把 {@code releaseSurface} 设为 true 时，本类才会一并释放原始 Surface。</p>
 */
public class WindowSurface extends EglSurfaceBase {
    /** 由本类持有的 Android Surface；SurfaceTexture 构造路径不会设置它。 */
    private Surface mSurface;
    /** release() 时是否同时释放 mSurface。 */
    private boolean mReleaseSurface;

    /**
     * 把 Android {@link Surface} 包装成 EGL 窗口 surface。
     *
     * <p>{@code releaseSurface} 为 true 时，{@link #release()} 会同时调用 Surface.release()。
     * 这适合由本类独占创建的 Surface；如果 Surface 由 SurfaceView 等框架组件管理，应传 false，
     * 否则可能干扰框架自己的生命周期回调。</p>
     *
     * @param eglCore 提供 display、context 和 config 的 EglCore
     * @param surface 要连接的 Android Surface
     * @param releaseSurface 是否由本类在 release() 时释放 Surface
     */
    public WindowSurface(EglCore eglCore, Surface surface, boolean releaseSurface) {
        super(eglCore);
        createWindowSurface(surface);
        mSurface = surface;
        mReleaseSurface = releaseSurface;
    }

    /** 把 SurfaceTexture 作为 EGL 窗口 surface 的底层原生窗口。 */
    public WindowSurface(EglCore eglCore, SurfaceTexture surfaceTexture) {
        super(eglCore);
        createWindowSurface(surfaceTexture);
    }

    /**
     * 释放 EGLSurface，并根据构造参数决定是否释放原始 Android Surface。
     *
     * <p>此方法本身不要求该 EGLSurface 当前处于 current 状态；但释放后不能再用本对象绘制。</p>
     */
    public void release() {
        releaseEglSurface();
        if (mSurface != null) {
            if (mReleaseSurface) {
                mSurface.release();
            }
            mSurface = null;
        }
    }

    /**
     * 使用新的 EglCore 为同一个 Android Surface 重新创建 EGLSurface。
     *
     * <p>调用方必须先释放旧的 EGLSurface，再调用本方法。常见场景是 EGL context 被重建后，
     * 需要让已有 Surface 连接到新的 context。Android Surface 同时只能连接到有限的生产者，
     * 如果旧 EGLSurface 仍处于 current 或未完全销毁，重新创建可能失败。</p>
     *
     * @param newEglCore 新的 EGL 核心对象
     */
    public void recreate(EglCore newEglCore) {
        if (mSurface == null) {
            throw new RuntimeException("not yet implemented for SurfaceTexture");
        }
        mEglCore = newEglCore;          // 切换到新的 EGL context
        createWindowSurface(mSurface);  // 为原来的 Android Surface 创建新的 EGLSurface
    }
}
