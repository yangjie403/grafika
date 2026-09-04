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

package com.android.grafika.gles;

import android.opengl.Matrix;

/**
 * 使用纹理填充整个当前 viewport 的矩形绘制器。
 *
 * <p>内部使用 {@link Drawable2d.Prefab#FULL_RECTANGLE}，其顶点范围是 [-1, 1]。因此绘制时
 * MVP 采用单位矩阵，矩形会直接覆盖裁剪空间，最终覆盖 viewport。纹理坐标仍交给
 * {@link Texture2dProgram} 的纹理矩阵处理，这对 Camera/MediaCodec 产生的 SurfaceTexture
 * 外部纹理尤其重要。</p>
 *
 * <p>本类拥有传入的 {@link Texture2dProgram}：调用 {@link #release(boolean)} 或替换 program
 * 时会负责释放旧 program。</p>
 */
public class FullFrameRect {
    /** 覆盖整个裁剪空间的固定矩形。 */
    private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.FULL_RECTANGLE);
    /** 当前负责纹理采样和绘制的 Shader program。 */
    private Texture2dProgram mProgram;

    /**
     * 创建全屏矩形绘制器，并接管 program 的生命周期。
     *
     * @param program 用于采样和绘制纹理的 Texture2dProgram
     */
    public FullFrameRect(Texture2dProgram program) {
        mProgram = program;
    }

    /**
     * 释放绘制器和其持有的 program。
     *
     * <p>如果 EGL context 仍然可用，传 {@code true} 让 program 执行 glDeleteProgram；
     * 如果 context 即将销毁、无需再单独删除 GL 对象，则可传 {@code false} 跳过 GL 清理。</p>
     *
     * @param doEglCleanup 是否在当前 EGL context 中执行 program 的 GL 资源删除
     */
    public void release(boolean doEglCleanup) {
        if (mProgram != null) {
            if (doEglCleanup) {
                mProgram.release();
            }
            mProgram = null;
        }
    }

    /** 返回当前纹理绘制 program；可能在 release 后为 {@code null}。 */
    public Texture2dProgram getProgram() {
        return mProgram;
    }

    /**
     * 更换纹理绘制 program，并释放旧 program。
     *
     * <p>旧 program 的删除需要其所属的 EGL context current；新 program 应已在当前可用
     * context 中创建。</p>
     *
     * @param program 新的纹理绘制 program
     */
    public void changeProgram(Texture2dProgram program) {
        mProgram.release();
        mProgram = program;
    }

    /** 创建一个适合当前 program 的纹理对象，并返回其 GL 句柄。 */
    public int createTextureObject() {
        return mProgram.createTextureObject();
    }

    /**
     * 将指定纹理绘制到整个 viewport。
     *
     * <p>{@code texMatrix} 用于校正纹理方向、裁剪或 SurfaceTexture 的变换；它不会改变
     * 矩形本身的位置和大小。</p>
     *
     * @param textureId 要采样的 GL 纹理句柄
     * @param texMatrix 纹理坐标变换矩阵，通常由 SurfaceTexture.getTransformMatrix() 提供
     */
    public void drawFrame(int textureId, float[] texMatrix) {
        // 全屏矩形已经位于 [-1, 1]，所以使用单位 MVP 直接覆盖当前 viewport。
        mProgram.draw(GlUtil.IDENTITY_MATRIX, mRectDrawable.getVertexArray(), 0,
                mRectDrawable.getVertexCount(), mRectDrawable.getCoordsPerVertex(),
                mRectDrawable.getVertexStride(),
                texMatrix, mRectDrawable.getTexCoordArray(), textureId,
                mRectDrawable.getTexCoordStride());
    }
}
