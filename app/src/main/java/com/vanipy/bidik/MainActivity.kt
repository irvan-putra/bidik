package com.vanipy.bidik

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * Whole screen is the queue list: no headings, no filler.
 *
 * Two stages:
 * - Prototype (unpaired): local file transport, manual Run button.
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
    private lateinit var status: TextView
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

    private lateinit var recordBtn: Button
    private lateinit var recordingsBox: LinearLayout

    private val recordStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(ScreenRecordService.EXTRA_STATE)) {
                ScreenRecordService.STATE_SAVED -> status.text = "recording saved on this phone"
                ScreenRecordService.STATE_FAILED -> status.text = "screen recording failed or was empty"
            }
            renderRecordSection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val runBtn = Button(this).apply {
            text = "Run capture"
            setOnClickListener { runCapture() }
        }
        root.addView(runBtn)
        recordBtn = Button(this).apply {
            setOnClickListener { onRecordClicked() }
        }
        root.addView(recordBtn)
        recordingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recordingsBox)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        root.addView(progress)
        status = TextView(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(24, 8, 24, 8) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Diagnostics"
            setOnClickListener { showDiagnostics() }
        })
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 16)
        }
        // The setup content (account, pairing, retention notes) can
        // exceed the leftover screen on small phones; it must scroll or
        // the lower rows are unreachable. Shares the leftover space with
        // the queue list, which already scrolls.
        val setupScroll = ScrollView(this)
        setupScroll.addView(setupBox)
        root.addView(setupScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
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

    private fun onRecordClicked() {
        if (RecordingStore.activeName(this) != null) {
            startService(
                Intent(this, ScreenRecordService::class.java)
                    .setAction(ScreenRecordService.ACTION_STOP)
            )
            return
        }
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
        recordingsBox.removeAllViews()
        val recordings = RecordingStore.list(this)
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
                row.addView(Button(this).apply {
                    text = "Share"
                    setOnClickListener { shareRecording(file) }
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
            type = "video/mp4"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share recording"))
        DiagLog.log(this, "record", "shared")
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
