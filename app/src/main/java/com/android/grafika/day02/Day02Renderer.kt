package com.android.grafika.day02

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Day02Renderer : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aColor;
        
        uniform vec2 uScale;
        varying mediump vec4 vColor;
        void main() {
            vec4 position = aPosition;

            position.xy *= uScale;
    
            gl_Position = position;
            vColor = aColor;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying mediump vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val vertices = floatArrayOf(
        0.3f, 0.3f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f,
        -0.3f, 0.3f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f,
        0.3f, -0.3f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f,
        -0.3f, 0.3f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f,
        0.3f, -0.3f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f,
        -0.3f, -0.3f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f
    )

    private lateinit var vertexBuffer: FloatBuffer
    private var program: Int = 0
    private var aPositionLocation: Int = 0
    private var aColorLocation: Int = 0
    private var uScaleLocation: Int = 0
    private var scaleX = 1.0f
    private var scaleY = 1.0f

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        aPositionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        aColorLocation = GLES20.glGetAttribLocation(program, "aColor")
        uScaleLocation = GLES20.glGetUniformLocation(program, "uScale")
    }

    override fun onSurfaceChanged(
        p0: GL10?,
        p1: Int,
        p2: Int
    ) {
        GLES20.glViewport(0, 0, p1, p2)
    }

    override fun onDrawFrame(p0: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLocation)

        GLES20.glVertexAttribPointer(
            aPositionLocation,
            3,
            GLES20.GL_FLOAT,
            false,
            7 * 4,
            vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(aColorLocation)
        vertexBuffer.position(3)

        GLES20.glVertexAttribPointer(
            aColorLocation,
            4,
            GLES20.GL_FLOAT,
            false,
            7 * 4,
            vertexBuffer
        )
        GLES20.glUniform2f(uScaleLocation, scaleX, scaleY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aColorLocation)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    fun setScale(x: Float, y: Float) {
        scaleX = x
        scaleY = y
    }
}