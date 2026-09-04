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
import android.util.Log;

/**
 * 对二维图形进行位置、缩放、旋转和颜色/纹理管理的精灵类。
 *
 * <p>Sprite2d 把“几何数据”和“实例属性”分开：{@link Drawable2d} 提供图形的顶点，
 * 本类保存某一个实例的位置、尺寸、角度、颜色和纹理。绘制时先生成模型-视图矩阵，
 * 再与调用方提供的投影矩阵相乘，最后委托给具体的 Shader program。</p>
 *
 * <p>类使用延迟计算（dirty flag）缓存矩阵。只有位置、缩放或旋转变化后才重新计算，
 * 适合在动画循环中反复绘制。构造后缩放值默认为 Java float 的 0，调用方应在绘制前
 * 使用 {@link #setScale(float, float)} 设置尺寸。</p>
 */
public class Sprite2d {
    /** 日志标签；当前主要用于与其他 GLES 辅助类保持一致。 */
    private static final String TAG = GlUtil.TAG;

    /** 要绘制的几何图形。多个 Sprite2d 可以共享同一个 Drawable2d。 */
    private Drawable2d mDrawable;
    /** RGBA 颜色；flat-shaded 绘制使用，纹理绘制不会读取它。 */
    private float mColor[];
    /** 纹理句柄；纹理绘制使用，纯色绘制不会读取它。 */
    private int mTextureId;
    /** 绕 Z 轴旋转的角度，单位为度。 */
    private float mAngle;
    /** X、Y 方向缩放因子。 */
    private float mScaleX, mScaleY;
    /** 精灵平移位置，位于模型坐标系中。 */
    private float mPosX, mPosY;

    /** 缓存的模型-视图矩阵，按 Android Matrix 的列主序存储。 */
    private float[] mModelViewMatrix;
    /** true 表示缓存矩阵与当前属性一致。 */
    private boolean mMatrixReady;

    /** 复用的 MVP 乘积矩阵，避免每次 draw 分配数组。 */
    private float[] mScratchMatrix = new float[16];

    /**
     * 创建一个使用指定几何图形的精灵。
     *
     * @param drawable 顶点和纹理坐标来源
     */
    public Sprite2d(Drawable2d drawable) {
        mDrawable = drawable;
        mColor = new float[4];
        mColor[3] = 1.0f;
        mTextureId = -1;

        mModelViewMatrix = new float[16];
        mMatrixReady = false;
    }

    /**
     * 按当前平移、旋转、缩放值重新计算模型-视图矩阵。
     *
     * <p>变换顺序在矩阵组合后体现为“先缩放，再旋转，最后平移”作用到顶点，
     * 这样旋转和缩放都围绕图形自身的原点进行，随后移动到指定位置。</p>
     */
    private void recomputeMatrix() {
        float[] modelView = mModelViewMatrix;

        Matrix.setIdentityM(modelView, 0);
        Matrix.translateM(modelView, 0, mPosX, mPosY, 0.0f);
        if (mAngle != 0.0f) {
            Matrix.rotateM(modelView, 0, mAngle, 0.0f, 0.0f, 1.0f);
        }
        Matrix.scaleM(modelView, 0, mScaleX, mScaleY, 1.0f);
        mMatrixReady = true;
    }

    /** 返回精灵在 X 轴方向的缩放因子。 */
    public float getScaleX() {
        return mScaleX;
    }

    /** 返回精灵在 Y 轴方向的缩放因子。 */
    public float getScaleY() {
        return mScaleY;
    }

    /** 设置精灵在 X、Y 方向的缩放因子，也就是最终显示尺寸。 */
    public void setScale(float scaleX, float scaleY) {
        mScaleX = scaleX;
        mScaleY = scaleY;
        mMatrixReady = false;
    }

    /** 返回精灵绕 Z 轴旋转的角度，单位为度。 */
    public float getRotation() {
        return mAngle;
    }

    /** 设置精灵绕 Z 轴旋转的角度，单位为度；正角度表示逆时针旋转。 */
    public void setRotation(float angle) {
        // 归一化到大致 [-360, 360) 范围，避免角度无限增长影响调试和矩阵计算。
        while (angle >= 360.0f) {
            angle -= 360.0f;
        }
        while (angle <= -360.0f) {
            angle += 360.0f;
        }
        mAngle = angle;
        mMatrixReady = false;
    }

    /** 返回精灵在模型坐标系中的 X 位置。 */
    public float getPositionX() {
        return mPosX;
    }

    /** 返回精灵在模型坐标系中的 Y 位置。 */
    public float getPositionY() {
        return mPosY;
    }

    /** 设置精灵在模型坐标系中的平移位置。 */
    public void setPosition(float posX, float posY) {
        mPosX = posX;
        mPosY = posY;
        mMatrixReady = false;
    }

    /**
     * 返回缓存的模型-视图矩阵，必要时先按最新属性重算。
     *
     * <p>为避免分配内存，返回的是内部数组；调用方不得修改。</p>
     */
    public float[] getModelViewMatrix() {
        if (!mMatrixReady) {
            recomputeMatrix();
        }
        return mModelViewMatrix;
    }

    /**
     * 设置纯色绘制时使用的 RGB 颜色。
     *
     * @param red 红色分量，通常在 [0, 1]
     * @param green 绿色分量，通常在 [0, 1]
     * @param blue 蓝色分量，通常在 [0, 1]
     */
    public void setColor(float red, float green, float blue) {
        mColor[0] = red;
        mColor[1] = green;
        mColor[2] = blue;
    }

    /** 设置纹理绘制使用的 GL 纹理句柄；纯色绘制不会读取此值。 */
    public void setTexture(int textureId) {
        mTextureId = textureId;
    }

    /** 返回内部 RGBA 颜色数组；为避免分配内存，调用方不得修改返回数组。 */
    public float[] getColor() {
        return mColor;
    }

    /**
     * 使用纯色 program 绘制精灵。
     *
     * <p>这里把投影矩阵与精灵自己的模型-视图矩阵相乘，得到传给顶点 Shader 的 MVP 矩阵；
     * 颜色和顶点数据随后由 {@link FlatShadedProgram} 上传并绘制。</p>
     */
    public void draw(FlatShadedProgram program, float[] projectionMatrix) {
        // MVP = Projection * ModelView；OpenGL 顶点会先完成模型变换，再完成投影变换。
        Matrix.multiplyMM(mScratchMatrix, 0, projectionMatrix, 0, getModelViewMatrix(), 0);

        program.draw(mScratchMatrix, mColor, mDrawable.getVertexArray(), 0,
                mDrawable.getVertexCount(), mDrawable.getCoordsPerVertex(),
                mDrawable.getVertexStride());
    }

    /**
     * 使用纹理 program 绘制精灵。
     *
     * <p>本方法使用单位纹理矩阵，表示 Drawable2d 中的纹理坐标不需要额外变换；
     * 如果纹理来自 SurfaceTexture，通常应直接调用 Texture2dProgram.draw() 并传入
     * SurfaceTexture.getTransformMatrix() 返回的矩阵。</p>
     */
    public void draw(Texture2dProgram program, float[] projectionMatrix) {
        // 计算精灵的 MVP 矩阵；纹理坐标变换在这里使用单位矩阵。
        Matrix.multiplyMM(mScratchMatrix, 0, projectionMatrix, 0, getModelViewMatrix(), 0);

        program.draw(mScratchMatrix, mDrawable.getVertexArray(), 0,
                mDrawable.getVertexCount(), mDrawable.getCoordsPerVertex(),
                mDrawable.getVertexStride(), GlUtil.IDENTITY_MATRIX, mDrawable.getTexCoordArray(),
                mTextureId, mDrawable.getTexCoordStride());
    }

    @Override
    public String toString() {
        return "[Sprite2d pos=" + mPosX + "," + mPosY +
                " scale=" + mScaleX + "," + mScaleY + " angle=" + mAngle +
                " color={" + mColor[0] + "," + mColor[1] + "," + mColor[2] +
                "} drawable=" + mDrawable + "]";
    }
}
