package com.android.grafika.day05

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.android.grafika.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Day05Renderer(private val context: Context) : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uMatrix;

        void main() {
            vTexCoord = aTexCoord;
            gl_Position = uMatrix * aPosition;
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
        // 位置          纹理坐标
        -0.5f, 0.5f, 0f, 0f, 0f,  // 左上
        -0.5f, -0.5f, 0f, 0f, 1f,  // 左下
        0.5f, 0.5f, 0f, 1f, 0f,  // 右上

        -0.5f, -0.5f, 0f, 0f, 1f,  // 左下
        0.5f, -0.5f, 0f, 1f, 1f,  // 右下
        0.5f, 0.5f, 0f, 1f, 0f   // 右上
    )

    private lateinit var vertexBuffer: FloatBuffer
    private var program: Int = 0
    private var textureId: Int = 0
    private var uMatrixLoc: Int = 0
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var uTextureLoc: Int = 0

    private val modelMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var rotation: Float = 0f
    private var scale: Float = 1f
    private var translateX: Float = 0f
    private var translateY: Float = 0f

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        GLES20.glClearColor(0.6f, 0.3f, 0.4f, 1.0f)
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

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMatrixLoc = GLES20.glGetUniformLocation(program, "uMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        textureId = loadTexture(context, R.drawable.xx)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        GLES20.glViewport(0, 0, width, height)

        // 创建正交投影矩阵
        val ratio = width.toFloat() / height.toFloat()
        if (width > height) {
            Matrix.orthoM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, -1f, 1f)
        } else {
            Matrix.orthoM(projectionMatrix, 0, -1f, 1f, -1 / ratio, 1 / ratio, -1f, 1f)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, translateX, translateY, 0f)
        Matrix.rotateM(modelMatrix, 0, rotation, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, scale, scale, 1f)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        GLES20.glUseProgram(program)

        GLES20.glUniformMatrix4fv(uMatrixLoc, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun setRotation(rotation: Float) {
        this.rotation = rotation
    }

    fun setScale(scale: Float) {
        this.scale = scale
    }

    fun setTranslate(translateX: Float, translateY: Float) {
        this.translateX = translateX
        this.translateY = translateY
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