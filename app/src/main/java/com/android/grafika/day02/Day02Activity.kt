package com.android.grafika.day02

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class Day02Activity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: Day02Renderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(2)
        renderer = Day02Renderer()
        glSurfaceView.setRenderer(renderer)

        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(
            glSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val controlLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val xSeekBar = addColorSeekBar(controlLayout, "X", 100)
        val ySeekBar = addColorSeekBar(controlLayout, "Y", 100)

        fun updateScale() {
            val x = xSeekBar.progress / 100f
            val y = ySeekBar.progress / 100f
            glSurfaceView.queueEvent {
                renderer.setScale(x, y)
            }
            glSurfaceView.requestRender()
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    updateScale()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        listOf(xSeekBar, ySeekBar).forEach {
            it.setOnSeekBarChangeListener(listener)
        }

        rootLayout.addView(controlLayout)
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

    fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    fun addColorSeekBar(controlLayout: LinearLayout, label: String, initialValue: Int): SeekBar {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
        }
        row.addView(
            labelView,
            LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val seekBar = SeekBar(this).apply {
            max = 200
            progress = initialValue
            contentDescription = "$label 颜色分量"
        }
        row.addView(seekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        controlLayout.addView(row)
        return seekBar
    }
}
