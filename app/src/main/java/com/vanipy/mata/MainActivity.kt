package com.vanipy.mata

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Whole screen is a concise queue list with no extra headings or filler.
 * One button runs the pass; status line at the bottom.
 *
 * Transport pairing (trusted contact address, account setup) is an explicit
 * owner action from the overflow area - never automatic on first launch,
 * and disabled entirely in builds where TRANSPORT_ENABLED is false.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var listView: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

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
        setContentView(root)
        refreshList()
    }

    private fun currentTransport(): QueueTransport {
        // Default: local transport (no account, no network beyond page loads).
        // The DeltaChat-backed transport activates only after explicit owner
        // setup and only in builds that enable it; see bot/BotService.
        return LocalQueueTransport(filesDir)
    }

    private fun refreshList() {
        scope.launch {
            val queue = try { currentTransport().fetchQueue() } catch (e: Exception) {
                status.text = "queue read failed: ${e.message}"
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
            refreshList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
