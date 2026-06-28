package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Take a photo silently in the background using Camera2 API and (optionally)
 * extract text/content from the image using OCR/Vision.
 *
 * Requires CAMERA permission. The photo is saved to the app's cache directory
 * and the file path is returned for further processing (e.g., with vision_analyze).
 */
internal fun buildCameraTool(context: Context): Tool = Tool(
    name = "take_photo",
    description = """
        Take a photo silently in the background using the device camera.
        Choose the front or back camera. The photo is saved to the app cache
        and the file path is returned. Requires CAMERA permission.
        Use this together with vision analysis to identify objects, text, or
        scenes in the captured image.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("camera", buildJsonObject {
                    put("type", "string")
                    put("description", "Which camera to use: 'front' (selfie) or 'back' (main). Default 'back'.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasCameraPermission(context)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "NO_PERMISSION")
                put("message", "Camera permission is not granted. Please enable it in the app permission settings.")
            }.toString()))
        }

        val params = args.jsonObject
        val useFront = params["camera"]?.jsonPrimitive?.contentOrNull == "front"

        val result = takePhotoSilently(context, useFront)
        if (result.isSuccess) {
            val file = result.getOrNull()!!
            val payload = buildJsonObject {
                put("success", true)
                put("file_path", file.absolutePath)
                put("file_name", file.name)
                put("file_size_bytes", file.length())
                put("camera", if (useFront) "front" else "back")
                put("note", "Use vision analysis to describe the image content.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } else {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "CAPTURE_FAILED")
                put("message", result.exceptionOrNull()?.message ?: "Failed to take photo.")
            }.toString()))
        }
    }
)

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun takePhotoSilently(context: Context, useFront: Boolean): Result<File> {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Find the right camera
    val cameraId: String
    try {
        cameraId = cameraManager.cameraIdList.first { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: NoSuchElementException) {
        return Result.failure(Exception("No ${if (useFront) "front" else "back"} camera found."))
    }

    val handlerThread = HandlerThread("CameraCapture")
    handlerThread.start()
    val handler = Handler(handlerThread.looper)

    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(context.cacheDir, "capture_$timestamp.jpg")
    val cameraDevice = AtomicReference<CameraDevice?>(null)
    val openLatch = CountDownLatch(1)
    val captureLatch = CountDownLatch(1)
    val errorRef = AtomicReference<String?>(null)

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)
        val size = jpegSizes?.maxByOrNull { it.width * it.height }

        if (size == null) {
            return Result.failure(Exception("Camera does not support JPEG capture."))
        }

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer: ByteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    FileOutputStream(photoFile).use { fos -> fos.write(bytes) }
                } finally {
                    image.close()
                }
                captureLatch.countDown()
            }
        }, handler)

        @Suppress("MissingPermission")
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice.set(camera)
                openLatch.countDown()
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                errorRef.set("Camera error: $error")
                camera.close()
                openLatch.countDown()
            }
        }, handler)

        if (!openLatch.await(5, TimeUnit.SECONDS) || cameraDevice.get() == null) {
            return Result.failure(Exception(errorRef.get() ?: "Camera open timed out."))
        }

        val camera = cameraDevice.get()!!

        // Create capture session
        val sessionLatch = CountDownLatch(1)
        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                try {
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                        set(CaptureRequest.JPEG_ORIENTATION, 0)
                    }
                    session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            // Photo captured — ImageReader callback will handle saving
                        }
                    }, handler)
                } catch (e: CameraAccessException) {
                    errorRef.set("Capture failed: ${e.message}")
                    captureLatch.countDown()
                }
                sessionLatch.countDown()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                errorRef.set("Camera session configuration failed.")
                sessionLatch.countDown()
                captureLatch.countDown()
            }
        }, handler)

        sessionLatch.await(5, TimeUnit.SECONDS)
        captureLatch.await(10, TimeUnit.SECONDS)

        imageReader.close()
        camera.close()

        if (photoFile.exists() && photoFile.length() > 0) {
            return Result.success(photoFile)
        }
        return Result.failure(Exception(errorRef.get() ?: "Photo capture failed — no image saved."))
    } catch (e: Exception) {
        return Result.failure(e)
    } finally {
        cameraDevice.get()?.close()
        handlerThread.quitSafely()
    }
}
