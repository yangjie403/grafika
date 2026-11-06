package com.android.grafika.day07

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.android.grafika.R
import com.android.grafika.gles.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FboRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var fboId = 0
    private var colorTextureId = 0
    private var imageTextureId = 0
    private var filterProgram = 0
    private var sceneProgram = 0
    private var screenProgram = 0

    private var scenePositionLoc = -1
    private var sceneColorLoc = -1
    private var screenPositionLoc = -1
    private var screenTextureCoordLoc = -1
    private var screenTextureLoc = -1

    private var filterPositionLoc = -1
    private var filterTexCoordLoc = -1
    private var filterTextureLoc = -1
    private var filterTexelSizeLoc = -1

    private val imageVertices = floatBuffer(
        floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )
    )

    private val scenePositions = floatBuffer(
        floatArrayOf(
            -0.8f, -0.7f,
            0.8f, -0.7f,
            0.0f, 0.8f
        )
    )

    private val sceneColors = floatBuffer(
        floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    )

    // 每个顶点依次存放：位置 x、位置 y、纹理坐标 u、纹理坐标 v。
    private val screenVertices = floatBuffer(
        floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        )
    )

    private val filterFragmentShader = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        varying vec2 vTexCoord;
    
        float luminance(vec2 uv) {
            vec3 color = texture2D(uTexture, uv).rgb;
            return dot(color, vec3(0.299, 0.587, 0.114));
        }
    
        void main() {
            float tl = luminance(vTexCoord + uTexelSize * vec2(-1.0,  1.0));
            float tm = luminance(vTexCoord + uTexelSize * vec2( 0.0,  1.0));
            float tr = luminance(vTexCoord + uTexelSize * vec2( 1.0,  1.0));
    
            float ml = luminance(vTexCoord + uTexelSize * vec2(-1.0,  0.0));
            float mr = luminance(vTexCoord + uTexelSize * vec2( 1.0,  0.0));
    
            float bl = luminance(vTexCoord + uTexelSize * vec2(-1.0, -1.0));
            float bm = luminance(vTexCoord + uTexelSize * vec2( 0.0, -1.0));
            float br = luminance(vTexCoord + uTexelSize * vec2( 1.0, -1.0));
    
            float gx = -tl + tr - 2.0 * ml + 2.0 * mr - bl + br;
            float gy =  tl + 2.0 * tm + tr - bl - 2.0 * bm - br;
    
            float edge = length(vec2(gx, gy));
            gl_FragColor = vec4(vec3(edge), 1.0);
        }
    """.trimIndent()

    private val sceneVertexShader = """
        attribute vec2 aPosition;
        attribute vec3 aColor;
        varying vec3 vColor;
        void main() {
            vColor = aColor;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
    """.trimIndent()

    private val sceneFragmentShader = """
        precision mediump float;
        varying vec3 vColor;
        void main() {
            gl_FragColor = vec4(vColor, 1.0);
        }
    """.trimIndent()

    private val screenVertexShader = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;

        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val screenFragmentShader = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()


    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        fboId = 0
        colorTextureId = 0

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        sceneProgram = GlUtil.createProgram(sceneVertexShader, sceneFragmentShader)
        screenProgram = GlUtil.createProgram(screenVertexShader, screenFragmentShader)
        filterProgram = GlUtil.createProgram(screenVertexShader, filterFragmentShader)

        scenePositionLoc = GLES20.glGetAttribLocation(sceneProgram, "aPosition")
        sceneColorLoc = GLES20.glGetAttribLocation(sceneProgram, "aColor")

        screenPositionLoc = GLES20.glGetAttribLocation(screenProgram, "aPosition")
        screenTextureCoordLoc = GLES20.glGetAttribLocation(screenProgram, "aTexCoord")
        screenTextureLoc = GLES20.glGetUniformLocation(screenProgram, "uTexture")

        filterPositionLoc = GLES20.glGetAttribLocation(filterProgram, "aPosition")
        filterTexCoordLoc = GLES20.glGetAttribLocation(filterProgram, "aTexCoord")
        filterTextureLoc = GLES20.glGetUniformLocation(filterProgram, "uTexture")
        filterTexelSizeLoc = GLES20.glGetUniformLocation(filterProgram, "uTexelSize")
        imageTextureId = GlUtil.createTextureFromRes(context, R.drawable.pyy)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        surfaceWidth = width
        surfaceHeight = height
        if (width > 0 && height > 0) {
            createFrameBuffer(width, height)
        }
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (fboId == 0 || colorTextureId == 0) {
            return
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glClearColor(0.08f, 0.08f, 0.08f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // drawScene()
        drawImageToFbo()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawColorTexture()
    }

    private fun createFrameBuffer(width: Int, height: Int) {
        deleteFramebuffer()

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        colorTextureId = textureIds[0]
        check(colorTextureId != 0) { "创建颜色纹理失败" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTextureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val framebufferIds = IntArray(1)
        GLES20.glGenFramebuffers(1, framebufferIds, 0)
        fboId = framebufferIds[0]
        check(fboId != 0) { "创建帧缓冲区失败" }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            colorTextureId,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "FBO不完整，状态码：0x${Integer.toHexString(status)}"
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun drawScene() {
        GLES20.glUseProgram(sceneProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        scenePositions.position(0)
        GLES20.glVertexAttribPointer(
            scenePositionLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            scenePositions
        )
        GLES20.glEnableVertexAttribArray(scenePositionLoc)

        sceneColors.position(0)
        GLES20.glVertexAttribPointer(
            sceneColorLoc,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            sceneColors
        )
        GLES20.glEnableVertexAttribArray(sceneColorLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 3)
        GLES20.glDisableVertexAttribArray(scenePositionLoc)
        GLES20.glDisableVertexAttribArray(sceneColorLoc)
    }

    private fun drawImageToFbo() {
        GLES20.glUseProgram(filterProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        imageVertices.position(0)
        GLES20.glVertexAttribPointer(
            filterPositionLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            4 * 4,
            imageVertices
        )
        GLES20.glEnableVertexAttribArray(filterPositionLoc)
        imageVertices.position(2)
        GLES20.glVertexAttribPointer(
            filterTexCoordLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            4 * 4,
            imageVertices
        )
        GLES20.glEnableVertexAttribArray(filterTexCoordLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        GLES20.glUniform1i(filterTextureLoc, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glUniform2f(filterTexelSizeLoc, 1f / surfaceWidth, 1f / surfaceHeight)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisableVertexAttribArray(filterPositionLoc)
        GLES20.glDisableVertexAttribArray(filterTexCoordLoc)
    }

    private fun drawColorTexture() {
        GLES20.glUseProgram(screenProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        screenVertices.position(0)
        GLES20.glVertexAttribPointer(
            screenPositionLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            4 * 4,
            screenVertices
        )
        GLES20.glEnableVertexAttribArray(screenPositionLoc)

        screenVertices.position(2)
        GLES20.glVertexAttribPointer(
            screenTextureCoordLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            4 * 4,
            screenVertices
        )
        GLES20.glEnableVertexAttribArray(screenTextureCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTextureId)
        GLES20.glUniform1i(screenTextureLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisableVertexAttribArray(screenPositionLoc)
        GLES20.glDisableVertexAttribArray(screenTextureCoordLoc)
    }

    private fun deleteFramebuffer() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (colorTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(colorTextureId), 0)
            colorTextureId = 0
        }
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }
}