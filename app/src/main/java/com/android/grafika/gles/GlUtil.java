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
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL ES 常用辅助方法集合。
 *
 * <p>本类集中处理底层重复工作：编译/链接 Shader、创建 2D 纹理、创建 native
 * {@link FloatBuffer}、检查 GL 错误以及打印驱动信息。它不保存绘制状态，也不负责管理 EGL
 * context；所有 GL 方法都要求调用线程已经绑定了正确的 EGL context。</p>
 */
public class GlUtil {
    /** Grafika 项目的统一日志标签。 */
    public static final String TAG = "Grafika";

    /**
     * 通用单位矩阵。
     *
     * <p>这是一个共享数组，调用方不得修改；如果需要修改矩阵，应先复制一份。修改它会
     * 影响所有把该对象作为默认 MVP 或纹理矩阵的绘制调用。</p>
     */
    public static final float[] IDENTITY_MATRIX;
    static {
        IDENTITY_MATRIX = new float[16];
        Matrix.setIdentityM(IDENTITY_MATRIX, 0);
    }

    /** 一个 float 在 ByteBuffer 中占用的字节数。 */
    private static final int SIZEOF_FLOAT = 4;


    /** 工具类不允许实例化。 */
    private GlUtil() {}

    /**
     * 编译顶点/片元 Shader 并链接成一个 GL program。
     *
     * <p>调用链为：{@link #loadShader(int, String)} 编译两个 Shader → 创建 program →
     * attach → link。链接失败会输出驱动提供的日志并返回 0。成功返回的句柄属于当前 EGL
     * context，不能跨不共享资源的 context 使用。</p>
     *
     * @param vertexSource 顶点 Shader 源码
     * @param fragmentSource 片元 Shader 源码
     * @return program 句柄；失败返回 0
     */
    public static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        checkGlError("glCreateProgram");
        if (program == 0) {
            Log.e(TAG, "Could not create program");
        }
        GLES20.glAttachShader(program, vertexShader);
        checkGlError("glAttachShader");
        GLES20.glAttachShader(program, pixelShader);
        checkGlError("glAttachShader");
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ");
            Log.e(TAG, GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    /**
     * 编译一段 GLSL Shader 源码。
     *
     * <p>Shader 编译失败时会打印 {@code glGetShaderInfoLog()} 返回的具体原因，并删除失败
     * 的 Shader 对象，避免留下无效资源。</p>
     *
     * @param shaderType {@link GLES20#GL_VERTEX_SHADER} 或 {@link GLES20#GL_FRAGMENT_SHADER}
     * @param source GLSL 源码
     * @return Shader 句柄；失败返回 0
     */
    public static int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        checkGlError("glCreateShader type=" + shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + shaderType + ":");
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    /**
     * 检查 GLES 错误队列。
     *
     * <p>OpenGL ES 很多 API 不通过 Java 异常报告错误，而是把错误码放入线程级错误队列；
     * 本方法读取并清除最近一次错误，发现错误立即抛出异常，便于定位出错的 GL 调用。</p>
     *
     * @param op 当前正在检查的操作描述
     */
    public static void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            String msg = op + ": glError 0x" + Integer.toHexString(error);
            Log.e(TAG, msg);
            throw new RuntimeException(msg);
        }
    }

    /**
     * 检查 Shader attribute/uniform 的位置是否有效。
     *
     * <p>{@code glGetAttribLocation()} 或 {@code glGetUniformLocation()} 找不到变量时返回 -1，
     * 但不会设置 GL 错误，因此必须单独检查。变量也可能因编译器优化而被移除。</p>
     *
     * @param location 要检查的变量位置
     * @param label 变量名，用于异常信息
     */
    public static void checkLocation(int location, String label) {
        if (location < 0) {
            throw new RuntimeException("Unable to locate '" + label + "' in program");
        }
    }

    /**
     * 从原始像素数据创建一个 GL_TEXTURE_2D 纹理。
     *
     * <p>方法会生成纹理对象、绑定它、设置缩放过滤方式，然后通过 glTexImage2D() 上传像素。
     * 过滤采用线性采样，未创建 mipmap；如果纹理被大幅缩放，采样质量和性能取决于驱动实现。</p>
     *
     * @param data 图像数据，必须是 direct ByteBuffer，格式由 {@code format} 指定
     * @param width 纹理宽度，单位为像素而不是字节
     * @param height 纹理高度，单位为像素
     * @param format 与 glTexImage2D() 对应的像素格式，例如 {@link GLES20#GL_RGBA}
     * @return GL 纹理句柄
     */
    public static int createImageTexture(ByteBuffer data, int width, int height, int format) {
        int[] textureHandles = new int[1];
        int textureHandle;

        GLES20.glGenTextures(1, textureHandles, 0);
        textureHandle = textureHandles[0];
        GlUtil.checkGlError("glGenTextures");

        // 将纹理句柄绑定到 2D 纹理目标；后续参数设置和像素上传都作用于此对象。
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle);

        // 设置缩小和放大过滤方式：纹理尺寸与屏幕像素不一致时如何计算采样颜色。
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GlUtil.checkGlError("loadImageTexture");

        // 将 CPU 端像素数据上传到 GPU 纹理存储。
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, /*level*/ 0, format,
                width, height, /*border*/ 0, format, GLES20.GL_UNSIGNED_BYTE, data);
        GlUtil.checkGlError("loadImageTexture");

        return textureHandle;
    }

    /**
     * 分配一个 native-order 的 direct FloatBuffer，并复制数组内容。
     *
     * <p>Android GLES 的顶点接口要求能够被 native 层访问的 Buffer；普通 Java 数组不能
     * 直接作为 {@code glVertexAttribPointer()} 的数据源，所以这里先申请 direct ByteBuffer，
     * 再视为 FloatBuffer。</p>
     *
     * @param coords 要复制的 float 数据
     * @return position 已重置为 0 的 direct FloatBuffer
     */
    public static FloatBuffer createFloatBuffer(float[] coords) {
        // 分配 direct ByteBuffer，每个 float 占 4 字节，并把坐标复制进去。
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * SIZEOF_FLOAT);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }

    /** 将当前 GL context 的厂商、渲染器和版本信息输出到日志。 */
    public static void logVersionInfo() {
        Log.i(TAG, "vendor  : " + GLES20.glGetString(GLES20.GL_VENDOR));
        Log.i(TAG, "renderer: " + GLES20.glGetString(GLES20.GL_RENDERER));
        Log.i(TAG, "version : " + GLES20.glGetString(GLES20.GL_VERSION));

        if (false) {
            // 保留的 GLES 3 版本查询示例；当前关闭以兼容 GLES 2 context。
            int[] values = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, values, 0);
            int majorVersion = values[0];
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, values, 0);
            int minorVersion = values[0];
            if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                Log.i(TAG, "iversion: " + majorVersion + "." + minorVersion);
            }
        }
    }
}
