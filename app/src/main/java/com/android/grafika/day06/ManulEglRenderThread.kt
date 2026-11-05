package com.android.grafika.day06

import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.android.grafika.gles.EglCore
import com.android.grafika.gles.GlUtil
import com.android.grafika.gles.WindowSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ManulEglRenderThread(
    private val surface: Surface
) : Thread("ManualEglRenderThread") {
    @Volatile
    private var running = true

    @Volatile
    private var surfaceWidth = 1

    @Volatile
    private var surfaceHeight = 1

    private var eglCore: EglCore? = null
    private var windowSurface: WindowSurface? = null

    private var program = 0
    private var vertexBuffer: FloatBuffer? = null

    private var aPositionLoc = -1
    private var uMatrixLoc = -1
    private var uColorLoc = -1
    private val modelMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var rotation = 0f

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        uniform mat4 uMatrix;

        void main() {
            gl_Position = uMatrix * aPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;

        uniform vec4 uColor;

        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    private val vertices = floatArrayOf(
        0.0f, 0.6f, 0.0f,
        -0.6f, -0.6f, 0.0f,
        0.6f, -0.6f, 0.0f
    )

    fun setSurfaceSize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    fun requestExitAndWait() {
        running = false
        interrupt()
        if (currentThread() != this) {
            join()
        }
    }

    override fun run() {
        super.run()
        try {
            createEglEnvironment()

            var lastFrameTimeNs = System.nanoTime()
            while (running) {
                val nowNs = System.nanoTime()
                val deltaSeconds = (nowNs - lastFrameTimeNs) / 1_000_000_000f

                drawFrame(deltaSeconds)

                val currentWindowSurface = windowSurface ?: break
                if (!currentWindowSurface.swapBuffers()) {
                    break
                }

                val frameCostNs = System.nanoTime() - nowNs
                val sleepNs = 16_666_667L - frameCostNs

                if (sleepNs > 0L) {
                    try {
                        val sleepMs = sleepNs / 1_000_000
                        val sleepNanos = (sleepNs % 1_000_000).toInt()
                        sleep(sleepMs, sleepNanos)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        } finally {
            releaseEglEnvironment()
        }
    }

    private fun createEglEnvironment() {
        val core = EglCore(null, EglCore.FLAG_TRY_GLES3)

        val surfaceWrapper = WindowSurface(core, surface, false)
        surfaceWrapper.makeCurrent()
        eglCore = core
        windowSurface = surfaceWrapper

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
        program = GlUtil.createProgram(vertexShaderCode, fragmentShaderCode)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        uMatrixLoc = GLES20.glGetUniformLocation(program, "uMatrix")
        uColorLoc = GLES20.glGetUniformLocation(program, "uColor")

        if (aPositionLoc < 0) {
            throw RuntimeException("找不到 aPosition 属性")
        }

        if (uMatrixLoc < 0) {
            throw RuntimeException("找不到 uMatrix uniform")
        }

        if (uColorLoc < 0) {
            throw RuntimeException("找不到 uColor uniform")
        }
    }

    private fun drawFrame(deltaSeconds: Float) {
        val width = surfaceWidth.coerceAtLeast(1)
        val height = surfaceHeight.coerceAtLeast(1)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.08f, 0.08f, 0.08f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        rotation += 90f * deltaSeconds
        if (rotation >= 360f) {
            rotation -= 360f
        }
        val aspectRatio = width.toFloat() / height.toFloat()

        Matrix.setIdentityM(projectionMatrix, 0)
        if (width >= height) {
            Matrix.orthoM(
                projectionMatrix,
                0,
                -aspectRatio,
                aspectRatio,
                -1.0f,
                1.0f,
                -1.0f,
                1.0f
            )
        } else {
            Matrix.orthoM(
                projectionMatrix,
                0,
                -1.0f,
                1.0f,
                -1.0f / aspectRatio,
                1.0f / aspectRatio,
                -1.0f,
                1.0f
            )
        }
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotation, 0.0f, 0.0f, 1.0f)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(uColorLoc, 0.2f, 0.8f, 1.0f, 1.0f)

        val buffer = vertexBuffer ?: throw IllegalStateException("vertexBuffer 尚未初始化")
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glUseProgram(0)
    }

    private fun releaseEglEnvironment() {
        try {
            GLES20.glDeleteProgram(program)
        } catch (_: Exception) {
        }
        val surfaceWrapper = windowSurface
        val core = eglCore

        windowSurface = null
        eglCore = null

        try {
            core?.makeNothingCurrent()
        } catch (_: Exception) {
        }

        try {
            surfaceWrapper?.release()
        } catch (_: Exception) {
        }

        try {
            core?.release()
        } catch (_: Exception) {
        }
    }
}