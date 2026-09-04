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

import android.opengl.GLES20;
import android.util.Log;

import java.nio.FloatBuffer;

/**
 * 用单一纯色绘制二维几何图形的 OpenGL ES Shader 程序。
 *
 * <p>该程序包含一个顶点 Shader 和一个片元 Shader：顶点 Shader 用
 * {@code uMVPMatrix} 把输入位置变换到裁剪空间，片元 Shader 对整个图元输出同一个
 * {@code uColor}。它不读取纹理，适合绘制彩色矩形、三角形和调试标记。</p>
 *
 * <p>程序对象属于创建它时的 EGL context。创建、绘制和释放都应在该 context 当前时执行。</p>
 */
public class FlatShadedProgram {
    /** 日志标签。 */
    private static final String TAG = GlUtil.TAG;

    /** 顶点 Shader：只做 MVP 矩阵变换，不处理纹理坐标。 */
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;" +
            "attribute vec4 aPosition;" +
            "void main() {" +
            "    gl_Position = uMVPMatrix * aPosition;" +
            "}";

    /** 片元 Shader：把每个片元设置为同一个 RGBA 颜色。 */
    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform vec4 uColor;" +
            "void main() {" +
            "    gl_FragColor = uColor;" +
            "}";

    /** GL program 及其 attribute/uniform 的句柄。句柄只对所属 context 有效。 */
    private int mProgramHandle = -1;
    private int muColorLoc = -1;
    private int muMVPMatrixLoc = -1;
    private int maPositionLoc = -1;


    /** 在当前 EGL context 中编译、链接 Shader，并查询变量位置。 */
    public FlatShadedProgram() {
        mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (mProgramHandle == 0) {
            throw new RuntimeException("Unable to create program");
        }
        Log.d(TAG, "Created program " + mProgramHandle);

        // Shader 编译成功并不等于变量一定存在；这里查询位置并在缺失时立即报错。

        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        GlUtil.checkLocation(maPositionLoc, "aPosition");
        muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
        GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");
        muColorLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColor");
        GlUtil.checkLocation(muColorLoc, "uColor");
    }

    /**
     * 删除 GL program。
     *
     * <p>调用时必须使创建该 program 的 EGL context current。</p>
     */
    public void release() {
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }

    /**
     * 使用纯色 Shader 绘制一组顶点。
     *
     * <p>每次调用都会重新设置 program、uniform 和顶点 attribute：先激活 program，
     * 再上传 MVP 矩阵和颜色，然后把 vertexBuffer 绑定到 {@code aPosition}，最后以
     * triangle strip 发出绘制命令。绘制结束后主动关闭 attribute 和 program，减少对调用方
     * GL 状态的影响。</p>
     *
     * @param mvpMatrix 4x4 模型-视图-投影矩阵
     * @param color RGBA 颜色数组，四个分量通常在 [0, 1]
     * @param vertexBuffer 顶点位置数据
     * @param firstVertex 要使用的起始顶点索引
     * @param vertexCount 要绘制的顶点数量
     * @param coordsPerVertex 每个顶点的位置分量数量，例如 x、y 为 2
     * @param vertexStride 相邻顶点之间的字节步长
     */
    public void draw(float[] mvpMatrix, float[] color, FloatBuffer vertexBuffer,
            int firstVertex, int vertexCount, int coordsPerVertex, int vertexStride) {
        GlUtil.checkGlError("draw start");

        // 激活 program；后续 uniform、attribute 和 draw call 都作用于它。
        GLES20.glUseProgram(mProgramHandle);
        GlUtil.checkGlError("glUseProgram");

        // 将 CPU 端的 4x4 矩阵上传到顶点 Shader 的 uniform。
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
        GlUtil.checkGlError("glUniformMatrix4fv");

        // 将 RGBA 颜色上传到片元 Shader；每个片元都会使用这一个颜色。
        GLES20.glUniform4fv(muColorLoc, 1, color, 0);
        GlUtil.checkGlError("glUniform4fv ");

        // 打开顶点位置 attribute。
        GLES20.glEnableVertexAttribArray(maPositionLoc);
        GlUtil.checkGlError("glEnableVertexAttribArray");

        // 描述 vertexBuffer 的布局：分量类型为 float，stride 用字节表示。
        GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex,
            GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);
        GlUtil.checkGlError("glVertexAttribPointer");

        // 以 triangle strip 解释顶点数据并提交绘制。
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount);
        GlUtil.checkGlError("glDrawArrays");

        // 清理本次调用打开的状态，避免污染下一个绘制程序。
        GLES20.glDisableVertexAttribArray(maPositionLoc);
        GLES20.glUseProgram(0);
    }
}
