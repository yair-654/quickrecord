package com.israel.quickrecord

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var errorView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (resources.displayMetrics.density * 24).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        root.addView(title)

        val intro = TextView(this).apply {
            text = "Tap Grant permissions once, then add the QuickRecord tile " +
                "to your Quick Settings panel. Tapping the tile starts a " +
                "recording; tapping it again stops and saves it."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, padding, 0, 0)
        }
        root.addView(intro)

        val grantButton = Button(this).apply {
            text = "Grant permissions"
            setOnClickListener { requestAllPermissions() }
        }
        root.addView(
            grantButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = padding }
        )

        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, padding, 0, 0)
        }
        root.addView(statusView)

        errorView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(200, 30, 30))
            setPadding(0, padding, 0, 0)
        }
        root.addView(errorView)

        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestAllPermissions() {
        requestPermissions(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            REQ_ALL
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_ALL) return

        val missing = mutableListOf<String>()
        permissions.forEachIndexed { i, perm ->
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) {
                missing.add(shortName(perm))
            }
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Not granted: ${missing.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        statusView.text = buildString {
            append("Camera: ").append(if (cam) "granted" else "MISSING").append('\n')
            append("Microphone: ").append(if (mic) "granted" else "MISSING").append('\n')
            append("Notifications: ").append(if (notif) "granted" else "MISSING").append('\n')
            append("Recording now: ")
            append(if (Constants.isRecording) "yes" else "no")
        }

        errorView.text = if (Constants.lastError == null) {
            "Last error: none"
        } else {
            "Last error:\n${Constants.lastError}"
        }
    }

    private fun shortName(perm: String): String =
        perm.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    companion object {
        private const val REQ_ALL = 1
    }
}
