package com.android.grafika.day01

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Day01Renderer : GLSurfaceView.Renderer {

    private var red = 0.5f
    private var green = 0.3f
    private var blue = 0.2f

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        GLES20.glClearColor(red, green, blue, 1.0f)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(p0: GL10?) {
        GLES20.glClearColor(red, green, blue, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun setBackground(r: Float, g: Float, b: Float) {
        red = r
        green = g
        blue = b
    }
}