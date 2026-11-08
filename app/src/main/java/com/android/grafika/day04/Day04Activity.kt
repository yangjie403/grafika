package com.android.grafika.day04

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class Day04Activity : AppCompatActivity() {

    private lateinit var renderer: Day04Renderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surfaceView = GLSurfaceView(this)
        surfaceView.setEGLContextClientVersion(2)
        renderer = Day04Renderer(this)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val frameLayout = FrameLayout(this).apply {
            addView(surfaceView, layoutParams)
        }
        frameLayout.layoutParams = layoutParams

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(this).apply {
            text = "Mix"
            setTextColor(Color.WHITE)
        }
        row.addView(labelView)
        val seekBar = SeekBar(this).apply {
            max = 100
            progress = 0
            contentDescription = "混合"
        }
        row.addView(
            seekBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        frameLayout.addView(
            row, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM })

        fun updateMix() {
            val progress = seekBar.progress / 100f
            surfaceView.queueEvent {
                renderer.setProgress(progress)
            }
            surfaceView.requestRender()
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                p0: SeekBar?,
                p1: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    updateMix()
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {

            }

            override fun onStopTrackingTouch(p0: SeekBar?) {

            }
        })
        row.setPadding(60, 0, 60, 60)
        setContentView(frameLayout)
    }
}
