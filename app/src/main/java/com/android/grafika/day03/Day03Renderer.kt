package com.android.grafika.day03

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.android.grafika.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Day03Renderer(private val context: Context) : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            gl_Position = aPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private val vertices = floatArrayOf(
        // 位置           纹理坐标
        -1f, 1f, 0f, -1f, 2f,  // 左上
        -1f, -1f, 0f, -1f, -1f,  // 左下
        1f, 1f, 0f, 2f, 2f,  // 右上

        -1f, -1f, 0f, -1f, -1f,  // 左下
        1f, -1f, 0f, 2f, -1f,  // 右下
        1f, 1f, 0f, 2f, 2f   // 右上
    )

    private lateinit var vertexBuffer: FloatBuffer
    private var program: Int = 0
    private var textureId: Int = 0
    private var aPositionLocation: Int = 0
    private var aTexCoordLocation: Int = 0
    private var uTextureLocation: Int = 0

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
        aTexCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLocation = GLES20.glGetUniformLocation(program, "uTexture")
        textureId = loadTexture(context, R.drawable.audio_logo)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLocation, 0)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLocation, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLocation)
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTexCoordLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    fun loadTexture(context: Context, resourceId: Int): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        if (textureIds[0] == 0) {
            throw RuntimeException("Could not generate a new OpenGL texture object.")
        }

        val options = BitmapFactory.Options().apply {
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeResource(context.resources, resourceId, options)
            ?: throw RuntimeException("Error loading texture.")

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_MIRRORED_REPEAT
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_REPEAT
        )

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return textureIds[0]
    }
}