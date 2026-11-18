package com.vanipy.bidik

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * Records the device screen to a local MP4.
 *
 * Consent: the activity collects the per-session system consent with
 * MediaProjectionManager.createScreenCaptureIntent() and passes the
 * result here. On Android 14 (targetSdk 34) the order is required:
 * consent first, startForeground with the mediaProjection type second,
 * getMediaProjection last. The consent token backs exactly one virtual
 * display, so each recording is one consented session.
 *
 * Capture surface: a MediaRecorder (H.264 in MP4) fed by a VirtualDisplay
 * in AUTO_MIRROR mode, so whatever is on the screen - home screen,
 * widgets, any app - is recorded. No microphone: no audio source is set,
 * so RECORD_AUDIO is not requested and recordings are silent.
 *
 * Files stay on the phone (see RecordingStore). Stop paths: the
 * notification Stop action, the app's stop button, the projection ending
 * from the system side, or the MAX_DURATION_MS cap. A failed or empty
 * recording deletes its partial file.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.vanipy.bidik.action.RECORD_START"
        const val ACTION_STOP = "com.vanipy.bidik.action.RECORD_STOP"
        const val ACTION_STATUS = "com.vanipy.bidik.action.RECORD_STATUS"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_STATE = "state"
        const val EXTRA_FILE = "file"
        const val STATE_RECORDING = "recording"
        const val STATE_SAVED = "saved"
        const val STATE_FAILED = "failed"
        private const val CHANNEL_ID = "screen-recording"
        private const val NOTIFICATION_ID = 7

        @Volatile
        internal var active = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var started = false
    private var cleanedUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // A stop request with nothing running is not a failure.
                if (active) cleanup(save = true, failed = false) else stopSelf()
            }
            ACTION_START -> {
                if (active) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                if (resultCode != Activity.RESULT_OK || data == null) {
                    DiagLog.log(this, "record", "failed", detail = "category=operation_failed")
                    broadcastStatus(STATE_FAILED, null)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRecording(resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (!RecordingStore.hasFreeSpace(this)) {
            DiagLog.log(this, "record", "failed", detail = "category=io_error")
            broadcastStatus(STATE_FAILED, null)
            stopSelf()
            return
        }
        startForegroundWithNotification()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        projection = mp
        // Required from Android 14: without a registered callback the
        // projection call sequence throws. Also our signal when the
        // system ends the session (revoked consent, screen off).
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { cleanup(save = true, failed = false) }
            }
        }, handler)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val file = RecordingStore.newRecordingFile(this)
        val rec = newRecorder(width, height, file)
        // Track both before any failing call so cleanup releases the
        // recorder and deletes the partial file on every failure path.
        recorder = rec
        outFile = file
        try {
            rec.prepare()
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        val vd = try {
            mp.createVirtualDisplay(
                "bidik-screen", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, handler
            )
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        virtualDisplay = vd
        try {
            rec.start()
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        started = true
        active = true
        RecordingStore.setActive(this, file.name)
        handler.postDelayed({ cleanup(save = true, failed = false) },
            RecordingStore.MAX_DURATION_MS)
        DiagLog.log(this, "record", "start")
        broadcastStatus(STATE_RECORDING, null)
    }

    private fun newRecorder(width: Int, height: Int, file: File): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(RecordingStore.videoBitRate(width, height))
        rec.setOutputFile(file.absolutePath)
        return rec
    }

    @Synchronized
    private fun cleanup(save: Boolean, failed: Boolean, error: Throwable? = null) {
        if (cleanedUp) return
        cleanedUp = true
        handler.removeCallbacksAndMessages(null)
        var usable = save && started
        val rec = recorder
        recorder = null
        if (rec != null) {
            if (started) {
                // stop() throws when no frame arrived; the partial file is
                // then unplayable and must not be kept.
                try {
                    rec.stop()
                } catch (e: Exception) {
                    usable = false
                }
            } else {
                // Never started: the file was created by setOutputFile but
                // holds no playable stream.
                usable = false
            }
            try {
                rec.reset()
                rec.release()
            } catch (_: Exception) {
            }
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        started = false
        active = false
        RecordingStore.clearActive(this)
        val file = outFile
        outFile = null
        if (file != null && usable && !failed && file.exists() && file.length() > 0) {
            DiagLog.log(this, "record", "saved")
            broadcastStatus(STATE_SAVED, file.name)
        } else {
            file?.delete()
            val category = if (error != null) DiagLog.errorCategory(error) else "operation_failed"
            DiagLog.log(this, "record", "failed", detail = "category=$category")
            broadcastStatus(STATE_FAILED, null)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW
            )
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("bidik is recording the screen")
            .setContentText("Local only. Stop from here or from the app.")
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun broadcastStatus(state: String, fileName: String?) {
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
        if (fileName != null) intent.putExtra(EXTRA_FILE, fileName)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        // If the system kills the service, end the session cleanly rather
        // than leaving a projection and partial file behind.
        cleanup(save = true, failed = false)
        super.onDestroy()
    }
}
