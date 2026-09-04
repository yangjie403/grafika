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

import java.nio.FloatBuffer;

/**
 * 可绘制二维几何图形的数据提供类。
 *
 * <p>这个类不负责真正调用 OpenGL 绘制，而是预先准备好顶点坐标和纹理坐标，
 * 供 {@link FlatShadedProgram} 或 {@link Texture2dProgram} 在绘制时使用。
 * 顶点坐标使用 OpenGL 的标准化设备坐标（NDC）概念：经过单位矩阵变换后，
 * x、y 在 [-1, 1] 范围内，其中 (-1, -1) 是左下角、(1, 1) 是右上角。</p>
 *
 * <p>类中的图形都是不可变的预制数据，并通过静态 {@link FloatBuffer} 复用，
 * 这样每次绘制不需要重新分配内存。调用方只能读取 getter 返回的 Buffer，
 * 不应修改其内容。</p>
 */
public class Drawable2d {
    /** Java float 在 native buffer 中占用的字节数。 */
    private static final int SIZEOF_FLOAT = 4;

    /** 边长为 1、中心位于原点的等边三角形顶点坐标。 */
    private static final float TRIANGLE_COORDS[] = {
         0.0f,  0.577350269f,   // 0：顶部
        -0.5f, -0.288675135f,   // 1：左下
         0.5f, -0.288675135f    // 2：右下
    };
    /** 三角形三个顶点对应的纹理坐标，纹理坐标范围为 [0, 1]。 */
    private static final float TRIANGLE_TEX_COORDS[] = {
        0.5f, 0.0f,     // 0：顶部中心
        0.0f, 1.0f,     // 1：左下
        1.0f, 1.0f,     // 2：右下
    };
    private static final FloatBuffer TRIANGLE_BUF =
            GlUtil.createFloatBuffer(TRIANGLE_COORDS);
    private static final FloatBuffer TRIANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(TRIANGLE_TEX_COORDS);

    /**
     * 中心位于原点、大小为 1x1 的普通矩形。
     *
     * <p>矩形使用 triangle strip 绘制，四个点的顺序会组成两个三角形：
     * 0-1-2 和 2-1-3。逆时针绕序便于配合 OpenGL 的正面判定。</p>
     */
    private static final float RECTANGLE_COORDS[] = {
        -0.5f, -0.5f,   // 0：左下
         0.5f, -0.5f,   // 1：右下
        -0.5f,  0.5f,   // 2：左上
         0.5f,  0.5f,   // 3：右上
    };
    /** 普通矩形的纹理坐标；这里按图像坐标习惯使用左上角为纹理原点的排列。 */
    private static final float RECTANGLE_TEX_COORDS[] = {
        0.0f, 1.0f,     // 0：左下
        1.0f, 1.0f,     // 1：右下
        0.0f, 0.0f,     // 2：左上
        1.0f, 0.0f      // 3：右上
    };
    private static final FloatBuffer RECTANGLE_BUF =
            GlUtil.createFloatBuffer(RECTANGLE_COORDS);
    private static final FloatBuffer RECTANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(RECTANGLE_TEX_COORDS);

    /**
     * 覆盖整个 NDC 的矩形，x、y 均从 -1 延伸到 1。
     *
     * <p>当 MVP 矩阵为单位矩阵时，它会正好覆盖当前 viewport。这个图形通常用于
     * 将 Camera、MediaCodec 或 {@link android.graphics.SurfaceTexture} 的纹理铺满屏幕。</p>
     *
     * <p>它的纹理坐标与普通矩形的 Y 方向相反。这是为了适配 SurfaceTexture 中常见的
     * 外部纹理方向；实际方向还会受到 Shader 中的纹理矩阵影响。</p>
     */
    private static final float FULL_RECTANGLE_COORDS[] = {
        -1.0f, -1.0f,   // 0：左下
         1.0f, -1.0f,   // 1：右下
        -1.0f,  1.0f,   // 2：左上
         1.0f,  1.0f,   // 3：右上
    };
    private static final float FULL_RECTANGLE_TEX_COORDS[] = {
        0.0f, 0.0f,     // 0：左下
        1.0f, 0.0f,     // 1：右下
        0.0f, 1.0f,     // 2：左上
        1.0f, 1.0f      // 3：右上
    };
    private static final FloatBuffer FULL_RECTANGLE_BUF =
            GlUtil.createFloatBuffer(FULL_RECTANGLE_COORDS);
    private static final FloatBuffer FULL_RECTANGLE_TEX_BUF =
            GlUtil.createFloatBuffer(FULL_RECTANGLE_TEX_COORDS);


    /** 顶点坐标 Buffer；每个顶点通常包含 x、y 两个 float。 */
    private FloatBuffer mVertexArray;
    /** 纹理坐标 Buffer；每个顶点包含 s、t 两个 float。 */
    private FloatBuffer mTexCoordArray;
    /** 顶点数量，而不是 float 数量。 */
    private int mVertexCount;
    /** 每个顶点包含的坐标分量数量，目前为 2。 */
    private int mCoordsPerVertex;
    /** 相邻两个顶点之间的字节步长，传给 glVertexAttribPointer。 */
    private int mVertexStride;
    /** 相邻两个纹理坐标之间的字节步长。 */
    private int mTexCoordStride;
    /** 记录当前采用的预制图形，主要用于调试输出。 */
    private Prefab mPrefab;

    /** 构造函数支持的预制图形类型。 */
    public enum Prefab {
        TRIANGLE, RECTANGLE, FULL_RECTANGLE
    }

    /**
     * 根据预制图形初始化顶点和纹理坐标。
     *
     * <p>这里只选择已经创建好的 CPU 端 Buffer，不执行 EGL 或 GL 调用，因此可以在
     * GL context 创建之前构造。真正绘制时，调用方会把这些 Buffer 作为 vertex attribute
     * 传给 Shader。</p>
     *
     * @param shape 要使用的预制图形
     */
    public Drawable2d(Prefab shape) {
        switch (shape) {
            case TRIANGLE:
                mVertexArray = TRIANGLE_BUF;
                mTexCoordArray = TRIANGLE_TEX_BUF;
                mCoordsPerVertex = 2;
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                mVertexCount = TRIANGLE_COORDS.length / mCoordsPerVertex;
                break;
            case RECTANGLE:
                mVertexArray = RECTANGLE_BUF;
                mTexCoordArray = RECTANGLE_TEX_BUF;
                mCoordsPerVertex = 2;
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                mVertexCount = RECTANGLE_COORDS.length / mCoordsPerVertex;
                break;
            case FULL_RECTANGLE:
                mVertexArray = FULL_RECTANGLE_BUF;
                mTexCoordArray = FULL_RECTANGLE_TEX_BUF;
                mCoordsPerVertex = 2;
                mVertexStride = mCoordsPerVertex * SIZEOF_FLOAT;
                mVertexCount = FULL_RECTANGLE_COORDS.length / mCoordsPerVertex;
                break;
            default:
                throw new RuntimeException("Unknown shape " + shape);
        }
        mTexCoordStride = 2 * SIZEOF_FLOAT;
        mPrefab = shape;
    }

    /**
     * 返回顶点坐标 Buffer。
     *
     * <p>为避免分配内存，这里返回内部对象；调用方只能读取，不能修改其 position 或内容。</p>
     */
    public FloatBuffer getVertexArray() {
        return mVertexArray;
    }

    /**
     * 返回纹理坐标 Buffer。
     *
     * <p>纹理坐标会在顶点 Shader 中与纹理矩阵相乘，再传入片元 Shader 采样纹理。</p>
     */
    public FloatBuffer getTexCoordArray() {
        return mTexCoordArray;
    }

    /** 返回顶点数量，传给 {@code glDrawArrays()} 的 {@code count} 参数。 */
    public int getVertexCount() {
        return mVertexCount;
    }

    /** 返回单个顶点数据的字节步长，传给 {@code glVertexAttribPointer()}。 */
    public int getVertexStride() {
        return mVertexStride;
    }

    /** 返回单个纹理坐标数据的字节步长。 */
    public int getTexCoordStride() {
        return mTexCoordStride;
    }

    /** 返回每个顶点的位置分量数量；本实现的图形均为二维坐标，因此为 2。 */
    public int getCoordsPerVertex() {
        return mCoordsPerVertex;
    }

    @Override
    public String toString() {
        if (mPrefab != null) {
            return "[Drawable2d: " + mPrefab + "]";
        } else {
            return "[Drawable2d: ...]";
        }
    }
}
