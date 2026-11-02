package com.android.grafika.day06

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.android.grafika.day06.Day06Renderer.RenderMode
import android.widget.TextView

class Day06Activity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: Day06Renderer

    private lateinit var renderModeTv: TextView
    private lateinit var rotationTv: TextView
    private lateinit var fpsTv: TextView
    private lateinit var switchBt: Button
    private lateinit var stepBt: Button
    private lateinit var resetBt: Button

    private var renderMode: RenderMode = RenderMode.CONTINUOUSLY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glSurfaceView = GLSurfaceView(this)
        renderer = Day06Renderer(this)
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        rootLayout.addView(
            glSurfaceView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        controlLayout.setPadding(50, 50, 50, 50)
        rootLayout.addView(controlLayout)
        renderModeTv = addLabelView(controlLayout, "渲染模式")
        rotationTv = addLabelView(controlLayout, "旋转角度")
        fpsTv = addLabelView(controlLayout, "FPS")
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
        }
        controlLayout.addView(row)
        val btParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        switchBt = Button(this).apply {
            text = "切换模式"
        }
        stepBt = Button(this).apply {
            text = "旋转一步"
            isEnabled = false
        }
        resetBt = Button(this).apply {
            text = "重置"
        }
        row.addView(switchBt, btParams)
        row.addView(stepBt, btParams)
        row.addView(resetBt, btParams)
        switchBt.setOnClickListener { _ ->
            when (renderMode) {
                RenderMode.CONTINUOUSLY -> {
                    glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    stepBt.isEnabled = true
                    renderMode = RenderMode.WHEN_DIRTY
                }

                RenderMode.WHEN_DIRTY -> {
                    glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    stepBt.isEnabled = false
                    renderMode = RenderMode.CONTINUOUSLY
                }
            }
            renderer.setRenderMode(mode = renderMode)
        }
        stepBt.setOnClickListener {
            glSurfaceView.queueEvent {
                renderer.rotateStep()
            }
            glSurfaceView.requestRender()
        }
        resetBt.setOnClickListener {
            glSurfaceView.queueEvent {
                renderer.reset()
            }
            glSurfaceView.requestRender()
        }
        setContentView(rootLayout)
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    fun addLabelView(controlLayout: LinearLayout, label: String): TextView {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controlLayout.addView(row)
        val labelTv = TextView(this).apply {
            text = label
        }
        row.addView(labelTv)
        val valueTv = TextView(this).apply {
            text = ""
        }
        row.addView(valueTv)
        return valueTv
    }
}