package com.android.grafika.day09

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.grafika.databinding.ActivityDay09CameraBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Day09Activity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityDay09CameraBinding
    private lateinit var cameraController: LifecycleCameraController
    private lateinit var glSurfaceProcessor: GlSurfaceProcessor
    private var isFrontCamera = false
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    @Volatile
    private var cameraReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewBinding = ActivityDay09CameraBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (allPermissionGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        viewBinding.imageCaptureButton.isEnabled = false
        viewBinding.switchCamera.isEnabled = false
        viewBinding.imageCaptureButton.setOnClickListener {
            takePicture()
        }
        viewBinding.switchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            cameraController.cameraSelector = cameraSelector
        }
    }

    private fun startCamera() {
        glSurfaceProcessor = GlSurfaceProcessor()

        val grayScaleEffect = CameraFilterEffect(
            executor = glSurfaceProcessor.executor,
            processor = glSurfaceProcessor
        )

        cameraController = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            cameraSelector = this@Day09Activity.cameraSelector
            setEffects(setOf<CameraEffect>(grayScaleEffect))
        }
        // viewBinding.viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER
        viewBinding.viewFinder.controller = cameraController
        cameraController.bindToLifecycle(this)
        cameraController.initializationFuture.addListener({
            try {
                cameraReady = true
                viewBinding.imageCaptureButton.isEnabled = true
                viewBinding.switchCamera.isEnabled = true
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePicture() {
        if (!cameraReady) return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault())
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Grafika")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        try {
            cameraController.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputResults: ImageCapture.OutputFileResults) {
                        val message = "Photo capture succeeded: ${outputResults.savedUri}"
                        Toast.makeText(this@Day09Activity, message, Toast.LENGTH_SHORT).show()
                    }

                    override fun onError(p0: ImageCaptureException) {
                    }
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun allPermissionGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "未授予权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (::cameraController.isInitialized) {
            cameraController.unbind()
        }
        if (::glSurfaceProcessor.isInitialized) {
            glSurfaceProcessor.shutdown()
        }
        super.onDestroy()
    }

    companion object {

        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
