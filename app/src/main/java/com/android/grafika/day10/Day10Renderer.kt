package com.android.grafika.day10

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.android.grafika.gles.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Day10Renderer(private val glSurfaceView: GLSurfaceView) : GLSurfaceView.Renderer {

    private var program: Int = 0
    private var oesTextureId: Int = 0
    private lateinit var surfaceTexture: SurfaceTexture

    private var uTextureLoc: Int = 0
    private var uTransformMatrixLoc: Int = 0
    private val transformMatrix = FloatArray(16)
    private var uPositionMatrixLoc: Int = 0
    private val positionMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    @Volatile
    private var surfaceTextureReady = false
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0

    @Volatile
    private var inputWidth = 1
    @Volatile
    private var inputHeight = 1
    private var outputWidth = 1
    private var outputHeight = 1
    private var sourceRotation: Int = 0

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTransformMatrix;
        uniform mat4 uPositionMatrix;
        varying vec2 vTexCoord;
        
        void main() {
            gl_Position = uPositionMatrix * aPosition;
            vTexCoord =(uTransformMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    // OES 纹理的片段着色器
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private val quadVertices = floatArrayOf(
        -1f, -1f,  // 左下
        1f, -1f,  // 右下
        -1f, 1f,  // 左上
        1f, 1f   // 右上
    )

    // 纹理坐标（4D，用于变换矩阵）
    private val texCoords = floatArrayOf(
        0f, 0f, 0f, 1f,  // 左下
        1f, 0f, 0f, 1f,  // 右下
        0f, 1f, 0f, 1f,  // 左上
        1f, 1f, 0f, 1f   // 右上
    )

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = GlUtil.createProgram(vertexShaderCode, fragmentShaderCode)
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uTransformMatrixLoc = GLES20.glGetUniformLocation(program, "uTransformMatrix")
        uPositionMatrixLoc = GLES20.glGetUniformLocation(program, "uPositionMatrix")
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        vertexBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer.position(0)

        oesTextureId = createOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture.setOnFrameAvailableListener {
            glSurfaceView.requestRender()
        }
        surfaceTextureReady = true
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        outputHeight = height
        outputWidth = width
        Log.d("Day10Renderer", "onSurfaceChanged: $width, $height")
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!surfaceTextureReady) {
            return
        }
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transformMatrix)
        } catch (_: Exception) {
            return
        }
        updateViewport(
            viewWidth = outputWidth,
            viewHeight = outputHeight,
            sourceWidth = inputWidth,
            sourceHeight = inputHeight,
            rotationDegrees = sourceRotation,
            fill = true
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glUniformMatrix4fv(uTransformMatrixLoc, 1, false, transformMatrix, 0)
        GLES20.glUniformMatrix4fv(uPositionMatrixLoc, 1, false, positionMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aPositionLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aTexCoordLoc,
            4,
            GLES20.GL_FLOAT,
            false,
            0,
            texCoordBuffer
        )
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)

        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return textureId
    }

    fun getSurfaceTexture(): SurfaceTexture {
        while (!surfaceTextureReady) {
            Thread.sleep(100)
        }
        return surfaceTexture
    }

    fun release() {
        if (::surfaceTexture.isInitialized) {
            surfaceTexture.release()
        }
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        }
    }

    fun setInputSize(width: Int, height: Int) {
        glSurfaceView.queueEvent {
            inputWidth = width
            inputHeight = height
            glSurfaceView.requestRender()
        }
    }

    fun setInputTransformation(
        rotationDegrees: Int,
        hasCameraTransform: Boolean,
        targetRotation: Int
    ) {
        glSurfaceView.queueEvent {
            // 完整旋转用于计算最终宽高比，不代表还需要把它全部叠加到图像上。
            sourceRotation = rotationDegrees
            val displayDegrees = when (targetRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            // 直连相机的纹理矩阵已包含传感器方向，只补目标显示方向。
            // 无相机变换时才应用完整的顺时针旋转；GL 顶点坐标的正角为逆时针。
            val vertexRotation = if (hasCameraTransform) displayDegrees else -rotationDegrees
            Matrix.setRotateM(positionMatrix, 0, vertexRotation.toFloat(), 0f, 0f, 1f)
        }
        glSurfaceView.requestRender()
    }

    private fun updateViewport(
        viewWidth: Int,
        viewHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        fill: Boolean
    ) {
        var sourceW = sourceWidth
        var sourceH = sourceHeight

        if (rotationDegrees == 90 || rotationDegrees == 270) {
            val temp = sourceW
            sourceW = sourceHeight
            sourceH = temp
        }

        val sourceAspect = sourceW.toFloat() / sourceH
        val viewAspect = viewWidth.toFloat() / viewHeight

        val viewportWidth: Int
        val viewportHeight: Int

        if (fill) {
            if (sourceAspect > viewAspect) {
                viewportWidth = (viewHeight * sourceAspect).toInt()
                viewportHeight = viewHeight
            } else {
                viewportWidth = viewWidth
                viewportHeight = (viewWidth / sourceAspect).toInt()
            }
        } else {
            if (sourceAspect > viewAspect) {
                viewportWidth = viewWidth
                viewportHeight = (viewWidth / sourceAspect).toInt()
            } else {
                viewportWidth = (viewHeight * sourceAspect).toInt()
                viewportHeight = viewHeight
            }
        }

        val x = (viewWidth - viewportWidth) / 2
        val y = (viewHeight - viewportHeight) / 2

        GLES20.glViewport(
            x,
            y,
            viewportWidth,
            viewportHeight
        )
        Log.d("Day10Renderer", "updateViewport: $viewportWidth, $viewportHeight")
    }
}
