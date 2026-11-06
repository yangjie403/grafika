package com.android.grafika.day07

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class Day07Activity : AppCompatActivity() {

    private var typeTv: TextView? = null
    private var originBt: Button? = null
    private var grayBt: Button? = null
    private var blurBt: Button? = null
    private var edgeBt: Button? = null
    private var revertBt: Button? = null
    private var surfaceView: SurfaceView? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row1.addView(
            TextView(this).apply {
                text = "当前滤镜："
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        typeTv = TextView(this).apply {
            text = "原图"
        }
        row1.addView(
            typeTv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        originBt = Button(this).apply {
            text = "原图"
            setOnClickListener {
                typeTv?.text = "原图"
            }
        }

        grayBt = Button(this).apply {
            text = "灰度图"
            setOnClickListener {
                typeTv?.text = "灰度图"
            }
        }

        blurBt = Button(this).apply {
            text = "模糊图"
            setOnClickListener {
                typeTv?.text = "模糊图"
            }
        }

        edgeBt = Button(this).apply {
            text = "边缘图"
            setOnClickListener {
                typeTv?.text = "边缘图"
            }
        }

        revertBt = Button(this).apply {
            text = "反色"
            setOnClickListener {
                typeTv?.text = "反色"
            }
        }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            weight = 1f
        }
        row2.addView(originBt, btParams)
        row2.addView(grayBt, btParams)
        row2.addView(blurBt, btParams)

        row3.addView(edgeBt, btParams)
        row3.addView(revertBt, btParams)

        controlLayout.addView(row1)
        controlLayout.addView(row2)
        controlLayout.addView(row3)
        rootLayout.addView(surfaceView)
        rootLayout.addView(controlLayout)
        setContentView(rootLayout)
    }
}