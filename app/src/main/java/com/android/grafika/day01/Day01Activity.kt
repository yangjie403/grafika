package com.android.grafika.day01

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
        )

        val controlLayout = LinearLayout(this)
        controlLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        controlLayout.orientation = LinearLayout.VERTICAL
        controlLayout.gravity = Gravity.CENTER_HORIZONTAL
        controlLayout.setPadding(dp(16), dp(8), dp(16), dp(16))

        val redSeekBar = addColorSeekBar(controlLayout, "R", 128)
        val greenSeekBar = addColorSeekBar(controlLayout, "G", 77)
        val blueSeekBar = addColorSeekBar(controlLayout, "B", 51)
        fun updateBackground() {
            val red = redSeekBar.progress / 255f
            val green = greenSeekBar.progress / 255f
            val blue = blueSeekBar.progress / 255f
            glSurfaceView.queueEvent {
                renderer.setBackground(red, green, blue)
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
                    updateBackground()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        listOf(redSeekBar, greenSeekBar, blueSeekBar).forEach {
            it.setOnSeekBarChangeListener(listener)
        }
        controlLayout.addView(randomColorButton, buttonParams)
        randomColorButton.setOnClickListener {
            redSeekBar.progress = Random.nextInt(255)
            greenSeekBar.progress = Random.nextInt(255)
            blueSeekBar.progress = Random.nextInt(255)
            updateBackground()
        }
        rootLayout.addView(
            controlLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            })
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
            setTextColor(Color.BLACK)
        }
        row.addView(
            labelView,
            LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val seekBar = SeekBar(this).apply {
            max = 255
            progress = initialValue
            contentDescription = "$label 颜色分量"
        }
        row.addView(seekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        controlLayout.addView(row)
        return seekBar
    }
}
