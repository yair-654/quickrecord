package com.israel.quickrecord

/**
 * Global constants and shared state for QuickRecord.
 */
object Constants {

    /** Log tag for every log statement in the app. */
    const val TAG = "QuickRecord"

    /**
     * Architectural switch, spec point (e).
     *
     * true  = primary path: the tile launches RecordActivity via
     *         startActivityAndCollapse, which shows over the lock screen
     *         and starts the service.
     *
     * false = fallback path: the tile starts RecordService directly,
     *         with no activity involved.
     *
     * Changing this requires rebuilding and reinstalling the APK.
     */
    const val USE_ACTIVITY_PATH = true

    /**
     * Battery threshold percent at which recording stops cleanly.
     * Prevents phone death with corrupted file.
     */
    const val BATTERY_THRESHOLD_PERCENT = 5

    /**
     * Default folder name under Movies/ where recordings are saved.
     * User can change this via app settings.
     */
    const val DEFAULT_FOLDER = "QuickRecord"

    /**
     * True while RecordService is actively recording. Written by the service,
     * read by the tile (same process) to decide start versus stop, and by
     * MainActivity for the status screen.
     */
    @Volatile
    var isRecording: Boolean = false

    /**
     * Last error message from the camera or recording pipeline, shown in
     * MainActivity. Volatile so the UI thread always sees the latest write.
     */
    @Volatile
    var lastError: String? = null

    /** Timestamp (millis since boot) of when lastError was set. */
    @Volatile
    var lastErrorTime: Long = 0L

    /**
     * Custom folder path (relative to MediaStore root). Empty means use DEFAULT_FOLDER.
     * Persisted by SharedPreferences in MainActivity, read by RecordService.
     */
    @Volatile
    var customFolderOverride: String? = null

    /** Broadcast action for recording state changes, for live UI updates. */
    const val BROADCAST_RECORDING_STATE_CHANGED = "com.israel.quickrecord.RECORDING_STATE_CHANGED"
}
