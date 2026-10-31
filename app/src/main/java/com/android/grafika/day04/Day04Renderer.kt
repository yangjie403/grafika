package com.android.grafika.day04

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

class Day04Renderer(private val context: Context) : GLSurfaceView.Renderer {
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
        uniform sampler2D texture1;
        uniform sampler2D texture2;
        uniform float progress;
        uniform float grid_num;

        vec4 getFromColor(vec2 uv) {
            return texture2D(texture1, uv);
        }

        vec4 getToColor(vec2 uv) {
            return texture2D(texture2, uv);
        }

        vec4 transition(vec2 uv) {
            vec2 st = uv * grid_num;
            vec2 idx = floor(st);
            vec2 grid = fract(st);

            vec4 a = getFromColor(uv);
            vec4 b = getToColor(uv);

            const float startProgress = 0.05;
            const float endProgress = 0.95;
            if (progress <= startProgress) return a;
            if (progress >= endProgress) return b;

            float transitionProgress =
                    (progress - startProgress) / (endProgress - startProgress);

            float checker = mod(idx.x + idx.y, 2.0);
            float mixFactor;

            if (transitionProgress <= 0.5) {
                mixFactor = (checker > 0.5)
                        ? step(grid.x, transitionProgress * 2.0)
                        : 0.0;
            } else {
                mixFactor = (checker < 0.5)
                        ? step(grid.x, (transitionProgress - 0.5) * 2.0)
                        : 1.0;
            }

            return mix(a, b, mixFactor);
        }

        void main() {
            gl_FragColor = transition(vTexCoord);
        }
    """.trimIndent()

    private val vertices = floatArrayOf(
        // 位置           纹理坐标
        -1f, 1f, 0f, 0f, 1f,  // 左上
        -1f, -1f, 0f, 0f, 0f,  // 左下
        1f, 1f, 0f, 1f, 1f,  // 右上

        -1f, -1f, 0f, 0f, 0f,  // 左下
        1f, -1f, 0f, 1f, 0f,  // 右下
        1f, 1f, 0f, 1f, 1f   // 右上
    )

    private lateinit var vertexBuffer: FloatBuffer
    private var program: Int = 0
    private var texture1Id: Int = 0
    private var texture2Id: Int = 0
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var tex1Loc: Int = 0
    private var tex2Loc: Int = 0
    private var progressLoc: Int = 0
    private var gridNumLoc: Int = 0
    @Volatile
    private var progress = 0.0f

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        GLES20.glClearColor(0.2f, 0.4f, 0.3f, 1.0f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        tex1Loc = GLES20.glGetUniformLocation(program, "texture1")
        tex2Loc = GLES20.glGetUniformLocation(program, "texture2")
        progressLoc = GLES20.glGetUniformLocation(program, "progress")
        gridNumLoc = GLES20.glGetUniformLocation(program, "grid_num")
        texture1Id = loadTexture(context, R.drawable.audio_logo)
        texture2Id = loadTexture(context, R.drawable.xx)
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

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture1Id)
        GLES20.glUniform1i(tex1Loc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture2Id)
        GLES20.glUniform1i(tex2Loc, 1)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glUniform1f(progressLoc, progress)
        GLES20.glUniform1f(gridNumLoc, 10.0f)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun loadTexture(context: Context, resourceId: Int): Int {
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

    fun setProgress(progress: Float) {
        this.progress = progress.coerceIn(0.0f, 1.0f)
    }
}
