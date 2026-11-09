package com.android.grafika.day10

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Surface
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.android.grafika.databinding.ActivityDay10Binding
import java.util.concurrent.Executors

class Day10Activity : AppCompatActivity() {

    companion object {
        private const val TAG = "Day10Activity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            // Android 10 及以下需要存储权限
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private lateinit var binding: ActivityDay10Binding
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: Day10Renderer
    private lateinit var btnSwitchCamera: Button
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDay10Binding.inflate(layoutInflater)
        setContentView(binding.root)

        glSurfaceView = binding.glSurfaceView
        glSurfaceView.setEGLContextClientVersion(2)
        renderer = Day10Renderer(glSurfaceView)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        btnSwitchCamera = binding.btnSwitchCamera

    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider {request ->
                    val resolution = request.resolution
                    surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                    val surface = Surface(surfaceTexture)
                    request.provideSurface(
                        surface,
                        ContextCompat.getMainExecutor(this@Day10Activity)
                    ) { result ->
                        surface.release()
                    }
                }
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                val cameraType = if (lensFacing == CameraSelector.LENS_FACING_BACK) "后置" else "前置"
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        cameraExecutor.execute {
            try {
                val surfaceTexture = renderer.getSurfaceTexture()
                runOnUiThread {
                    startCamera(surfaceTexture)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun allPermissionGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        renderer.release()
    }
}