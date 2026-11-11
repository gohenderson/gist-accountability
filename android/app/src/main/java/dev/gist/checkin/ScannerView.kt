package dev.gist.checkin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.abs

/**
 * Camera preview that reports the first QR code / barcode it sees.
 * Uses the platform Camera2 API for frames and ZXing (core) to decode them, entirely on-device.
 * Call [start] in onResume and [stop] in onPause. Callbacks arrive on the main thread.
 * Only works in portrait (the activities using it are locked to portrait).
 */
class ScannerView(context: Context) : FrameLayout(context) {
    private val texture = AspectTextureView(context)
    private val main = Handler(Looper.getMainLooper())
    private val decoder = MultiFormatReader().apply { setHints(HINTS) }

    private var thread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var onCode: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    @Volatile private var running = false
    @Volatile private var delivered = false

    init {
        addView(texture, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    fun start(onCode: (String) -> Unit, onError: (String) -> Unit) {
        if (running) return
        this.onCode = onCode
        this.onError = onError
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission isn't granted.")
            return
        }
        running = true
        delivered = false
        val t = HandlerThread("gist-camera").also { it.start() }
        thread = t
        camHandler = Handler(t.looper)
        if (texture.isAvailable) openCamera() // otherwise the listener opens it
    }

    /** After a code was delivered, look for another one. */
    fun rearm() {
        delivered = false
    }

    fun stop() {
        running = false
        val s = session; val c = camera; val r = reader; val t = thread; val h = camHandler
        session = null; camera = null; reader = null; thread = null; camHandler = null
        if (h != null) {
            h.post {
                try { s?.close() } catch (e: Exception) {}
                try { c?.close() } catch (e: Exception) {}
                try { r?.close() } catch (e: Exception) {}
            }
            t?.quitSafely()
        }
    }

    // ------------------------------------------------------------ camera

    @SuppressLint("MissingPermission") // checked in start()
    private fun openCamera() {
        val h = camHandler
        if (!running || h == null) return
        try {
            val cm = context.getSystemService(CameraManager::class.java)
            val ids = cm.cameraIdList
            val id = ids.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.firstOrNull() ?: return fail("No camera found.")
            val map = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("Camera unavailable.")

            val frameSize = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), 1280 * 720, null)
            val previewSize = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 1920 * 1080, frameSize)

            // Sensor is landscape; we're portrait, so the preview is tall.
            texture.setAspect(previewSize.height, previewSize.width)
            texture.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

            val r = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ onFrame(it) }, h)
            reader = r

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    if (!running) { cd.close(); return }
                    camera = cd
                    startSession(cd, r, h)
                }
                override fun onDisconnected(cd: CameraDevice) {
                    cd.close()
                    camera = null
                }
                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close()
                    camera = null
                    fail("Camera error ($error).")
                }
            }, h)
        } catch (e: Exception) {
            fail("Camera unavailable: ${e.message}")
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List, ...) is fine on all supported versions
    private fun startSession(cd: CameraDevice, r: ImageReader, h: Handler) {
        val st = texture.surfaceTexture ?: return fail("Preview unavailable.")
        val preview = Surface(st)
        try {
            cd.createCaptureSession(listOf(preview, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) { s.close(); return }
                    session = s
                    try {
                        val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(preview)
                        req.addTarget(r.surface)
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        s.setRepeatingRequest(req.build(), null, h)
                    } catch (e: Exception) {
                        fail("Couldn't start the camera.")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) = fail("Couldn't start the camera.")
            }, h)
        } catch (e: Exception) {
            fail("Couldn't start the camera.")
        }
    }

    // ------------------------------------------------------------ decoding

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (delivered || !running) return
            val w = img.width
            val h = img.height
            val plane = img.planes[0] // Y (luminance) is all ZXing needs
            val buf = plane.buffer
            val stride = plane.rowStride
            val y = ByteArray(w * h)
            if (stride == w) {
                buf.get(y, 0, w * h)
            } else {
                for (row in 0 until h) {
                    buf.position(row * stride)
                    buf.get(y, row * w, w)
                }
            }
            // Try as-is, then rotated 90° (1D barcodes held "upright" in portrait).
            val text = decode(y, w, h) ?: decode(rotate90(y, w, h), h, w) ?: return
            delivered = true
            main.post { if (running) onCode?.invoke(text) }
        } catch (e: Exception) {
            // bad frame; try the next one
        } finally {
            img.close()
        }
    }

    private fun decode(data: ByteArray, w: Int, h: Int): String? =
        try {
            val src = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            decoder.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
        } catch (e: Exception) {
            null
        } finally {
            decoder.reset()
        }

    private fun rotate90(d: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(d.size)
        for (y in 0 until h) {
            for (x in 0 until w) out[x * h + (h - 1 - y)] = d[y * w + x]
        }
        return out
    }

    private fun fail(msg: String) {
        main.post { onError?.invoke(msg) }
    }

    private fun pickSize(sizes: Array<Size>?, maxArea: Int, sameAspectAs: Size?): Size {
        val all = sizes?.toList().orEmpty()
        if (all.isEmpty()) return Size(640, 480)
        val pool = if (sameAspectAs == null) all else {
            val target = sameAspectAs.width.toFloat() / sameAspectAs.height
            all.filter { abs(it.width.toFloat() / it.height - target) < 0.05f }.ifEmpty { all }
        }
        return pool.filter { it.width * it.height <= maxArea }.maxByOrNull { it.width * it.height }
            ?: pool.minByOrNull { it.width * it.height }
            ?: all.first()
    }

    /** TextureView that keeps the camera's aspect ratio and uses at most ~55% of the screen height. */
    private class AspectTextureView(context: Context) : TextureView(context) {
        private var ratioW = 3
        private var ratioH = 4

        fun setAspect(w: Int, h: Int) {
            if (w <= 0 || h <= 0) return
            post {
                ratioW = w
                ratioH = h
                requestLayout()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var w = MeasureSpec.getSize(widthMeasureSpec)
            var h = w * ratioH / ratioW
            val maxH = (resources.displayMetrics.heightPixels * 0.55).toInt()
            if (h > maxH) {
                h = maxH
                w = h * ratioW / ratioH
            }
            setMeasuredDimension(w, h)
        }
    }

    companion object {
        private val HINTS = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
            ),
            DecodeHintType.TRY_HARDER to true,
        )
    }
}
