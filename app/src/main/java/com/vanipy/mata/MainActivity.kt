package com.vanipy.mata

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.vanipy.mata.bot.DcQueueTransport
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
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var listView: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var setupBox: LinearLayout

    private val qrScanner =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents
            if (contents != null) completePairing(contents)
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
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        root.addView(progress)
        status = TextView(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(24, 8, 24, 8) }
        root.addView(status)
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 16)
        }
        root.addView(setupBox)
        setContentView(root)

        renderSetup()
        if (SetupController.isPaired(this)) {
            // Target flow: open -> sync -> capture -> send, no taps.
            refreshList { runCapture() }
        } else {
            refreshList()
        }
    }

    private fun currentTransport(): QueueTransport {
        val peer = SetupController.peerAddr(this)
        return if (SetupController.isPaired(this) && peer != null) {
            DcQueueTransport(this, peer)
        } else {
            LocalQueueTransport(filesDir)
        }
    }

    private fun renderSetup() {
        setupBox.removeAllViews()
        if (SetupController.isPaired(this)) {
            setupBox.addView(TextView(this).apply {
                text = "paired: ${SetupController.peerAddr(this@MainActivity)}"
            })
            return
        }
        val state = TextView(this)
        setupBox.addView(state)
        scope.launch {
            val addr = SetupController.selfAddress(this@MainActivity)
            state.text = if (addr != null) "messaging address: $addr" else "messaging not set up"
        }
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
                    } catch (e: Exception) {
                        status.text = "account setup failed: ${e.message}"
                        btn.isEnabled = true
                    }
                }
            }
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
    }

    private fun completePairing(qrText: String) {
        status.text = "pairing..."
        scope.launch {
            try {
                val addr = SetupController.completePairing(this@MainActivity, qrText)
                status.text = "paired: $addr"
                renderSetup()
                refreshList { runCapture() }
            } catch (e: Exception) {
                status.text = "pairing failed: ${e.message}"
            }
        }
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
        progress.visibility = View.VISIBLE
        status.text = "running..."
        scope.launch {
            val outDir = File(filesDir, "captures")
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
