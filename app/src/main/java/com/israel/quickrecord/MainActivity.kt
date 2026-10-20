package com.israel.quickrecord

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
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
            setPadding(p(16), p(16), p(16), p(32))
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
            Log.w(Constants.TAG, "receiver unregister: ${e.message}")
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private fun p(v: Int): Int = (v * dp).toInt()

    private fun cardLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = p(16) }

    private fun rowLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = p(6) }

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

        val cam = granted(Manifest.permission.CAMERA)
        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val notif = granted(Manifest.permission.POST_NOTIFICATIONS)
        val loc = granted(Manifest.permission.ACCESS_FINE_LOCATION)

        statusBody.addView(statusRow(getString(R.string.label_camera), cam), rowLp())
        statusBody.addView(statusRow(getString(R.string.label_microphone), mic), rowLp())
        statusBody.addView(statusRow(getString(R.string.label_notifications), notif), rowLp())
        statusBody.addView(statusRow(getString(R.string.label_location), loc), rowLp())
        statusBody.addView(
            statusRow(getString(R.string.label_recording_now), Constants.isRecording),
            rowLp()
        )

        val err = TextView(this).apply {
            text = if (Constants.lastError == null) {
                getString(R.string.label_last_error) + ": " + getString(R.string.state_none)
            } else {
                getString(R.string.label_last_error) + ":\n" + Constants.lastError
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.state_error))
            setPadding(0, p(12), 0, 0)
        }
        statusBody.addView(err, rowLp())

        val grant = Button(this).apply {
            text = getString(R.string.btn_grant_permissions)
            setOnClickListener { requestAllPermissions() }
        }
        statusBody.addView(grant, rowLp())
    }

    private fun granted(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun requestAllPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missing = wanted.filter { !granted(it) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
            filesBody.addView(empty, rowLp())
            return
        }

        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, p(10), 0, p(10))
                isClickable = true
                setOnClickListener { showDetails(item) }
            }
            val name = TextView(this).apply {
                text = item.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(getColor(R.color.text_primary))
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
            row.addView(name)
            row.addView(meta)
            filesBody.addView(row, rowLp())
        }
    }

    private fun loadFiles(): List<FileItem> {
        val folder = currentFolder()
        val out = mutableListOf<FileItem>()

        val videoArgs = arrayOf("${Environment.DIRECTORY_MOVIES}/$folder/%")
        val videoCursor = try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    BaseColumns._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION
                ),
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                videoArgs,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )
        } catch (e: Exception) {
            Log.w(Constants.TAG, "video query failed: ${e.message}")
            null
        }
        videoCursor?.use { c ->
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

        val audioArgs = arrayOf("${Environment.DIRECTORY_MUSIC}/$folder/%")
        val audioCursor = try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    BaseColumns._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DURATION
                ),
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                audioArgs,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )
        } catch (e: Exception) {
            Log.w(Constants.TAG, "audio query failed: ${e.message}")
            null
        }
        audioCursor?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    FileItem(
                        c.getLong(0),
                        c.getString(1) ?: "unknown",
                        c.getLong(2),
                        c.getLong(3),
                        c.getInt(4),
                        true
                    )
                )
            }
        }

        return out.sortedByDescending { it.date }
    }

    // ------------------------------------------------------------------
    // Details dialog
    // ------------------------------------------------------------------

    private fun showDetails(item: FileItem) {
        val rows = collectDetails(item)

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
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f)
            }
            val value = TextView(this).apply {
                text = pair.second ?: getString(R.string.detail_unknown)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.text_primary))
                layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f)
            }
            row.addView(key)
            row.addView(value)
            content.addView(row)
        }

        val scroller = ScrollView(this).apply { addView(content) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.details_header))
            .setView(scroller)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun collectDetails(item: FileItem): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        out.add(getString(R.string.detail_name) to item.name)
        out.add(getString(R.string.detail_type) to if (item.isAudio) "audio-only" else "video")
        out.add(getString(R.string.detail_id) to item.id.toString())
        out.add(getString(R.string.detail_size) to formatBytes(item.size))
        out.add(
            getString(R.string.detail_duration) to
                if (item.durationMs > 0) formatDuration(item.durationMs) else null
        )
        out.add(
            getString(R.string.detail_date) to
                if (item.date > 0) formatDate(item.date * 1000L) else null
        )

        val uri = if (item.isAudio) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = if (item.isAudio) {
            arrayOf(
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATE_MODIFIED
            )
        } else {
            arrayOf(
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.BITRATE
            )
        }

        try {
            contentResolver.query(
                uri,
                projection,
                BaseColumns._ID + "=?",
                arrayOf(item.id.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    out.add(getString(R.string.detail_path) to c.getString(0))
                    out.add(getString(R.string.detail_mime) to c.getString(1))
                    val mod = c.getLong(2)
                    out.add(
                        getString(R.string.detail_modified) to
                            if (mod > 0) formatDate(mod * 1000L) else null
                    )
                    if (!item.isAudio) {
                        val w = c.getInt(3)
                        val h = c.getInt(4)
                        val br = c.getInt(5)
                        out.add(
                            getString(R.string.detail_resolution) to
                                if (w > 0 && h > 0) "${w} x ${h}" else null
                        )
                        out.add(
                            getString(R.string.detail_bitrate) to
                                if (br > 0) "${br / 1000} kbps" else null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(Constants.TAG, "details query failed: ${e.message}")
        }

        val loc = try {
            getSharedPreferences(Constants.LOCATION_PREFS, MODE_PRIVATE)
                .getString(item.name, null)
        } catch (e: Exception) {
            null
        }
        out.add(getString(R.string.detail_location) to loc)

        return out
    }

    // ------------------------------------------------------------------
    // Settings card
    // ------------------------------------------------------------------

    private fun buildSettings(body: LinearLayout) {
        val hint = TextView(this).apply {
            text = getString(R.string.hint_folder)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, p(8))
        }
        body.addView(hint)

        folderField = EditText(this).apply {
            setText(currentFolder())
            singleLine = true
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            setBackgroundColor(getColor(R.color.bg_input))
            setPadding(p(12), p(12), p(12), p(12))
        }
        body.addView(folderField, rowLp())

        val save = Button(this).apply {
            text = getString(R.string.btn_save)
            setOnClickListener { saveFolder() }
        }
        body.addView(save, rowLp())

        val note = TextView(this).apply {
            text = "Applies to recordings started after saving."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, p(8), 0, 0)
        }
        body.addView(note)
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
            Log.w(Constants.TAG, "state receiver register failed: ${e.message}")
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
        return if (minutes > 0) {
            "$minutes:${seconds.toString().padStart(2, '0')} min"
        } else {
            "$seconds s"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        private const val REQ_PERMS = 1
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
