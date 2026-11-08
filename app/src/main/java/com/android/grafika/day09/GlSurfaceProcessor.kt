package com.android.grafika.day09

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.android.grafika.gles.GlUtil
import java.util.concurrent.Executor

class GlSurfaceProcessor : SurfaceProcessor {
    private val glThread = HandlerThread("CameraFilterGL").apply {
        start()
    }

    private val glHandler = Handler(glThread.looper)

    val executor: Executor = Executor { command ->
        glHandler.post(command)
    }

    private var shuttingDown = false
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null
    private var eglPbufferSurface: EGLSurface? = null

    private var program = 0
    private var positionHandle = 0
    private var textureCoordinateHandle = 0
    private var textureMatrixHandle = 0
    private var textureHandle = 0

    private var inputResources = mutableSetOf<InputResources>()
    private var outputResources = mutableSetOf<OutputResources>()
    private var currentInput: InputResources? = null
    private var currentOutput: OutputResources? = null
    private var framePending = false

    private val textureBuffer = GlUtil.createFloatBuffer(
        floatArrayOf(
            0f, 0f, // 左下
            1f, 0f, // 右下
            0f, 1f, // 左上
            1f, 1f  // 右上
        )
    )

    private val vertexBuffer = GlUtil.createFloatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
    )

    private val inputMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)

    override fun onInputSurface(request: SurfaceRequest) {
        if (shuttingDown) {
            request.willNotProvideSurface()
            return
        }

        ensureGlContext()

        val textureId = createExternalTexture()
        val surfaceTexture = SurfaceTexture(textureId)
        val resolution = request.resolution

        surfaceTexture.setDefaultBufferSize(
            resolution.width,
            resolution.height
        )
        val surface = Surface(surfaceTexture)
        val resources = InputResources(
            textureId = textureId,
            surfaceTexture = surfaceTexture,
            surface = surface
        )

        inputResources += resources
        currentInput = resources

        surfaceTexture.setOnFrameAvailableListener(
            {
                framePending = true
                drawFrame()
            },
            glHandler
        )

        request.provideSurface(surface, executor) {
            releaseInput(resources)
        }

        request.addRequestCancellationListener(executor) {
            releaseInput(resources)
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (shuttingDown) {
            output.close()
            return
        }

        ensureGlContext()

        var resources: OutputResources? = null

        val surface = output.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                resources?.let {
                    releaseOutput(it)
                }
            }
        }

        val display = requireNotNull(eglDisplay)
        val config = requireNotNull(eglConfig)
        val eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            "创建 EGL Window Surface 失败：${eglError()}"
        }

        resources = OutputResources(
            surfaceOutput = output,
            eglSurface = eglSurface
        )

        outputResources += resources
        currentOutput = resources

        if (framePending) {
            drawFrame()
        }
    }

    private fun drawFrame() {
        if (!framePending) {
            return
        }

        val input = currentInput ?: return
        val output = currentOutput ?: return

        if (!makeCurrent(output.eglSurface)) {
            return
        }

        input.surfaceTexture.updateTexImage()
        input.surfaceTexture.getTransformMatrix(inputMatrix)

        output.surfaceOutput.updateTransformMatrix(
            outputMatrix,
            inputMatrix
        )

        GLES20.glViewport(
            0,
            0,
            output.surfaceOutput.size.width,
            output.surfaceOutput.size.height
        )

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )

        textureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES20.glVertexAttribPointer(
            textureCoordinateHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer
        )

        GLES20.glUniformMatrix4fv(
            textureMatrixHandle,
            1,
            false,
            outputMatrix,
            0
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle)

        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            0
        )

        val display = requireNotNull(eglDisplay)
        if (!EGL14.eglSwapBuffers(display, output.eglSurface)) {
            Log.e("GlSurfaceProcessor", "eglSwapBuffers 失败：${eglError()}")
        }
        framePending = false
    }

    private fun ensureGlContext() {
        val existingDisplay = eglDisplay
        val existingContext = eglContext
        val existingPbuffer = eglPbufferSurface

        if (existingContext != null &&
            existingPbuffer != null &&
            existingDisplay != null
        ) {
            check(
                EGL14.eglMakeCurrent(
                    existingDisplay,
                    existingPbuffer,
                    existingPbuffer,
                    existingContext
                )
            ) {
                "重新绑定 EGL Pbuffer 失败：${eglError()}"
            }
            return
        }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) {
            "获取 EGL Display 失败：${eglError()}"
        }
        eglDisplay = display
        val version = IntArray(2)
        check(
            EGL14.eglInitialize(
                eglDisplay,
                version,
                0,
                version,
                1
            )
        ) {
            "初始化 EGL 失败：${eglError()}"
        }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE,
            EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)

        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                1,
                configCount,
                0
            )
        ) {
            "选择 EGL Config 失败：${eglError()}"
        }

        eglConfig = configs[0]
        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION,
            2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0
        )

        check(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
            "创建 EGL Context 失败：${eglError()}"
        }

        val pbufferAttributes = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            eglConfig,
            pbufferAttributes,
            0
        )

        check(
            pbuffer != null &&
                    pbuffer != EGL14.EGL_NO_SURFACE
        ) {
            "创建 EGL Pbuffer 失败：${eglError()}"
        }

        eglPbufferSurface = pbuffer

        check(
            EGL14.eglMakeCurrent(
                display,
                pbuffer,
                pbuffer,
                eglContext
            )
        ) {
            "绑定 EGL Context 失败：${eglError()}"
        }
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateHandle = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
        textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        GlUtil.checkLocation(positionHandle, "aPosition")
        GlUtil.checkLocation(
            textureCoordinateHandle,
            "aTextureCoordinate"
        )
        GlUtil.checkLocation(
            textureMatrixHandle,
            "uTextureMatrix"
        )
        GlUtil.checkLocation(textureHandle, "uTexture")
    }

    private fun makeCurrent(surface: EGLSurface): Boolean {
        val display = eglDisplay ?: return false
        val context = eglContext ?: return false

        val result = EGL14.eglMakeCurrent(
            display,
            surface,
            surface,
            context
        )

        if (!result) {
            Log.e(
                "GlSurfaceProcessor",
                "eglMakeCurrent 失败：${eglError()}"
            )
        }

        return result
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)

        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            0
        )

        return textures[0]
    }

    private fun releaseInput(resources: InputResources) {
        if (resources.released) {
            return
        }

        resources.released = true
        inputResources.remove(resources)

        if (currentInput === resources) {
            currentInput = inputResources.lastOrNull()
        }

        resources.surfaceTexture.setOnFrameAvailableListener(null)
        resources.surface.release()
        resources.surfaceTexture.release()

        GLES20.glDeleteTextures(
            1,
            intArrayOf(resources.textureId),
            0
        )
    }

    private fun releaseOutput(resources: OutputResources) {
        if (resources.released) {
            return
        }

        resources.released = true
        outputResources.remove(resources)

        if (currentOutput === resources) {
            currentOutput = outputResources.lastOrNull()
        }

        val display = eglDisplay
        val pbuffer = eglPbufferSurface

        if (display != null && pbuffer != null) {
            EGL14.eglMakeCurrent(
                display,
                pbuffer,
                pbuffer,
                eglContext
            )

            EGL14.eglDestroySurface(
                display,
                resources.eglSurface
            )
        }

        resources.surfaceOutput.close()
    }

    fun shutdown() {
        glHandler.post {
            if (shuttingDown) {
                return@post
            }

            shuttingDown = true

            inputResources.toList().forEach {
                releaseInput(it)
            }

            outputResources.toList().forEach {
                releaseOutput(it)
            }

            val display = eglDisplay
            val context = eglContext
            val pbuffer = eglPbufferSurface

            if (
                display != null &&
                context != null &&
                pbuffer != null
            ) {
                EGL14.eglMakeCurrent(
                    display,
                    pbuffer,
                    pbuffer,
                    context
                )
            }

            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }

            if (
                display != null &&
                context != null
            ) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }

            if (display != null && pbuffer != null) {
                EGL14.eglDestroySurface(display, pbuffer)
            }

            if (display != null && context != null) {
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }

            eglPbufferSurface = null
            eglContext = null
            eglDisplay = null
            eglConfig = null

            glThread.quitSafely()
        }
    }

    private class InputResources(
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface
    ) {
        var released = false
    }

    private class OutputResources(
        val surfaceOutput: SurfaceOutput,
        val eglSurface: EGLSurface
    ) {
        var released = false
    }

    private fun eglError(): String {
        return "0x${Integer.toHexString(EGL14.eglGetError())}"
    }

    companion object {

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoordinate;

            uniform mat4 uTextureMatrix;

            varying vec2 vTextureCoordinate;

            void main() {
                gl_Position = aPosition;
                vTextureCoordinate =
                    (uTextureMatrix *
                    vec4(aTextureCoordinate, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require

            precision mediump float;

            uniform samplerExternalOES uTexture;

            varying vec2 vTextureCoordinate;

            void main() {
                vec4 color = texture2D(
                    uTexture,
                    vTextureCoordinate
                );

                float gray = dot(
                    color.rgb,
                    vec3(0.299, 0.587, 0.114)
                );

                gl_FragColor = vec4(
                    vec3(gray),
                    color.a
                );
            }
        """.trimIndent()
    }
}