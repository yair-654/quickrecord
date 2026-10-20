package com.israel.quickrecord

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusCard: CardView
    private lateinit var filesCard: CardView
    private lateinit var settingsCard: CardView
    private lateinit var statusLayout: LinearLayout
    private lateinit var filesLayout: LinearLayout
    private lateinit var settingsLayout: LinearLayout

    private lateinit var filesReceiver: BroadcastReceiver

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density

        val root = ScrollView(this).apply {
            setBackgroundColor(getColorByAttr(R.attr.bg_root))
            overScrollMode = OVER_SCROLL_NEVER
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((dp * 16).toInt(), (dp * 16).toInt(), (dp * 16).toInt(), (dp * 32).toInt())
        }

        // Status card
        statusCard = CardView(this, "Status").apply {
            statusLayout = cardBody as LinearLayout
            refreshStatus()
        }
        inner.addView(statusCard.cardRoot, cardLayoutParams(dp))

        // Files card
        filesCard = CardView(this, "Recordings").apply {
            filesLayout = cardBody as LinearLayout
            refreshFiles()
        }
        inner.addView(filesCard.cardRoot, cardLayoutParams(dp))

        // Settings card
        settingsCard = CardView(this, "Settings").apply {
            settingsLayout = cardBody as LinearLayout
            setupSettings(dp)
        }
        inner.addView(settingsCard.cardRoot, cardLayoutParams(dp))

        // Israel footer
        val madeFooter = TextView(this).apply {
            text = getString(R.string.made_in_israel)
            textSize = 14f
            setTextColor(getColorByAttr(R.attr.text_secondary))
            setPadding(0, (dp * 32).toInt(), 0, (dp * 16).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        inner.addView(madeFooter)

        root.addView(inner)
        setContentView(root)

        registerStateReceiver()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshFiles()
    }

    override fun onDestroy() {
        if (::filesReceiver.isInitialized) {
            try { unregisterReceiver(filesReceiver) } catch (e: Exception) { }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    private fun refreshStatus() {
        statusLayout.removeAllViews()

        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val files = checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

        val header = LabelRow(getString(R.string.status_header))
        statusLayout.addView(header.root, layoutParams())

        val perms = RowWithCheckbox(this, getString(R.string.label_camera), cam)
        statusLayout.addView(perms.root, layoutParams())

        val micr = RowWithCheckbox(this, getString(R.string.label_microphone), mic)
        statusLayout.addView(micr.root, layoutParams())

        val not = RowWithCheckbox(this, getString(R.string.label_notifications), notif)
        statusLayout.addView(not.root, layoutParams())

        val locl = RowWithCheckbox(this, getString(R.string.label_location), loc)
        statusLayout.addView(locl.root, layoutParams())

        val file = RowWithCheckbox(this, getString(R.string.label_files_access), files)
        statusLayout.addView(file.root, layoutParams())

        val recording = RowWithCheckbox(this, getString(R.string.label_recording_now), Constants.isRecording)
        statusLayout.addView(recording.root, layoutParams())

        val error = TextView(this).apply {
            text = if (Constants.lastError == null) {
                getString(R.string.state_none)
            } else {
                "${getString(R.string.label_last_error)}\n${Constants.lastError}"
            }
            textSize = 13f
            setTextColor(getColorByAttr(R.attr.state_error))
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }
        statusLayout.addView(error, layoutParams())

        val btn = Button(this).apply {
            text = getString(R.string.btn_grant_permissions)
            setOnClickListener { requestAllPermissions() }
        }
        statusLayout.addView(btn, layoutParams())
    }

    private fun requestAllPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.CAMERA)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            refreshStatus()
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
        Toast.makeText(this, "Permission changes applied", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private fun refreshFiles() {
        filesLayout.removeAllViews()

        val header = LabelRow(getString(R.string.files_header))
        filesLayout.addView(header.root, layoutParams())

        val folder = (Constants.customFolderOverride ?: Constants.DEFAULT_FOLDER)
            .trim().replace("/", "-").ifEmpty { Constants.DEFAULT_FOLDER }
        val sel = ContentValues().apply {
            put(MediaStore.Video.Media.MEDIA_TYPE, MediaStore.Video.TYPE_VIDEO)
        }
        val uri = Uri.withAppendedPath(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "?$MediaStore.Video.Media.RELATIVE_PATH=$Movies/$folder&$MediaStore.Video.Media.MIME_TYPE=video/mp4"
        )

        val cursor = contentResolver.query(
            uri,
            arrayOf(BaseColumns._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION),
            null, null, MediaStore.Video.Media.DATE_ADDED + " DESC"
        )

        if (cursor == null || !cursor.moveToFirst()) {
            if (cursor != null) cursor.close()
            val empty = TextView(this).apply {
                text = getString(R.string.empty_files)
                textSize = 14f
                setTextColor(getColorByAttr(R.attr.text_secondary))
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            filesLayout.addView(empty, layoutParams())
            return
        }

        val list = mutableListOf<FileItem>()
        do {
            try {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))
                val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                val dur = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                list.add(FileItem(id, name, date, size, dur))
            } catch (e: Exception) { continue }
        } while (cursor.moveToNext())
        cursor.close()

        if (list.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.empty_files)
                textSize = 14f
                setTextColor(getColorByAttr(R.attr.text_secondary))
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            filesLayout.addView(empty, layoutParams())
            return
        }

        for (item in list) {
            val row = FileRow(this, item) { detailsFor(item) }
            filesLayout.addView(row.root, layoutParams())
        }
    }

    private fun detailsFor(item: FileItem) {
        val prefs = getSharedPreferences(Constants.LOCATION_PREFS, MODE_PRIVATE)
        val locationSidecar = prefs.getString(item.name, null)

        val resolver = contentResolver
        val cursor = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.BITRATE,
                MediaStore.Video.Media.CODEC,
                MediaStore.Video.Media.MIME_TYPE
            ),
            "${BaseColumns._ID}=?",
            arrayOf("${item.id}"),
            null
        )

        var path: String? = null
        var width: Int? = null
        var height: Int? = null
        var bitrate: Int? = null
        var codec: String? = null
        var mime: String? = null
        var added: Long? = null
        var modified: Long? = null

        try {
            if (cursor != null && cursor.moveToFirst()) {
                path = cursor.getString(0)
                width = cursor.getInt(1)
                height = cursor.getInt(2)
                bitrate = cursor.getInt(3)
                codec = cursor.getString(4)
                mime = cursor.getString(5)
                added = cursor.getLong(6)
                modified = cursor.getLong(7)
            }
        } finally {
            cursor?.close()
        }

        val layout = GridLayout(this).apply {
            columnCount = 2
            setPadding((16 * dp).toInt(), 0, 0, 0)
        }

        fun label(text: String): TextView = TextView(this).apply {
            text = text
            textSize = 13f
            setTextColor(getColorByAttr(R.attr.text_secondary))
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        fun value(v: String?): TextView = TextView(this).apply {
            text = v ?: getString(R.string.detail_unknown)
            textSize = 13f
            setTextColor(getColorByAttr(R.attr.text_primary))
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        val pairs: List<Pair<String, String?>> = listOf(
            getString(R.string.detail_name) to item.name,
            getString(R.string.detail_path) to path,
            getString(R.string.detail_date) to (if (item.date > 0) formatDateTime(item.date * 1000L) else null),
            getString(R.string.detail_size) to (formatBytes(item.size)),
            getString(R.string.detail_duration) to (if (item.durationMs > 0) formatDuration(item.durationMs) else null),
            getString(R.string.detail_id) to ("${item.id}"),
            getString(R.string.detail_resolution) to (if (width != null && height != null) "${width}x${height}" else null),
            getString(R.string.detail_bitrate) to (if (bitrate != null) "${bitrate} bps" else null),
            getString(R.string.detail_framerate) to (null),
            getString(R.string.detail_mime) to mime,
            getString(R.string.detail_type) to (if (item.name.startsWith("AUD_")) "audio-only" else "video"),
            getString(R.string.detail_added) to (if (added != null) formatDateTime(added * 1000L) else null),
            getString(R.string.detail_modified) to (if (modified != null) formatDateTime(modified * 1000L) else null),
            getString(R.string.detail_location) to locationSidecar
        )

        var r = 0
        for ((lbl, valStr) in pairs) {
            layout.addView(label(lbl), GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(0, 1f)
                rowSpec = GridLayout.spec(r++)
            })
            layout.addView(value(valStr), GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(1, 1f)
                rowSpec = GridLayout.spec(r - 1)
            })
        }

        showDetailsSheet(layout)
    }

    private fun showDetailsSheet(content: View) {
        val popup = PopupWindow(this, null, R.style.Theme_QuickRecord).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            isFocusable = true
            isOutsideTouchable = true
        }

        val wrapper = ScrollView(this).apply {
            setBackgroundColor(getColorByAttr(R.attr.bg_card))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            addView(content)
        }

        popup.contentView = wrapper

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(128, 0, 0, 0))
            setOnTouchListener { _, _ -> popup.dismiss(); true }
            addView(wrapper)
            // Overlay content here
            val closeBtn = Button(this).apply {
                text = getString(R.string.btn_close)
                setOnClickListener { popup.dismiss() }
                setGravity(Gravity.CENTER)
            }
            addView(closeBtn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, (16 * dp).toInt(), (16 * dp).toInt(), 0)
            })
        }

        popup.showAtLocation(overlay, Gravity.NO_GRAVITY, 0, 0)
        windowManager.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private fun setupSettings(dp: Float) {
        settingsLayout.removeAllViews()

        val header = LabelRow(getString(R.string.settings_header))
        settingsLayout.addView(header.root, layoutParams())

        val hint = TextView(this).apply {
            text = getString(R.string.hint_folder)
            textSize = 13f
            setTextColor(getColorByAttr(R.attr.text_secondary))
            setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt())
        }
        settingsLayout.addView(hint, layoutParams())

        val current = Constants.customFolderOverride ?: Constants.DEFAULT_FOLDER
        val folderField = EditText(this).apply {
            setText(current)
            singleLine = true
            hint = getString(R.string.label_folder)
            setTextColor(getColorByAttr(R.attr.text_primary))
            setHintTextColor(getColorByAttr(R.attr.text_secondary))
            setBackgroundColor(getColorByAttr(R.attr.bg_input))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveCustomFolder(this)
                    true
                } else false
            }
        }
        settingsLayout.addView(folderField, layoutParams())

        val saveBtn = Button(this).apply {
            text = getString(R.string.btn_save)
            setOnClickListener { saveCustomFolder(this) }
        }
        settingsLayout.addView(saveBtn, layoutParams())

        val info = TextView(this).apply {
            text = "Folder changes apply to recordings started AFTER pressing Save."
            textSize = 12f
            setTextColor(getColorByAttr(R.attr.text_secondary))
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }
        settingsLayout.addView(info, layoutParams())
    }

    private fun saveCustomFolder(view: View) {
        val im = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        im.hideSoftInputFromWindow(view.windowToken, 0)
        val field = view.parent.parent as LinearLayout
        val folder = field.getChildAt(1) as EditText
        val value = folder.text.toString().trim()
        if (value.isEmpty()) {
            Constants.customFolderOverride = null
        } else {
            Constants.customFolderOverride = value.replace("/", "-")
        }
        SharedPreferencesWrapper(this).saveFolder(Constants.customFolderOverride ?: Constants.DEFAULT_FOLDER)
        Toast.makeText(this, "Folder saved", Toast.LENGTH_SHORT).show()
        refreshStatus()
        refreshFiles()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun registerStateReceiver() {
        filesReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runOnUiThread { refreshFiles() }
            }
        }
        try {
            registerReceiver(filesReceiver, IntentFilter(Constants.BROADCAST_RECORDING_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) { Log.w(Constants.TAG, "filesReceiver register failed: ${e.message}") }
    }

    private fun formatDateTime(epochMs: Long): String =
        android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", epochMs).toString()

    private fun formatDuration(ms: Int): String {
        val s = ms / 1000
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "${m}:${sec.toString().padStart(2, '0')} min" else "${sec}s"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    private fun layoutParams(): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        weight = 1f
        setMargins(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun cardLayoutParams(dp: Float): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = GridLayout.LayoutParams.MATCH_PARENT
        setMargins(0, 0, 0, (16 * dp).toInt())
    }

    private fun getColorByAttr(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }

    companion object {
        private const val REQ_PERMS = 1
    }
}

// Minimal UI components

class LabelRow(private val text: String) {
    val root: TextView = TextView(null).apply {
        this@LabelRow.let {
            val tv = this
            tv.text = it.text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setTextColor(it.getColorByAttr(android.R.attr.textColorPrimary))
            val dp = it.resources.displayMetrics.density
            tv.setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
    }

    private fun getColorByAttr(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }

    private fun obtainStyledAttributes(ids: IntArray): android.content.res.TypedArray {
        val theme = android.view.View.getDefaultDisplay()?.context?.theme ?: android.content.res.Resources.getSystem().theme
        return theme.obtainStyledAttributes(null, ids, 0, 0)
    }
}

class RowWithCheckbox(context: Context, label: String, checked: Boolean) {
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
    }
    private val dp: Float = context.resources.displayMetrics.density

    init {
        val txt = TextView(context).apply {
            text = "$label: ${if (checked) context.getString(R.string.state_granted) else context.getString(R.string.state_missing)}"
            textSize = 14f
            setTextColor(context.getColorByAttr(R.attr.text_primary))
            setPadding(0, 0, 0, 0)
        }
        root.addView(txt, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val dot = TextView(context).apply {
            text = if (checked) "✓" else "✗"
            textSize = 14f
            setTextColor(if (checked) context.getColorByAttr(R.attr.state_ok) else context.getColorByAttr(R.attr.state_error))
            gravity = Gravity.RIGHT
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        }
        root.addView(dot, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

class FileRow(context: Context, item: FileItem, private val onClick: () -> Unit) {
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        setBackgroundResource(android.R.drawable.list_selector_background)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    private val dp: Float = context.resources.displayMetrics.density

    init {
        val icon = TextView(context).apply {
            text = if (item.name.startsWith("AUD_")) "🎵" else "🎥"
            textSize = 24f
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        root.addView(icon, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val info = TextView(context).apply {
            val meta = StringBuilder(item.name)
            if (item.size > 0) {
                meta.append(" • ").append(formatBytes(item.size))
            }
            if (item.durationMs > 0) {
                meta.append(" • ").append(formatDuration(item.durationMs))
            }
            text = meta.toString()
            textSize = 14f
            setTextColor(context.getColorByAttr(R.attr.text_primary))
            setPadding((8 * dp).toInt(), 0, 0, 0)
        }
        root.addView(info, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    private fun formatDuration(ms: Int): String {
        val s = ms / 1000
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "${m}:${sec.toString().padStart(2, '0')}" else "${sec}s"
    }
}

class CardView(context: Context, title: String) {
    val cardRoot: FrameLayout = FrameLayout(context)
    val cardBody: LinearLayout = LinearLayout(context)
    private val dp: Float = context.resources.displayMetrics.density

    init {
        cardBody.orientation = LinearLayout.VERTICAL
        cardBody.setBackgroundColor(context.getColorByAttr(R.attr.bg_card))
        cardBody.setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())

        val header = TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(context.getColorByAttr(R.attr.text_primary))
            setPadding(0, 0, 0, (16 * dp).toInt())
        }

        cardRoot.addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            setMargins(0, (16 * dp).toInt(), 0, 0)
        })
        cardRoot.addView(cardBody, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
}

class SharedPreferencesWrapper(private val ctx: Context) {
    fun saveFolder(path: String) {
        ctx.getSharedPreferences("quickrecord_prefs", Context.MODE_PRIVATE).edit()
            .putString("custom_folder", path)
            .apply()
    }
    fun getFolder(): String {
        return ctx.getSharedPreferences("quickrecord_prefs", Context.MODE_PRIVATE).getString("custom_folder", Constants.DEFAULT_FOLDER) ?: Constants.DEFAULT_FOLDER
    }
}

private fun Context.getColorByAttr(attr: Int): Int {
    val ta = theme.obtainStyledAttributes(intArrayOf(attr))
    val c = ta.getColor(0, 0)
    ta.recycle()
    return c
}

data class FileItem(val id: Long, val name: String, val date: Long, val size: Long, val durationMs: Int)
