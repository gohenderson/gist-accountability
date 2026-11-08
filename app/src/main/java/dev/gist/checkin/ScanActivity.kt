package dev.gist.checkin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Registers a QR code / barcode: scans once and returns its text as [EXTRA_CODE]. */
class ScanActivity : Activity() {
    private lateinit var scanner: ScannerView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.add(label("Register a code", 26f, bold = true))
        col.add(
            label(
                "Scan something that stays put where you want to be when the alarm rings: " +
                    "a printed QR sticker by the sink, the barcode on your toothpaste, the scale…",
                14f
            ),
            dp(6)
        )
        scanner = ScannerView(this)
        col.add(scanner, dp(16))
        status = label("Point the camera at the code.", 15f)
        col.add(status, dp(12))
        col.add(button("Cancel") { finish() }, dp(16))

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)

        if (!hasCamera()) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (!hasCamera()) return // the permission dialog's result brings us back here
        scanner.start(
            onCode = { code ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
                finish()
            },
            onError = { msg -> status.text = msg }
        )
    }

    override fun onPause() {
        scanner.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_CAMERA && !hasCamera()) {
            Toast.makeText(this, "Scanning needs the camera permission.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_CODE = "code"
        private const val REQ_CAMERA = 3
    }
}
