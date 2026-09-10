/*
 * Copyright 2026 Google LLC. All rights reserved.
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

package com.android.grafika;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 使用 OpenGL ES 2.0 绘制彩色三角形的最小完整示例。
 *
 * <p>这个 Activity 有意不使用 XML 布局，而是在 {@link #onCreate(Bundle)} 中直接创建
 * {@link GLSurfaceView}。这样可以把学习重点集中在 OpenGL ES 的核心流程：</p>
 *
 * <ol>
 *     <li>创建 GLSurfaceView，并声明使用 GLES 2.0；</li>
 *     <li>设置 Renderer；GLSurfaceView 会在自己的 GL 线程中回调 Renderer；</li>
 *     <li>在 {@link TriangleRenderer#onSurfaceCreated(GL10, EGLConfig)} 中创建着色器程序
 *         和顶点缓冲区；</li>
 *     <li>在 {@link TriangleRenderer#onSurfaceChanged(GL10, int, int)} 中设置 viewport；</li>
 *     <li>在 {@link TriangleRenderer#onDrawFrame(GL10)} 中清屏、绑定 program、配置顶点属性，
 *         最后调用 {@code glDrawArrays()} 绘制三角形。</li>
 * </ol>
 *
 * <p>线程模型很重要：{@link GLSurfaceView.Renderer} 的三个回调运行在 GLSurfaceView
 * 创建的 GL 渲染线程，而不是 Activity/UI 线程。OpenGL ES 对当前线程绑定的 EGLContext
 * 有依赖，因此 GL 对象（shader、program、buffer 等）应在 Renderer 回调中创建和使用，
 * 不要在 Activity 的 UI 线程中直接调用 GL API。</p>
 *
 * <p>本示例使用 GLES 2.0 的可编程渲染管线。与固定功能管线不同，应用需要自己提供：</p>
 *
 * <ul>
 *     <li>顶点着色器（vertex shader）：处理每个顶点的位置；</li>
 *     <li>片元着色器（fragment shader）：计算每个像素的颜色；</li>
 *     <li>顶点数据：这里每个顶点包含 3 个坐标分量和 4 个颜色分量；</li>
 *     <li>顶点属性绑定：告诉 OpenGL ES 如何从 FloatBuffer 读取 position/color。</li>
 * </ul>
 *
 * <p>三角形坐标直接使用归一化设备坐标（NDC）：x、y、z 通常位于 -1 到 1 的范围内。
 * 经过顶点着色器输出后，OpenGL 会把它们映射到当前 viewport。这里不使用投影矩阵，
 * 因此它是一个非常适合入门的“从顶点数据到屏幕像素”示例。</p>
 */
public class OpenGlesTriangleActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    /** GLSurfaceView 负责 EGLSurface、GL 线程和 Renderer 回调。 */
    private GLSurfaceView mGlSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // GLSurfaceView 是一个可以承载 OpenGL ES 输出的 View。它内部会创建用于显示的
        // Surface，并管理与该 Surface 关联的 EGL 环境。
        mGlSurfaceView = new GLSurfaceView(this);

        // setEGLContextClientVersion() 必须在 setRenderer() 之前调用。
        // GLES 2.0 支持 vertex shader、fragment shader、attribute、varying 等概念；
        // 本示例的 shader 代码就是 GLES 2.0 写法。
        mGlSurfaceView.setEGLContextClientVersion(2);

        // Renderer 的回调会运行在 GLSurfaceView 的 GL 渲染线程。
        mGlSurfaceView.setRenderer(new TriangleRenderer());

        // 持续绘制模式会不断调用 onDrawFrame()。即使三角形是静态的，这里也保留连续
        // 模式，便于初学者观察“每一帧都会清屏和重新绘制”的渲染循环。
        mGlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // 直接把 GLSurfaceView 作为 Activity 的内容视图，不需要额外 XML 布局。
        setContentView(mGlSurfaceView);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 恢复 GLSurfaceView 的渲染线程和 EGL 生命周期。对应的 onPause() 会暂停渲染，
        // 避免 Activity 不可见时继续占用 GPU 资源。
        if (mGlSurfaceView != null) {
            mGlSurfaceView.onResume();
        }
    }

    @Override
    protected void onPause() {
        // 先暂停 GLSurfaceView，再让 Activity 继续执行暂停流程。GLSurfaceView 会处理
        // 渲染线程与 EGLSurface 的暂停/恢复。
        if (mGlSurfaceView != null) {
            mGlSurfaceView.onPause();
        }
        super.onPause();
    }

    /**
     * GLSurfaceView 的 Renderer：把 Java 顶点数据送入 GPU，并发起绘制命令。
     *
     * <p>Renderer 中的状态分为两类：</p>
     * <ul>
     *     <li>GL 资源：program、shader、GPU buffer，只能在 GL context 有效时使用；</li>
     *     <li>每帧状态：清屏颜色、vertex attribute 指针和绘制数量，在 onDrawFrame() 中设置。</li>
     * </ul>
     */
    private static class TriangleRenderer implements GLSurfaceView.Renderer {
        /** 每个顶点由 x/y/z 三个坐标和 r/g/b/a 四个颜色分量组成。 */
        private static final int FLOATS_PER_VERTEX = 7;
        /** 一个 float 占 4 字节；glVertexAttribPointer 的 stride 使用字节数。 */
        private static final int BYTES_PER_FLOAT = 4;
        /** 交错数据中颜色从第 4 个 float（下标 3）开始。 */
        private static final int COLOR_OFFSET_FLOATS = 3;

        /**
         * 顶点着色器：
         *
         * <p>{@code aPosition} 是每个顶点的输入属性。把它直接赋给 {@code gl_Position}
         * 表示顶点数据已经是裁剪空间/NDC 坐标，不需要矩阵变换。
         * {@code aColor} 通过 varying 传给片元着色器；GPU 会在三角形内部自动插值颜色。</p>
         */
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n"
                        + "attribute vec4 aColor;\n"
                        + "varying vec4 vColor;\n"
                        + "void main() {\n"
                        + "    gl_Position = aPosition;\n"
                        + "    vColor = aColor;\n"
                        + "}\n";

        /**
         * 片元着色器：
         *
         * <p>{@code precision mediump float} 声明 float 精度。移动 GPU 通常要求片元
         * shader 明确声明 float 精度，否则可能编译失败。{@code vColor} 是经过三角形
         * 内插值后的颜色，赋给 {@code gl_FragColor} 即成为最终像素颜色。</p>
         */
        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n"
                        + "varying vec4 vColor;\n"
                        + "void main() {\n"
                        + "    gl_FragColor = vColor;\n"
                        + "}\n";

        /** CPU 侧保存交错顶点数据的直接缓冲区。 */
        private FloatBuffer mVertexBuffer;
        /** 链接后的 shader program ID。0 表示尚未创建。 */
        private int mProgram;
        /** program 中 aPosition 属性的位置。 */
        private int mPositionLocation;
        /** program 中 aColor 属性的位置。 */
        private int mColorLocation;

        /**
         * 交错顶点数组：
         *
         * <pre>
         *     x,    y,    z,    r,   g,   b,   a,
         *     ... 一个顶点 7 个 float，连续存放三个顶点 ...
         * </pre>
         *
         * <p>坐标使用 NDC。OpenGL 的 y 轴向上，三角形顶点分别位于上方、左下方和右下方。
         * 三个顶点使用红、绿、蓝三种颜色，片元颜色会在三角形内部平滑插值。</p>
         */
        private static final float[] TRIANGLE_VERTICES = {
                // position             // color (RGBA)
                 0.0f,  0.70f, 0.0f,     1.0f, 0.0f, 0.0f, 1.0f,
                -0.70f, -0.55f, 0.0f,     0.0f, 1.0f, 0.0f, 1.0f,
                 0.70f, -0.55f, 0.0f,     0.0f, 0.0f, 1.0f, 1.0f,
        };

        /**
         * 首次创建 EGLContext 后调用一次。
         *
         * <p>这里创建所有依赖 GL context 的对象：顶点缓冲区、shader 和 program。不要把
         * {@code glCreateShader()} 或 {@code glCreateProgram()} 放到 Activity/UI 线程中，
         * 因为 UI 线程通常没有当前的 EGLContext。</p>
         */
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            // 设置每帧 glClear() 使用的背景色。RGBA 的取值范围是 0.0 到 1.0。
            GLES20.glClearColor(0.08f, 0.08f, 0.12f, 1.0f);

            // allocateDirect() 创建的内存可以被 JNI/OpenGL 更高效地访问；
            // nativeOrder() 确保 float 的字节序与设备 CPU 一致。
            mVertexBuffer = ByteBuffer
                    .allocateDirect(TRIANGLE_VERTICES.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            mVertexBuffer.put(TRIANGLE_VERTICES);
            mVertexBuffer.position(0);

            // 编译并链接 shader。program 是 vertex shader 和 fragment shader 的组合，
            // 后续 glUseProgram() 会选择它作为当前绘制管线。
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            mProgram = linkProgram(vertexShader, fragmentShader);

            // shader 已经链接进 program，单独的 shader 对象可以删除；program 仍会保留
            // 链接后的代码和属性信息。
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);

            // getAttribLocation() 查找 shader 中 attribute 变量的位置。location 是由
            // linker 分配的整数，不能假设它固定为 0 或 1。
            mPositionLocation = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mColorLocation = GLES20.glGetAttribLocation(mProgram, "aColor");
            checkGlError("glGetAttribLocation");

            if (mPositionLocation < 0 || mColorLocation < 0) {
                throw new IllegalStateException("shader 中找不到顶点属性");
            }
        }

        /**
         * EGLSurface 尺寸改变或第一次创建时调用。
         *
         * <p>viewport 决定 NDC 如何映射到实际 framebuffer 的像素区域。这里不设置投影
         * 矩阵，因为顶点着色器已经直接输出 NDC；如果以后使用世界坐标/相机坐标，就应
         * 在这里或 Renderer 中准备矩阵，并将其作为 uniform 传入 shader。</p>
         */
        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            checkGlError("glViewport");
        }

        /**
         * 绘制一帧。
         *
         * <p>OpenGL ES 命令通常是异步提交给 GPU 的；这里的调用顺序描述了“要画什么”，
         * 驱动和 GPU 再负责执行。核心顺序是：清屏 → 选择 program → 启用属性 → 指定
         * buffer 中的布局 → drawArrays → 禁用属性。</p>
         */
        @Override
        public void onDrawFrame(GL10 gl) {
            // 清除上一帧的颜色缓冲区。若不清除，上一帧内容可能残留在当前 framebuffer 中。
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 选择刚才链接好的 shader program。后续的顶点绘制会使用它。
            GLES20.glUseProgram(mProgram);

            // position 和 color 都是“顶点属性”，每个顶点各有一份。启用后，
            // glVertexAttribPointer() 指定的缓冲区数据才会被 vertex shader 读取。
            GLES20.glEnableVertexAttribArray(mPositionLocation);
            GLES20.glEnableVertexAttribArray(mColorLocation);

            // 交错布局的一个顶点占 7 个 float，即 28 字节。position 从第 0 个 float 开始，
            // color 从第 3 个 float 开始；stride 告诉 GPU 读取下一个顶点时跨过多少字节。
            mVertexBuffer.position(0);
            GLES20.glVertexAttribPointer(
                    mPositionLocation,
                    3,
                    GLES20.GL_FLOAT,
                    false,
                    FLOATS_PER_VERTEX * BYTES_PER_FLOAT,
                    mVertexBuffer);

            mVertexBuffer.position(COLOR_OFFSET_FLOATS);
            GLES20.glVertexAttribPointer(
                    mColorLocation,
                    4,
                    GLES20.GL_FLOAT,
                    false,
                    FLOATS_PER_VERTEX * BYTES_PER_FLOAT,
                    mVertexBuffer);

            // GL_TRIANGLES 每三个顶点组成一个独立三角形。这里只有 3 个顶点，因此绘制
            // 一个三角形；如果传入 6 个顶点，就会绘制两个互不共享顶点的三角形。
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            checkGlError("glDrawArrays");

            // 当前帧已经提交，关闭 attribute 是良好的状态管理习惯，避免影响后续绘制。
            GLES20.glDisableVertexAttribArray(mPositionLocation);
            GLES20.glDisableVertexAttribArray(mColorLocation);
        }

        /**
         * 编译一个 shader，并在失败时抛出包含编译日志的异常。
         */
        private static int compileShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            if (shader == 0) {
                throw new IllegalStateException("glCreateShader 失败，type=" + shaderType);
            }

            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);

            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("shader 编译失败：" + log);
            }
            return shader;
        }

        /**
         * 把 vertex shader 和 fragment shader 链接为可使用的 program。
         */
        private static int linkProgram(int vertexShader, int fragmentShader) {
            int program = GLES20.glCreateProgram();
            if (program == 0) {
                throw new IllegalStateException("glCreateProgram 失败");
            }

            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);

            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new IllegalStateException("program 链接失败：" + log);
            }
            return program;
        }

        /**
         * 检查最近一次 GL 调用是否产生错误。OpenGL ES 错误不会自动抛出 Java 异常，
         * 因此学习和调试阶段应主动调用 glGetError()。
         */
        private static void checkGlError(String operation) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, operation + ": glError 0x" + Integer.toHexString(error));
                throw new IllegalStateException(operation + ": glError 0x"
                        + Integer.toHexString(error));
            }
        }
    }
}
