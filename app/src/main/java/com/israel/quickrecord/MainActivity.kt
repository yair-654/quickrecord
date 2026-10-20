package com.israel.quickrecord

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface

class MainActivity : Activity() {

    private var dp = 1f
    private lateinit var statusBody: LinearLayout
    private lateinit var filesBody: LinearLayout
    private lateinit var folderField: EditText
    private lateinit var stateReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density

        Constants.customFolderOverride =
            SharedPreferencesWrapper(this).getFolder().takeIf { it.isNotEmpty() }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg_root))
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(16), p(64), p(16), p(32))
        }

        val statusCard = makeCard(getString(R.string.status_header))
        statusBody = statusCard.second
        col.addView(statusCard.first, cardLp())

        val filesCard = makeCard(getString(R.string.files_header))
        filesBody = filesCard.second
        col.addView(filesCard.first, cardLp())

        val settingsCard = makeCard(getString(R.string.settings_header))
        buildSettings(settingsCard.second)
        col.addView(settingsCard.first, cardLp())

        val footer = TextView(this).apply {
            text = getString(R.string.made_in_israel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, p(24), 0, p(8))
        }
        col.addView(footer)

        scroll.addView(col)
        setContentView(scroll)

        registerStateReceiver()
        refreshStatus()
        refreshFiles()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshFiles()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    private fun p(v: Int): Int = (v * dp).toInt()

    private fun cardLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = p(16) }

    private fun makeCard(title: String): Pair<LinearLayout, LinearLayout> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_card))
            setPadding(p(16), p(16), p(16), p(16))
        }
        val header = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, p(12))
        }
        card.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(body)
        return Pair(card, body)
    }

    private fun statusRow(label: String, ok: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, p(4), 0, p(4))
        }
        val name = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val state = TextView(this).apply {
            text = if (ok) getString(R.string.state_yes) else getString(R.string.state_no)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (ok) getColor(R.color.state_ok) else getColor(R.color.state_error))
            gravity = Gravity.END
        }
        row.addView(name)
        row.addView(state)
        return row
    }

    // ------------------------------------------------------------------
    // Status card
    // ------------------------------------------------------------------

    private fun refreshStatus() {
        statusBody.removeAllViews()

        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        statusBody.addView(statusRow(getString(R.string.label_camera), cam))
        statusBody.addView(statusRow(getString(R.string.label_microphone), mic))
        statusBody.addView(statusRow(getString(R.string.label_notifications), notif))
        statusBody.addView(statusRow(getString(R.string.label_location), loc))
        statusBody.addView(statusRow(getString(R.string.label_recording_now), Constants.isRecording))

        val err = TextView(this).apply {
            text = if (Constants.lastError == null) {
                "Last error: none"
            } else {
                "Last error:\n" + Constants.lastError
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.state_error))
            setPadding(0, p(12), 0, 0)
        }
        statusBody.addView(err)

        val grant = Button(this).apply {
            text = getString(R.string.btn_grant_permissions)
            setOnClickListener { requestAllPermissions() }
        }
        statusBody.addView(grant)
    }

    private fun requestAllPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        refreshStatus()
    }

    // ------------------------------------------------------------------
    // Files card
    // ------------------------------------------------------------------

    private fun currentFolder(): String =
        (Constants.customFolderOverride ?: Constants.DEFAULT_FOLDER)
            .trim().replace("/", "-").ifEmpty { Constants.DEFAULT_FOLDER }

    private fun refreshFiles() {
        filesBody.removeAllViews()
        val items = loadFiles()

        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.empty_files)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, p(8), 0, 0)
            }
            filesBody.addView(empty)
            return
        }

        // Inner scrollable list, capped at roughly four rows tall.
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (item in items) {
            list.addView(makeFileRow(item))
        }

        val innerScroll = ScrollView(this).apply {
            addView(list)
            isVerticalScrollBarEnabled = true
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { height = p(FOUR_ROW_HEIGHT_DP) }

        filesBody.addView(innerScroll, lp)
    }

    private fun makeFileRow(item: FileItem): View {
        // Outer row: text part on the left, Info button on the right.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, p(10), 0, p(10))
        }

        // Text part: tapping plays the file.
        val textPart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { openFile(item) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = TextView(this).apply {
            text = item.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.accent))
        }
        val meta = TextView(this).apply {
            val bits = mutableListOf<String>()
            if (item.size > 0) bits.add(formatBytes(item.size))
            if (item.durationMs > 0) bits.add(formatDuration(item.durationMs))
            if (item.date > 0) bits.add(formatDate(item.date * 1000L))
            text = bits.joinToString("  •  ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, p(2), 0, 0)
        }
        textPart.addView(name)
        textPart.addView(meta)
        outer.addView(textPart)

        // Info button: opens the details dialog, does not play.
        val info = Button(this).apply {
            text = "Info"
            setOnClickListener { showDetails(item) }
        }
        outer.addView(info)

        return outer
    }

    private fun openFile(item: FileItem) {
        try {
            val contentUri = Uri.withAppendedPath(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                item.id.toString()
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No player found for this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFiles(): List<FileItem> {
        val folder = currentFolder()
        val out = mutableListOf<FileItem>()

        try {
            val args = arrayOf("${Environment.DIRECTORY_MOVIES}/$folder/%")
            val cursor = contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    BaseColumns._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION
                ),
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                args,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        FileItem(
                            c.getLong(0),
                            c.getString(1) ?: "unknown",
                            c.getLong(2),
                            c.getLong(3),
                            c.getInt(4),
                            false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return out.sortedByDescending { it.date }
    }

    // ------------------------------------------------------------------
    // Details dialog
    // ------------------------------------------------------------------

    private fun showDetails(item: FileItem) {
        val prefs = getSharedPreferences("qr_locations", MODE_PRIVATE)
        val location = prefs.getString(item.name, null)

        val rows = mutableListOf<Pair<String, String?>>()
        rows.add("Name" to item.name)
        rows.add("Type" to if (item.isAudio) "audio-only" else "video")
        rows.add("ID" to item.id.toString())
        rows.add("Size" to formatBytes(item.size))
        rows.add("Duration" to if (item.durationMs > 0) formatDuration(item.durationMs) else null)
        rows.add("Date" to if (item.date > 0) formatDate(item.date * 1000L) else null)

        // Extra metadata from the Video collection.
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT
                ),
                BaseColumns._ID + "=?",
                arrayOf(item.id.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    rows.add("Path" to c.getString(0))
                    rows.add("MIME" to c.getString(1))
                    val mod = c.getLong(2)
                    rows.add("Modified" to if (mod > 0) formatDate(mod * 1000L) else null)
                    val w = c.getInt(3)
                    val h = c.getInt(4)
                    rows.add("Resolution" to if (w > 0 && h > 0) "${w} x ${h}" else null)
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        rows.add("Location" to location)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(20), p(8), p(20), p(8))
        }

        for (pair in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, p(6), 0, p(6))
            }
            val key = TextView(this).apply {
                text = pair.first
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f)
            }
            val value = TextView(this).apply {
                text = pair.second ?: "unknown"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f)
            }
            row.addView(key)
            row.addView(value)
            content.addView(row)
        }

        val scroller = ScrollView(this).apply { addView(content) }

        AlertDialog.Builder(this)
            .setTitle("File details")
            .setView(scroller)
            .setPositiveButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Settings card
    // ------------------------------------------------------------------

    private fun buildSettings(body: LinearLayout) {
        val hint = TextView(this).apply {
            text = "Folder under Movies/, for example QuickRecord"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, p(8))
        }
        body.addView(hint)

        folderField = EditText(this).apply {
            setText(currentFolder())
            maxLines = 1
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            setBackgroundColor(getColor(R.color.bg_input))
            setPadding(p(12), p(12), p(12), p(12))
        }
        body.addView(folderField)

        val save = Button(this).apply {
            text = getString(R.string.btn_save)
            setOnClickListener { saveFolder() }
        }
        body.addView(save)
    }

    private fun saveFolder() {
        val value = folderField.text.toString().trim().replace("/", "-")
        val effective = value.ifEmpty { Constants.DEFAULT_FOLDER }
        Constants.customFolderOverride = effective
        SharedPreferencesWrapper(this).saveFolder(effective)
        Toast.makeText(this, "Folder set to $effective", Toast.LENGTH_SHORT).show()
        refreshFiles()
    }

    // ------------------------------------------------------------------
    // Live state updates
    // ------------------------------------------------------------------

    private fun registerStateReceiver() {
        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runOnUiThread {
                    refreshStatus()
                    refreshFiles()
                }
            }
        }
        try {
            registerReceiver(
                stateReceiver,
                IntentFilter(Constants.BROADCAST_RECORDING_STATE_CHANGED),
                RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            // ignore
        }
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private fun formatDate(epochMs: Long): String =
        android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", epochMs).toString()

    private fun formatDuration(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "$minutes:${seconds.toString().padStart(2, '0')} min" else "$seconds s"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val FOUR_ROW_HEIGHT_DP = 232
    }
}

class SharedPreferencesWrapper(private val ctx: Context) {
    fun saveFolder(path: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER, path)
            .apply()
    }

    fun getFolder(): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER, Constants.DEFAULT_FOLDER) ?: Constants.DEFAULT_FOLDER

    companion object {
        private const val PREFS = "quickrecord_prefs"
        private const val KEY_FOLDER = "custom_folder"
    }
}

data class FileItem(
    val id: Long,
    val name: String,
    val date: Long,
    val size: Long,
    val durationMs: Int,
    val isAudio: Boolean
)
