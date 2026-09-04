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

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.FloatBuffer;

/**
 * 用纹理绘制二维图形的 OpenGL ES program 封装。
 *
 * <p>本类统一了四种纹理绘制模式：普通 2D 纹理、SurfaceTexture 使用的外部纹理、
 * 外部纹理黑白效果以及 3x3 卷积滤镜效果。顶点 Shader 负责把顶点位置变换到裁剪空间，
 * 并变换纹理坐标；片元 Shader 再按具体模式采样纹理并计算最终颜色。</p>
 *
 * <p>每个 program、纹理对象和 attribute/uniform 位置都依赖当前 EGL context。构造、绘制、
 * 修改滤镜参数和释放均应在正确的 context current 时执行。</p>
 */
public class Texture2dProgram {
    /** 日志标签。 */
    private static final String TAG = GlUtil.TAG;

    /**
     * 片元处理模式。
     *
     * <ul>
     *     <li>{@link #TEXTURE_2D}：采样普通 GL_TEXTURE_2D。</li>
     *     <li>{@link #TEXTURE_EXT}：采样 GL_TEXTURE_EXTERNAL_OES，常用于 SurfaceTexture。</li>
     *     <li>{@link #TEXTURE_EXT_BW}：外部纹理转灰度。</li>
     *     <li>{@link #TEXTURE_EXT_FILT}：外部纹理执行 3x3 卷积，并显示对照区域。</li>
     * </ul>
     */
    public enum ProgramType {
        TEXTURE_2D, TEXTURE_EXT, TEXTURE_EXT_BW, TEXTURE_EXT_FILT
    }

    /**
     * 所有模式共用的顶点 Shader。
     *
     * <p>{@code uMVPMatrix} 变换顶点位置；{@code uTexMatrix} 变换纹理坐标。后者可以
     * 校正 SurfaceTexture 的旋转、镜像或裁剪，变换后的坐标通过 varying 插值到片元 Shader。</p>
     */
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    /** 普通 GL_TEXTURE_2D 的片元 Shader，直接读取采样颜色。 */
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    /**
     * 外部纹理的片元 Shader。
     *
     * <p>{@code samplerExternalOES} 和扩展声明是 SurfaceTexture、Camera、视频解码器等
     * 外部图像源的关键；这种纹理不能当作普通 GL_TEXTURE_2D 使用。</p>
     */
    private static final String FRAGMENT_SHADER_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    /** 外部纹理转黑白的片元 Shader，使用感知亮度近似公式。 */
    private static final String FRAGMENT_SHADER_EXT_BW =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    vec4 tc = texture2D(sTexture, vTextureCoord);\n" +
            "    float color = tc.r * 0.3 + tc.g * 0.59 + tc.b * 0.11;\n" +
            "    gl_FragColor = vec4(color, color, color, 1.0);\n" +
            "}\n";

    /**
     * 外部纹理卷积滤镜的片元 Shader。
     *
     * <p>它把当前纹理坐标周围 3x3 的 9 个采样点分别乘以 {@code uKernel} 中的权重后求和，
     * 再加上 {@code uColorAdjust}。为了演示滤镜效果，按纹理坐标的对角线把画面分为两部分：
     * 一侧使用滤镜，一侧直接采样，中间用红线分隔。</p>
     *
     * <p>这是教学示例而非高性能实现。条件分支、循环和 uniform 数组都会增加片元开销，
     * 生产代码通常会根据固定 kernel 展开循环并减少分支。</p>
     */
    public static final int KERNEL_SIZE = 9;
    private static final String FRAGMENT_SHADER_EXT_FILT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#define KERNEL_SIZE " + KERNEL_SIZE + "\n" +
            "precision highp float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform float uKernel[KERNEL_SIZE];\n" +
            "uniform vec2 uTexOffset[KERNEL_SIZE];\n" +
            "uniform float uColorAdjust;\n" +
            "void main() {\n" +
            "    int i = 0;\n" +
            "    vec4 sum = vec4(0.0);\n" +
            "    if (vTextureCoord.x < vTextureCoord.y - 0.005) {\n" +
            "        for (i = 0; i < KERNEL_SIZE; i++) {\n" +
            "            vec4 texc = texture2D(sTexture, vTextureCoord + uTexOffset[i]);\n" +
            "            sum += texc * uKernel[i];\n" +
            "        }\n" +
            "    sum += uColorAdjust;\n" +
            "    } else if (vTextureCoord.x > vTextureCoord.y + 0.005) {\n" +
            "        sum = texture2D(sTexture, vTextureCoord);\n" +
            "    } else {\n" +
            "        sum.r = 1.0;\n" +
            "    }\n" +
            "    gl_FragColor = sum;\n" +
            "}\n";

    /** 当前 program 的工作模式。 */
    private ProgramType mProgramType;

    /** GL program 句柄以及其中各 attribute/uniform 的位置。 */
    private int mProgramHandle;
    private int muMVPMatrixLoc;
    private int muTexMatrixLoc;
    private int muKernelLoc;
    private int muTexOffsetLoc;
    private int muColorAdjustLoc;
    private int maPositionLoc;
    private int maTextureCoordLoc;

    /** 当前 program 应绑定的纹理目标：GL_TEXTURE_2D 或 GL_TEXTURE_EXTERNAL_OES。 */
    private int mTextureTarget;

    /** 卷积核的 9 个权重；非滤镜模式不会使用。 */
    private float[] mKernel = new float[KERNEL_SIZE];
    /** 3x3 邻域中各采样点相对当前坐标的偏移，按 x/y 成对排列。 */
    private float[] mTexOffset;
    /** 卷积结果的颜色修正量。 */
    private float mColorAdjust;


    /**
     * 在当前 EGL context 中选择并创建指定类型的纹理 program。
     *
     * <p>构造过程中会编译/链接 Shader，并查询所有 attribute/uniform 的位置。滤镜模式还会
     * 初始化一个“中心点权重为 1”的恒等卷积核，因此初始效果等同于直接采样。</p>
     *
     * @param programType 要创建的纹理处理模式
     */
    public Texture2dProgram(ProgramType programType) {
        mProgramType = programType;

        switch (programType) {
            case TEXTURE_2D:
                mTextureTarget = GLES20.GL_TEXTURE_2D;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
                break;
            case TEXTURE_EXT:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT);
                break;
            case TEXTURE_EXT_BW:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_BW);
                break;
            case TEXTURE_EXT_FILT:
                mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                mProgramHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_EXT_FILT);
                break;
            default:
                throw new RuntimeException("Unhandled type " + programType);
        }
        if (mProgramHandle == 0) {
            throw new RuntimeException("Unable to create program");
        }
        Log.d(TAG, "Created program " + mProgramHandle + " (" + programType + ")");

        // 查询 Shader 中各输入变量的位置；位置是 program 链接后的句柄，不是固定数字。

        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        GlUtil.checkLocation(maPositionLoc, "aPosition");
        maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord");
        GlUtil.checkLocation(maTextureCoordLoc, "aTextureCoord");
        muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
        GlUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");
        muTexMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
        GlUtil.checkLocation(muTexMatrixLoc, "uTexMatrix");
        muKernelLoc = GLES20.glGetUniformLocation(mProgramHandle, "uKernel");
        if (muKernelLoc < 0) {
            // 非滤镜 program 没有卷积相关 uniform，用 -1 表示 draw 时跳过上传。
            muKernelLoc = -1;
            muTexOffsetLoc = -1;
            muColorAdjustLoc = -1;
        } else {
            // 滤镜 program 必须同时包含卷积权重、邻域偏移和颜色修正参数。
            muTexOffsetLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexOffset");
            GlUtil.checkLocation(muTexOffsetLoc, "uTexOffset");
            muColorAdjustLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColorAdjust");
            GlUtil.checkLocation(muColorAdjustLoc, "uColorAdjust");

            // 默认使用中心权重为 1 的恒等核，避免刚创建时出现未定义滤镜效果。
            setKernel(new float[] {0f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 0f}, 0f);
            setTexSize(256, 256);
        }
    }

    /** 删除 GL program；调用时必须使创建它的 EGL context current。 */
    public void release() {
        Log.d(TAG, "deleting program " + mProgramHandle);
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;
    }

    /** 返回当前 program 的纹理处理模式。 */
    public ProgramType getProgramType() {
        return mProgramType;
    }

    /**
     * 创建适用于当前 program 的纹理对象。
     *
     * <p>返回时该纹理仍处于绑定状态，调用方可以继续为它配置数据或将其交给
     * SurfaceTexture.attachToGLContext()。外部纹理由图像生产者提供像素，普通 2D 纹理则
     * 可以再调用 glTexImage2D() 上传像素。</p>
     *
     * @return 新建的 GL 纹理句柄
     */
    public int createTextureObject() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GlUtil.checkGlError("glGenTextures");

        int texId = textures[0];
        GLES20.glBindTexture(mTextureTarget, texId);
        GlUtil.checkGlError("glBindTexture " + texId);

        // 纹理放大使用线性过滤，缩小使用最近邻；外部纹理通常要求边缘采用 clamp。
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GlUtil.checkGlError("glTexParameter");

        return texId;
    }

    /**
     * 设置卷积滤镜的 3x3 权重和颜色修正量。
     *
     * <p>数组按从左上到右下的顺序排列，共 9 个值。卷积结果大致为：
     * {@code sum(texture(uv + offset[i]) * values[i]) + colorAdj}。</p>
     *
     * @param values 归一化卷积权重，长度必须为 {@link #KERNEL_SIZE}
     * @param colorAdj 添加到卷积结果的颜色修正量
     */
    public void setKernel(float[] values, float colorAdj) {
        if (values.length != KERNEL_SIZE) {
            throw new IllegalArgumentException("Kernel size is " + values.length +
                    " vs. " + KERNEL_SIZE);
        }
        System.arraycopy(values, 0, mKernel, 0, KERNEL_SIZE);
        mColorAdjust = colorAdj;
        //Log.d(TAG, "滤镜 kernel: " + Arrays.toString(mKernel) + ", adj=" + colorAdj);
    }

    /**
     * 设置被滤镜处理的纹理尺寸，用于计算相邻 texel 的归一化偏移。
     *
     * <p>一个 texel 在纹理坐标中的宽度是 {@code 1 / width}，高度是 {@code 1 / height}。
     * 例如中心点左上方的采样偏移就是 (-1/width, -1/height)。</p>
     *
     * @param width 纹理宽度，必须大于 0
     * @param height 纹理高度，必须大于 0
     */
    public void setTexSize(int width, int height) {
        float rw = 1.0f / width;
        float rh = 1.0f / height;

        // 9 个 vec2 按连续的 x、y 浮点数排列，顺序对应 3x3 邻域的左上到右下。
        mTexOffset = new float[] {
            -rw, -rh,   0f, -rh,    rw, -rh,
            -rw, 0f,    0f, 0f,     rw, 0f,
            -rw, rh,    0f, rh,     rw, rh
        };
        //Log.d(TAG, "滤镜纹理尺寸: " + width + "x" + height + ": " + Arrays.toString(mTexOffset));
    }

    /**
     * 配置 GL 状态并绘制一组带纹理坐标的顶点。
     *
     * <p>每次调用的主要流程是：激活 program → 绑定纹理 → 上传 MVP 与纹理矩阵 →
     * 启用并描述位置/纹理坐标 attribute →（如果是滤镜模式）上传卷积参数 →
     * 以 triangle strip 发出 draw call → 清理本次使用的状态。</p>
     *
     * @param mvpMatrix 4x4 模型-视图-投影矩阵，决定图形在 viewport 中的位置和大小
     * @param vertexBuffer 顶点位置数据
     * @param firstVertex 顶点起始索引
     * @param vertexCount 要绘制的顶点数量
     * @param coordsPerVertex 每个位置顶点的分量数量，例如 x、y 为 2
     * @param vertexStride 相邻位置顶点之间的字节步长
     * @param texMatrix 4x4 纹理坐标矩阵，常用于 SurfaceTexture 的方向校正
     * @param texBuffer 顶点纹理坐标数据
     * @param textureId 要绑定并采样的纹理句柄
     * @param texStride 相邻纹理坐标之间的字节步长
     */
    public void draw(float[] mvpMatrix, FloatBuffer vertexBuffer, int firstVertex,
            int vertexCount, int coordsPerVertex, int vertexStride,
            float[] texMatrix, FloatBuffer texBuffer, int textureId, int texStride) {
        GlUtil.checkGlError("draw start");

        // 选择当前 program；uniform 和 attribute 都属于这个 program。
        GLES20.glUseProgram(mProgramHandle);
        GlUtil.checkGlError("glUseProgram");

        // 激活纹理单元 0，并按 program 类型绑定普通纹理或外部纹理。
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(mTextureTarget, textureId);

        // 上传顶点位置的 MVP 矩阵，顶点 Shader 用它计算 gl_Position。
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
        GlUtil.checkGlError("glUniformMatrix4fv");

        // 上传纹理坐标矩阵，修正 SurfaceTexture 常见的旋转、镜像和 Y 方向。
        GLES20.glUniformMatrix4fv(muTexMatrixLoc, 1, false, texMatrix, 0);
        GlUtil.checkGlError("glUniformMatrix4fv");

        // 启用位置 attribute。
        GLES20.glEnableVertexAttribArray(maPositionLoc);
        GlUtil.checkGlError("glEnableVertexAttribArray");

        // 告诉 GL 如何从 vertexBuffer 读取每个顶点的位置分量。
        GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex,
            GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);
        GlUtil.checkGlError("glVertexAttribPointer");

        // 启用纹理坐标 attribute。
        GLES20.glEnableVertexAttribArray(maTextureCoordLoc);
        GlUtil.checkGlError("glEnableVertexAttribArray");

        // 告诉 GL 如何从 texBuffer 读取每个顶点的 s、t 坐标。
        GLES20.glVertexAttribPointer(maTextureCoordLoc, 2,
                GLES20.GL_FLOAT, false, texStride, texBuffer);
            GlUtil.checkGlError("glVertexAttribPointer");

        // 只有滤镜 Shader 声明了这些 uniform 时才上传卷积参数。
        if (muKernelLoc >= 0) {
            GLES20.glUniform1fv(muKernelLoc, KERNEL_SIZE, mKernel, 0);
            GLES20.glUniform2fv(muTexOffsetLoc, KERNEL_SIZE, mTexOffset, 0);
            GLES20.glUniform1f(muColorAdjustLoc, mColorAdjust);
        }

        // 用 triangle strip 绘制；FullFrameRect 和 Drawable2d 的矩形都采用这种拓扑。
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount);
        GlUtil.checkGlError("glDrawArrays");

        // 关闭本次开启的 attribute，解绑纹理和 program，减少 GL 状态泄漏。
        GLES20.glDisableVertexAttribArray(maPositionLoc);
        GLES20.glDisableVertexAttribArray(maTextureCoordLoc);
        GLES20.glBindTexture(mTextureTarget, 0);
        GLES20.glUseProgram(0);
    }
}
