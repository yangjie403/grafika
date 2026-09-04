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

/**
 * 离屏 EGLSurface（Pbuffer）的便捷封装。
 *
 * <p>Pbuffer 没有对应的 Android 窗口，不会直接显示到屏幕。它可以作为当前绘制目标，
 * 用于创建 GL 对象、执行离屏渲染、读取像素或做性能测试。与窗口 surface 一样，使用前
 * 需要调用 {@link #makeCurrent()}。</p>
 *
 * <p>建议显式调用 {@link #release()}，最好放在 finally 代码块中。</p>
 */
public class OffscreenSurface extends EglSurfaceBase {
    /** 创建指定像素尺寸的离屏 Pbuffer。 */
    public OffscreenSurface(EglCore eglCore, int width, int height) {
        super(eglCore);
        createOffscreenSurface(width, height);
    }

    /** 释放底层 EGLSurface。 */
    public void release() {
        releaseEglSurface();
    }
}
