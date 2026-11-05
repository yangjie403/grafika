package com.android.grafika.day06

import android.app.Activity
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.SurfaceHolder
import android.view.SurfaceView

class ManualEglActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private var renderThread: ManulEglRenderThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = ManulEglRenderThread(holder.surface).also {
            it.start()
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        renderThread?.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        renderThread?.requestExitAndWait()
        renderThread = null
    }

    override fun onDestroy() {
        renderThread?.requestExitAndWait()
        renderThread = null
        super.onDestroy()
    }
}