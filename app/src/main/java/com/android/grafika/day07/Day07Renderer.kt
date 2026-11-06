package com.android.grafika.day07

import android.content.Context
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Day07Renderer(private val context: Context): GLSurfaceView.Renderer {

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var fboId = 0
    private var imageTextureId = 0
    private var filterProgram = -1
    private var screenProgram = -1

    private var screenPositionLoc = -1
    private var screenTextureCoordLoc = -1
    private var screenTextureLoc = -1
    private var filterPositionLoc = -1
    private var filterTexCoordLoc = -1
    private var filterTextureLoc = -1
    private var filterTexelSizeLoc = -1
    private var filterTypeLoc = -1

    private val imageVertices = floatBuffer(
        floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )
    )

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {

    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
    }

    override fun onDrawFrame(gl: GL10?) {
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