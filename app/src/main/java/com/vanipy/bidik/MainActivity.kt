package com.vanipy.bidik

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.vanipy.bidik.bot.DcQueueTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home screen: plain-language status first, then the everyday controls
 * (screen recording, page captures, the queue). Messaging setup and
 * diagnostics live in the hidden left menu so the main screen stays
 * readable; every function from the prototype layout is kept, only
 * tucked away.
 *
 * Two stages:
 * - Prototype (unpaired): local file transport, manual Run button, and
 *   a visible hint that opens the setup menu.
 * - Paired: on open the app syncs the queue from the paired contact and
 *   runs the capture pass automatically - the target flow. The Run button
 *   stays only as a manual re-run.
 *
 * Account creation and pairing are explicit user actions from the setup
 * section; the app never creates an account on first launch.
 */
/**
 * One-at-a-time gate for capture runs. The Run button, the on-open auto
 * run and the post-pairing callback can all fire together; overlapping
 * runs would share DcQueueTransport.lastQueueId (wrong ledger) and
 * collide on the run directory.
 */
internal class RunGate {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)
    fun release() = busy.set(false)
}

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var listView: LinearLayout
    internal lateinit var status: TextView
    private lateinit var progress: ProgressBar
    internal lateinit var setupBox: LinearLayout

    private val qrScanner =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents
            if (contents != null) completePairing(contents)
        }

    // Per-session system consent for screen recording. The result token
    // backs exactly one recording session and is handed to the service.
    private val recordConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val intent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, intent)
                status.text = "recording the screen... stop from the notification or here"
                DiagLog.log(this, "record", "consent_granted")
            } else {
                status.text = "screen recording not started: consent not granted"
                DiagLog.log(this, "record", "consent_denied")
            }
            renderRecordSection()
        }

    internal lateinit var drawer: DrawerLayout
    private lateinit var recordBtn: Button
    private lateinit var menuHintBtn: Button
    private lateinit var recordingsBox: LinearLayout
    private lateinit var delayBtn: Button
    private lateinit var autoStopBtn: Button
    private lateinit var barsBtn: Button
    private var soundBtn: Button? = null
    private lateinit var micBtn: Button
    private lateinit var motionBtn: Button
    private lateinit var shakeBtn: Button

    private val recordStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(ScreenRecordService.EXTRA_STATE)) {
                ScreenRecordService.STATE_STARTING -> {
                    val left = intent.getIntExtra(ScreenRecordService.EXTRA_REMAINING_SEC, -1)
                    status.text = if (left > 0) {
                        "recording starts in ${left}s - switch to the screen to share"
                    } else {
                        "preparing the recorder..."
                    }
                }
                ScreenRecordService.STATE_RECORDING ->
                    status.text = if (intent.getBooleanExtra(
                            ScreenRecordService.EXTRA_SOUND_MISSING, false
                        )
                    ) {
                        "recording without the sound you chose (see the notification)"
                    } else {
                        "recording the screen... stop from the notification or here"
                    }
                ScreenRecordService.STATE_SAVED -> status.text = "recording saved on this phone"
                ScreenRecordService.STATE_FAILED -> status.text = "screen recording failed or was empty"
                ScreenRecordService.STATE_CANCELED -> status.text = "recording canceled before it started"
            }
            renderRecordSection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Main content: title, status, then one scrolling column with the
        // everyday sections. Section headers make the screen readable at a
        // glance; everything the prototype layout had is still here.
        val menuBtn = Button(this).apply {
            text = "Menu"
            setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(menuBtn)
            addView(TextView(this@MainActivity).apply {
                text = "bidik"
                textSize = 20f
                setPadding(16, 8, 8, 8)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        status = TextView(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(24, 8, 24, 8) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        menuHintBtn = Button(this).apply {
            text = "Messaging sync: not connected (see menu)"
            setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        }

        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recordingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val runBtn = Button(this).apply {
            text = "Run capture"
            setOnClickListener { runCapture() }
        }
        val optionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        delayBtn = Button(this).apply {
            setOnClickListener { RecordOptions.cycleDelay(this@MainActivity); renderRecordSection() }
        }
        autoStopBtn = Button(this).apply {
            setOnClickListener { RecordOptions.cycleAutoStop(this@MainActivity); renderRecordSection() }
        }
        barsBtn = Button(this).apply {
            setOnClickListener {
                RecordOptions.setBarsHidden(this@MainActivity, !RecordOptions.barsHidden(this@MainActivity))
                renderRecordSection()
            }
        }
        optionsRow.addView(delayBtn, LinearLayout.LayoutParams(0, -2, 1f))
        optionsRow.addView(autoStopBtn, LinearLayout.LayoutParams(0, -2, 1f))
        optionsRow.addView(barsBtn, LinearLayout.LayoutParams(0, -2, 1f))
        // Second row: the opt-in capture extras. Game sound needs Android
        // 10's AudioPlaybackCapture, so its button exists only there; mic,
        // motion and shake work everywhere. All default off.
        val captureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (RecordOptions.soundSupported()) {
            soundBtn = Button(this).apply {
                setOnClickListener {
                    RecordOptions.setSoundEnabled(
                        this@MainActivity, !RecordOptions.soundEnabled(this@MainActivity)
                    )
                    renderRecordSection()
                }
            }
            captureRow.addView(soundBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        micBtn = Button(this).apply {
            setOnClickListener {
                RecordOptions.setMicEnabled(
                    this@MainActivity, !RecordOptions.micEnabled(this@MainActivity)
                )
                renderRecordSection()
            }
        }
        motionBtn = Button(this).apply {
            setOnClickListener {
                RecordOptions.setMotionEnabled(
                    this@MainActivity, !RecordOptions.motionEnabled(this@MainActivity)
                )
                renderRecordSection()
            }
        }
        shakeBtn = Button(this).apply {
            setOnClickListener {
                RecordOptions.setShakeEnabled(
                    this@MainActivity, !RecordOptions.shakeEnabled(this@MainActivity)
                )
                renderRecordSection()
            }
        }
        captureRow.addView(micBtn, LinearLayout.LayoutParams(0, -2, 1f))
        captureRow.addView(motionBtn, LinearLayout.LayoutParams(0, -2, 1f))
        captureRow.addView(shakeBtn, LinearLayout.LayoutParams(0, -2, 1f))
        recordBtn = Button(this).apply {
            setOnClickListener { onRecordClicked() }
        }

        fun sectionHeader(label: String) = TextView(this).apply {
            text = label
            textSize = 16f
            setPadding(24, 24, 24, 4)
        }
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionHeader("Screen recording"))
            addView(optionsRow)
            addView(captureRow)
            addView(recordBtn)
            addView(recordingsBox)
            addView(sectionHeader("Page captures"))
            addView(runBtn)
            addView(listView)
        }
        val mainScroll = ScrollView(this).apply { addView(mainColumn) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(status)
            addView(progress)
            addView(mainScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(menuHintBtn)
        }

        // Hidden left menu: messaging setup and diagnostics. The setup
        // content (account, pairing, retention notes) can exceed the
        // leftover screen on small phones; it must scroll or the lower
        // rows are unreachable.
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 16)
        }
        val setupScroll = ScrollView(this).apply { addView(setupBox) }
        val drawerColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "advanced: setup & diagnostics"
                textSize = 16f
                setPadding(24, 24, 24, 4)
            })
            addView(setupScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Diagnostics"
                setOnClickListener { showDiagnostics() }
            })
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val density = resources.displayMetrics.density
        val drawerWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt()
            .coerceAtMost((320 * density).toInt())
        val drawerParams = DrawerLayout.LayoutParams(drawerWidth, -1, GravityCompat.START)
        drawerColumn.layoutParams = drawerParams

        drawer = DrawerLayout(this).apply {
            addView(content, DrawerLayout.LayoutParams(-1, -1))
            addView(drawerColumn, drawerParams)
        }
        setContentView(drawer)
        DiagLog.log(this, "app", "open")

        val filter = IntentFilter(ScreenRecordService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(recordStatusReceiver, filter)
        }
        renderRecordSection()

        renderSetup()
        if (SetupController.isPaired(this)) {
            // Target flow: open -> sync -> capture -> send, no taps.
            refreshList { runCapture() }
        } else {
            refreshList()
        }
    }

    @Deprecated("DrawerLayout edge handling")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private var transport: QueueTransport? = null

    internal val runGate = RunGate()

    // One transport instance per pairing state. DcQueueTransport keeps
    // run-scoped state (lastQueueId) that finishRun needs; building a
    // fresh instance per call silently dropped the run ledger, so
    // record_queue_outcome never ran.
    internal fun currentTransport(): QueueTransport {
        transport?.let { return it }
        val peer = SetupController.peerAddr(this)
        val t = if (SetupController.isPaired(this) && peer != null) {
            DcQueueTransport(this, peer)
        } else {
            LocalQueueTransport(filesDir)
        }
        transport = t
        return t
    }

    private fun renderSetup() {
        setupBox.removeAllViews()
        menuHintBtn.visibility = if (SetupController.isPaired(this)) View.GONE else View.VISIBLE
        if (SetupController.isPaired(this)) {
            setupBox.addView(Button(this).apply {
                text = "Unpair"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Stop syncing with this contact? The messaging account and past captures stay on this phone.")
                        .setPositiveButton("Unpair") { _, _ ->
                            SetupController.unpair(this@MainActivity)
                            transport = null
                            renderSetup()
                            refreshList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            setupBox.addView(TextView(this).apply {
                text = "paired: ${SetupController.peerAddr(this@MainActivity)}"
            })
            setupBox.addView(TextView(this).apply {
                text = "on open: syncs the queue, captures each page, sends results back automatically. Captures are archived per run on this phone and kept until the app's data is cleared."
            })
            return
        }
        val state = TextView(this)
        setupBox.addView(state)
        scope.launch {
            val addr = SetupController.selfAddress(this@MainActivity)
            state.text = if (addr != null) "messaging address: $addr" else "messaging not set up"
        }
        if (!SetupController.isProvisioned(this)) {
            // Pairing needs the account first: the handshake runs over
            // the account's own transport, and the peer address comes
            // back from it.
            setupBox.addView(TextView(this).apply {
                text = "legacy route: a separate messaging account inside bidik. Sync through jin is the planned direction and is not built yet."
            })
            setupBox.addView(TextView(this).apply {
                text = "step 1: create a NEW messaging account on relay nine.testrun.org. Its keys live only on this phone; backup/export is not built yet, so clearing app data loses the account."
            })
            setupBox.addView(Button(this).apply {
                text = "Create messaging account"
                setOnClickListener { btn ->
                    btn.isEnabled = false
                    status.text = "creating messaging account..."
                    scope.launch {
                        try {
                            val addr = SetupController.provision(this@MainActivity, "nine.testrun.org")
                            status.text = "account ready"
                            state.text = "messaging address: $addr"
                            renderSetup()
                        } catch (e: Exception) {
                            status.text = "account setup failed: ${e.message}"
                            btn.isEnabled = true
                        }
                    }
                }
            })
            setupBox.addView(TextView(this).apply {
                text = "captures are archived per run on this phone and kept until the app's data is cleared"
            })
            return
        }
        setupBox.addView(TextView(this).apply {
            text = "step 2: pair with the assistant's invite QR"
        })
        setupBox.addView(Button(this).apply {
            text = "Scan pairing QR"
            setOnClickListener {
                qrScanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("scan the pairing QR")
                )
            }
        })
        val qrField = EditText(this).apply { hint = "or paste the pairing QR text" }
        setupBox.addView(qrField)
        setupBox.addView(Button(this).apply {
            text = "Pair from pasted QR"
            setOnClickListener {
                val qr = qrField.text.toString().trim()
                if (qr.isNotEmpty()) completePairing(qr) else status.text = "paste the QR text first"
            }
        })
        setupBox.addView(TextView(this).apply {
            text = "captures are archived per run on this phone and kept until the app's data is cleared"
        })
    }

    private fun completePairing(qrText: String) {
        status.text = "pairing..."
        scope.launch {
            try {
                val addr = SetupController.completePairing(this@MainActivity, qrText)
                status.text = "paired: $addr"
                transport = null
                renderSetup()
                refreshList { runCapture() }
            } catch (e: Exception) {
                status.text = "pairing failed: ${e.message}"
            }
        }
    }

    /**
     * Android requires the RECORD_AUDIO runtime grant for app-sound
     * capture too, so it is asked before the consent prompt whenever a
     * sound option is on. The answer either way continues to consent:
     * a decline means video-only, which the service says visibly.
     */
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            launchRecordConsent()
        }

    private fun onRecordClicked() {
        if (RecordingStore.activeName(this) != null) {
            startService(
                Intent(this, ScreenRecordService::class.java)
                    .setAction(ScreenRecordService.ACTION_STOP)
            )
            return
        }
        val wantsAudio =
            RecordOptions.soundEnabled(this) || RecordOptions.micEnabled(this)
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (wantsAudio && !audioGranted) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchRecordConsent()
    }

    private fun launchRecordConsent() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        recordConsent.launch(mgr.createScreenCaptureIntent())
    }

    /**
     * The record button doubles as the stop control while a session runs,
     * and saved recordings get an explicit share action. Recording files
     * are local; nothing is uploaded automatically.
     */
    internal fun renderRecordSection() {
        val activeName = RecordingStore.activeName(this)
        recordBtn.text = if (activeName != null) "Stop recording" else "Record screen"
        delayBtn.text = "start delay: ${RecordOptions.delayLabel(RecordOptions.delaySeconds(this))}"
        autoStopBtn.text = "auto-stop: ${RecordOptions.autoStopLabel(RecordOptions.autoStopSeconds(this))}"
        barsBtn.text = if (RecordOptions.barsHidden(this)) "system bars: hidden" else "system bars: shown"
        soundBtn?.text =
            if (RecordOptions.soundEnabled(this)) "game sound: on" else "game sound: off"
        micBtn.text = if (RecordOptions.micEnabled(this)) "mic: on" else "mic: off"
        motionBtn.text = if (RecordOptions.motionEnabled(this)) "motion: on" else "motion: off"
        shakeBtn.text = if (RecordOptions.shakeEnabled(this)) "shake: on" else "shake: off"
        recordingsBox.removeAllViews()
        val recordings = RecordingStore.listSharable(this)
        if (recordings.isNotEmpty()) {
            recordingsBox.addView(TextView(this).apply {
                text = "recordings on this phone (local only; share is manual):"
                setPadding(24, 16, 24, 4)
            })
            for (file in recordings) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(TextView(this).apply {
                    text = "${file.name}  ${file.length() / (1024 * 1024)} MB"
                    setPadding(24, 20, 24, 20)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (file.extension == "mp4") {
                    row.addView(Button(this).apply {
                        text = "GIF"
                        setOnClickListener { showGifExport(file) }
                    })
                }
                row.addView(Button(this).apply {
                    text = "View"
                    setOnClickListener { previewRecording(file) }
                })
                row.addView(Button(this).apply {
                    text = "Share"
                    setOnClickListener { shareRecording(file) }
                })
                row.addView(Button(this).apply {
                    text = "Delete"
                    setOnClickListener { confirmDeleteRecording(file) }
                })
                recordingsBox.addView(row)
            }
        }
    }

    internal fun shareRecording(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "com.vanipy.bidik.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (file.extension == "gif") "image/gif" else "video/mp4"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share recording"))
        DiagLog.log(this, "record", "shared")
    }

    /**
     * Opens a recording in whatever viewer the phone has, so it can be
     * checked before anything is shared. Local playback only; the file
     * never leaves the phone for this.
     */
    internal fun previewRecording(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "com.vanipy.bidik.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (file.extension == "gif") "image/gif" else "video/mp4")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "View recording"))
        DiagLog.log(this, "record", "previewed")
    }

    /**
     * Saved recordings only leave the list by an explicit delete, and
     * the delete always asks first. The file is removed from the phone;
     * there is no undo.
     */
    internal fun confirmDeleteRecording(file: File) {
        AlertDialog.Builder(this)
            .setTitle("delete ${file.name}?")
            .setMessage("this removes the file from the phone. this cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (RecordingStore.delete(this, file.name)) {
                    DiagLog.log(this, "record", "deleted", detail = file.name)
                }
                renderRecordSection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Looping-GIF export for one recording. The dialog runs the loop
     * search (same-screen recurrence) and offers its candidates; the
     * start/end fields stay editable as the manual fallback. The source
     * MP4 is never modified.
     */
    internal fun showGifExport(file: File) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val state = TextView(this).apply { text = "looking for loop points..." }
        val candidatesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val startField = EditText(this).apply {
            hint = "start (seconds)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val endField = EditText(this).apply {
            hint = "end (seconds)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val exportBtn = Button(this).apply { text = "Export GIF" }

        // TikTok-style frames viewer for choosing start/end by eye: a
        // scrubbable preview above a thumbnail strip with draggable
        // handles. The auto-detected candidates still fill the span;
        // the seconds fields stay editable as the precise fallback.
        val frameSource = RetrieverFrameSource(file)
        val preview = android.widget.ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val timeline = TrimTimelineView(this)
        val density = resources.displayMetrics.density
        box.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (220 * density).toInt()))
        box.addView(timeline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (56 * density).toInt()))
        box.addView(state)
        box.addView(candidatesBox)
        box.addView(startField)
        box.addView(endField)
        box.addView(exportBtn)
        val dialog = AlertDialog.Builder(this)
            .setTitle("looping GIF from ${file.name}")
            .setView(box)
            .setNegativeButton("Close", null)
            .show()
        dialog.setOnDismissListener { timeline.release() }

        fun showFrameAt(timeMs: Long) {
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val bmp = frameSource.frameAt(timeMs * 1000L, preview.width.coerceAtLeast(320))
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (bmp != null) preview.setImageBitmap(bmp)
                }
            }
        }
        timeline.listener = { startMs, endMs, scrubbing, scrubMs ->
            startField.setText((startMs / 1000.0).toString())
            endField.setText((endMs / 1000.0).toString())
            showFrameAt(if (scrubbing) scrubMs else startMs)
        }
        fun applyFieldsToHandles() {
            val s = (startField.text.toString().toDoubleOrNull() ?: return) * 1000
            val e = (endField.text.toString().toDoubleOrNull() ?: return) * 1000
            timeline.setSpan(s.toLong(), e.toLong())
        }
        startField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyFieldsToHandles() }
        endField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyFieldsToHandles() }
        if (frameSource.durationMs() > 0) {
            timeline.setSource(frameSource)
            showFrameAt(0L)
        } else {
            state.text = "could not read this recording's frames"
            timeline.visibility = android.view.View.GONE
            preview.visibility = android.view.View.GONE
        }

        // The loop search skips the same bands the hidden-bars option
        // crops away, so a clock change there cannot break a match.
        val bands = if (RecordOptions.barsHidden(this)) SystemBars.bands(this) else null
        val metrics = resources.displayMetrics
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val result = try {
                LoopAnalyzer.analyze(
                    file,
                    maskTopFraction = if (bands != null && metrics.heightPixels > 0) {
                        bands.topPx.toFloat() / metrics.heightPixels
                    } else 0f,
                    maskBottomFraction = if (bands != null && metrics.heightPixels > 0) {
                        bands.bottomPx.toFloat() / metrics.heightPixels
                    } else 0f
                )
            } catch (e: Exception) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    state.text = "loop search failed; type the span by hand"
                }
                return@launch
            }
            launch(kotlinx.coroutines.Dispatchers.Main) {
                if (result.candidates.isEmpty()) {
                    state.text = if (result.bestEffort != null) {
                        "no clean loop found; best guess filled in - adjust by hand"
                    } else {
                        "no loop found in this recording; type the span by hand"
                    }
                } else {
                    state.text = "loop points found; tap one or adjust by hand:"
                }
                val fill = result.candidates.firstOrNull() ?: result.bestEffort
                if (fill != null) {
                    startField.setText((fill.startMs / 1000.0).toString())
                    endField.setText((fill.endMs / 1000.0).toString())
                }
                for (c in result.candidates) {
                    candidatesBox.addView(Button(this@MainActivity).apply {
                        text = "%.1fs to %.1fs - seam %d%%".format(
                            c.startMs / 1000.0, c.endMs / 1000.0, (c.seam * 100).toInt()
                        )
                        setOnClickListener {
                            startField.setText((c.startMs / 1000.0).toString())
                            endField.setText((c.endMs / 1000.0).toString())
                            timeline.setSpan(c.startMs, c.endMs)
                        }
                    })
                }
            }
        }

        exportBtn.setOnClickListener {
            val startMs = ((startField.text.toString().toDoubleOrNull() ?: -1.0) * 1000).toLong()
            val endMs = ((endField.text.toString().toDoubleOrNull() ?: -1.0) * 1000).toLong()
            if (startMs < 0 || endMs <= startMs) {
                state.text = "start/end need numbers, end after start"
                return@setOnClickListener
            }
            exportBtn.isEnabled = false
            state.text = "exporting..."
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    val target = RecordingStore.newGifFile(this@MainActivity)
                    val partial = java.io.File(cacheDir, target.name + ".part")
                    partial.delete()
                    val outcome = GifExporter.export(file, startMs, endMs, partial,
                        onProgress = { done, total ->
                            launch(kotlinx.coroutines.Dispatchers.Main) {
                                state.text = "exporting frame $done of about $total..."
                            }
                        })
                    if (!partial.renameTo(target) || outcome.bytes <= 0) {
                        partial.delete()
                        throw java.io.IOException("could not move the GIF into place")
                    }
                    DiagLog.log(this@MainActivity, "gif", "exported",
                        detail = "frames=${outcome.frames}")
                    launch(kotlinx.coroutines.Dispatchers.Main) {
                        state.text = "saved ${target.name} (${outcome.width}x${outcome.height}, " +
                            "${outcome.frames} frames, loops forever)"
                        exportBtn.isEnabled = true
                        renderRecordSection()
                    }
                } catch (e: Exception) {
                    launch(kotlinx.coroutines.Dispatchers.Main) {
                        state.text = "GIF export failed: ${e.message}"
                        exportBtn.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderRecordSection()
    }

    private fun refreshList(after: (() -> Unit)? = null) {
        scope.launch {
            val queue = try { currentTransport().fetchQueue() } catch (e: Exception) {
                status.text = "queue sync failed: ${e.message}"
                emptyList()
            }
            listView.removeAllViews()
            for (job in queue) {
                listView.addView(TextView(this@MainActivity).apply {
                    text = "${job.id}  ${job.url}"
                    setPadding(24, 20, 24, 20)
                })
            }
            if (queue.isEmpty()) {
                listView.addView(TextView(this@MainActivity).apply {
                    text = "no pages queued"
                    setPadding(24, 20, 24, 20)
                })
            }
            after?.invoke()
        }
    }

    private fun runCapture() {
        // One run at a time: the button, the on-open auto run and the
        // post-pairing callback can all fire together, and overlapping
        // runs would share DcQueueTransport.lastQueueId (wrong ledger)
        // and collide on the run directory.
        if (!runGate.tryAcquire()) {
            status.text = "a run is already in progress"
            DiagLog.log(this, "run", "rejected", detail = "already in progress")
            return
        }
        progress.visibility = View.VISIBLE
        status.text = "running..."
        scope.launch {
            try {
                // Every run archives into its own unique directory.
                // Captures are kept, never auto-deleted; they leave the
                // phone only via the paired transport, and are gone only
                // when the app's data is cleared.
                val outDir = newRunDir(filesDir)
                val outcome = try {
                    CaptureRunner(this@MainActivity, currentTransport(), outDir).runOnce()
                } catch (e: Exception) {
                    status.text = "run failed: ${e.message}"
                    progress.visibility = View.GONE
                    return@launch
                }
                progress.visibility = View.GONE
                status.text = "done: ${outcome.ok} ok, ${outcome.failed} failed"
                // Ledger: full success serves the queue; partial failure
                // records only the failed pages for the next sync's retry.
                (currentTransport() as? DcQueueTransport)?.finishRun(outcome.failedIds)
            } finally {
                runGate.release()
            }
        }
    }

    internal fun showDiagnostics() {
        val view = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = DiagLog.tail(this@MainActivity)
                setPadding(24, 16, 24, 16)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Diagnostics (no keys, no message or page contents)")
            .setView(view)
            .setPositiveButton("Share full log") { _, _ -> shareDiagnostics() }
            .setNegativeButton("Close", null)
            .show()
    }

    internal fun shareDiagnostics() {
        val export = File(cacheDir, "diagnostics/bidik-diagnostics.txt")
        export.parentFile?.mkdirs()
        export.writeText(DiagLog.fullText(this))
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "com.vanipy.bidik.fileprovider", export
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share diagnostics"))
    }

    internal fun newRunDir(base: File): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            .format(java.util.Date())
        val suffix = (1000..9999).random()
        return File(base, "captures/run-$stamp-$suffix")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(recordStatusReceiver)
        } catch (_: IllegalArgumentException) {
        }
        scope.cancel()
    }
}
