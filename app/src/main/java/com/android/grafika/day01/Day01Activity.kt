package com.android.grafika.day01

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.random.Random

class Day01Activity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: Day01Renderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(2)
        renderer = Day01Renderer()
        glSurfaceView.setRenderer(renderer)

        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        val randomColorButton = Button(this).apply {
            text = "随机切换颜色"
            setOnClickListener {
                val red = Random.nextFloat()
                val green = Random.nextFloat()
                val blue = Random.nextFloat()

                glSurfaceView.queueEvent {
                    renderer.setBackground(red, green, blue)
                }
                glSurfaceView.requestRender()
            }
        }

        val rootLayout = FrameLayout(this)
        rootLayout.addView(
            glSurfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val buttonParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 56
        }

        val controlLayout = LinearLayout(this)
        controlLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        rootLayout.addView(randomColorButton, buttonParams)
        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }
}
