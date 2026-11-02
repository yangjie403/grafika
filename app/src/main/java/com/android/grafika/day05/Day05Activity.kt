package com.android.grafika.day05

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.hypot

class Day05Activity : AppCompatActivity() {
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: Day05Renderer

    private var rotation = 0f
    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var firstPointerId = INVALID_POINTER_ID
    private var secondPointerId = INVALID_POINTER_ID

    private var lastX = 0f
    private var lastY = 0f

    private var previousCentroidX = 0f
    private var previousCentroidY = 0f
    private var previousDistance = 0f
    private var previousAngle = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glSurfaceView = GLSurfaceView(this)
        renderer = Day05Renderer(this)
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        glSurfaceView.setOnTouchListener { _, event ->
            handleTouchEvent(event)
        }
        setContentView(glSurfaceView)
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                firstPointerId = event.getPointerId(0)
                secondPointerId = INVALID_POINTER_ID
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (secondPointerId == INVALID_POINTER_ID) {
                    secondPointerId = event.getPointerId(event.actionIndex)
                    beginTwoFingerGesture(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (secondPointerId != INVALID_POINTER_ID) {
                    handleTwoFingerMove(event)
                } else {
                    handleOneFingerMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedPointerId = event.getPointerId(event.actionIndex)
                if (liftedPointerId == firstPointerId ||
                    liftedPointerId == secondPointerId
                ) {
                    val replacementIndex = (0 until event.pointerCount)
                        .firstOrNull { it != event.actionIndex }
                    if (replacementIndex == null) {
                        resetTouchState()
                    } else {
                        firstPointerId = event.getPointerId(replacementIndex)
                        secondPointerId = INVALID_POINTER_ID
                        lastX = event.getX(replacementIndex)
                        lastY = event.getY(replacementIndex)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetTouchState()
            }
        }
        return true
    }

    private fun handleOneFingerMove(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(firstPointerId)
        if (pointerIndex < 0) return
        val currentX = event.getX(pointerIndex)
        val currentY = event.getY(pointerIndex)
        val deltaX = currentX - lastX
        val deltaY = currentY - lastY
        val pixelsToWorld = pixelsToWorld()

        translateX += deltaX * pixelsToWorld
        translateY -= deltaY * pixelsToWorld
        lastX = currentX
        lastY = currentY
        publishTransform()
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        val firstIndex = event.findPointerIndex(firstPointerId)
        val secondIndex = event.findPointerIndex(secondPointerId)

        if (firstIndex < 0 || secondIndex < 0) return

        val firstX = event.getX(firstIndex)
        val firstY = event.getY(firstIndex)
        val secondX = event.getX(secondIndex)
        val secondY = event.getY(secondIndex)

        val centroidX = (firstX + secondX) / 2f
        val centroidY = (firstY + secondY) / 2f

        val deltaCentroidX = centroidX - previousCentroidX
        val deltaCentroidY = centroidY - previousCentroidY
        val pixelsToWorld = pixelsToWorld()

        translateX += deltaCentroidX * pixelsToWorld
        translateY -= deltaCentroidY * pixelsToWorld

        val distance = hypot(
            (secondX - firstX).toDouble(),
            (secondY - firstY).toDouble()
        ).toFloat()

        if (previousDistance > 0f && distance > 0f) {
            scale = (scale * distance / previousDistance).coerceIn(MIN_SCALE, MAX_SCALE)
        }

        val angle = Math.toDegrees(
            atan2(
                (secondY - firstY).toDouble(),
                (secondX - firstX).toDouble()
            )
        ).toFloat()

        val deltaAngle = shortestAngleDelta(angle - previousAngle)
        rotation -= deltaAngle

        previousCentroidX = centroidX
        previousCentroidY = centroidY
        previousDistance = distance
        previousAngle = angle
        publishTransform()
    }

    private fun beginTwoFingerGesture(event: MotionEvent) {
        val firstIndex = event.findPointerIndex(firstPointerId)
        val secondIndex = event.findPointerIndex(secondPointerId)

        if (firstIndex < 0 || secondIndex < 0) return
        val firstX = event.getX(firstIndex)
        val firstY = event.getY(firstIndex)
        val secondX = event.getX(secondIndex)
        val secondY = event.getY(secondIndex)

        previousCentroidX = (firstX + secondX) / 2f
        previousCentroidY = (firstY + secondY) / 2f
        previousDistance = hypot(
            (secondX - firstX).toDouble(),
            (secondY - firstY).toDouble()
        ).toFloat()
        previousAngle = Math.toDegrees(
            atan2(
                (secondY - firstY).toDouble(),
                (secondX - firstX).toDouble()
            )
        ).toFloat()
    }

    private fun publishTransform() {
        val currentRotation = rotation
        val currentScale = scale
        val currentTranslateX = translateX
        val currentTranslateY = translateY

        glSurfaceView.queueEvent {
            renderer.setRotation(currentRotation)
            renderer.setScale(currentScale)
            renderer.setTranslate(currentTranslateX, currentTranslateY)
        }
        glSurfaceView.requestRender()
    }

    private fun pixelsToWorld(): Float {
        val width = glSurfaceView.width
        val height = glSurfaceView.height
        if (width <= 0 || height <= 0) {
            return 0f
        }
        return 2f / minOf(width, height).toFloat()
    }

    private fun shortestAngleDelta(angle: Float): Float {
        return ((angle + 180f) % 360f + 360f) % 360f - 180f
    }

    private fun resetTouchState() {
        firstPointerId = INVALID_POINTER_ID
        secondPointerId = INVALID_POINTER_ID
    }

    private companion object {
        const val INVALID_POINTER_ID = -1
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 5f
    }
}