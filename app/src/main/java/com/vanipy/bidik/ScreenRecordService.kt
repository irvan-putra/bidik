package com.vanipy.bidik

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
 * Capture surface: a VirtualDisplay in AUTO_MIRROR mode renders whatever
 * is on the screen - home screen, widgets, any app - into a
 * CaptureEngine, which feeds a MediaCodec encoder at a fixed cadence
 * with wall-clock timestamps (see its KDoc). Fixed-cadence feeding keeps
 * the video timeline equal to real elapsed time even while the screen is
 * still, which direct MediaRecorder feeding does not.
 *
 * Optional extras, each off until the user turns it on (RecordOptions):
 * app sound records internal audio via AudioPlaybackCapture and/or the
 * microphone, mixed into one AAC track (both need the RECORD_AUDIO
 * runtime grant; the mic source is opened only for the mic option);
 * the motion option records an accelerometer CSV next to the MP4 and
 * draws a live motion dot into the video; the shake option moves the
 * recorded picture itself with the phone (shift/roll/zoom-pulse with
 * caps), riding the same sensor stream. When a wanted sound source
 * cannot start - permission declined, device too old, capture refused -
 * the recording continues video-only and says so in the notification
 * and status broadcast; it never fails silently.
 *
 * Files: the encoder writes a partial file in the app-private cache dir;
 * only after a clean stop is it moved into the recordings dir. The
 * in-progress file therefore never appears in the app's recordings list
 * and can never be shared unfinished. Files stay on the phone (see
 * RecordingStore). Stop paths: the notification Stop action, the app's
 * stop button, the projection ending from the system side, a chosen
 * auto-stop duration, or the MAX_DURATION_MS cap. A failed or empty
 * recording deletes its partial file.
 *
 * Recording options (RecordOptions): a start delay runs between consent
 * and the first encoded frame - the projection and virtual display are
 * set up immediately (the consent token backs exactly one virtual
 * display), only the encoder feeding waits - so the user can switch to
 * the screen to share first; the countdown shows in the notification
 * and stopping during it cancels without saving. An auto-stop duration
 * saves the recording with no interaction, so neither end of the clip
 * needs the app's UI on screen. The system-bars option crops those
 * bands in the encoder (see CaptureEngine), it never hides anything on
 * the phone.
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
        const val EXTRA_REMAINING_SEC = "remainingSec"
        const val EXTRA_SOUND_MISSING = "soundMissing"
        const val STATE_STARTING = "starting"
        const val STATE_RECORDING = "recording"
        const val STATE_SAVED = "saved"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELED = "canceled"
        private const val CHANNEL_ID = "screen-recording"
        private const val NOTIFICATION_ID = 7

        @Volatile
        internal var active = false
            private set

        // Test-only handle: instrumentation runs in this process and
        // drives the live overlay through the same onSample the sensor
        // feeds. Not an exported command or intent; the injection
        // itself lives entirely in the test source.
        @Volatile
        internal var activeMotionTrace: MotionTrace? = null

        /** Test-only handle for injecting synthetic shake samples. */
        internal var activeShakeModel: ShakeModel? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var engine: CaptureEngine? = null
    private var outFile: File? = null
    private var partialFile: File? = null
    private var started = false
    private var cleanedUp = false
    private var autoStopSec = 0
    private var delaySec = 0
    private var audioWanted = false
    private var motionLog: MotionLog? = null
    private var motionPartial: File? = null
    private var motionTarget: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // A stop request with nothing running is not a failure.
                // During the start-delay countdown a stop cancels the
                // session instead of saving an empty clip.
                if (active) cleanup(save = started, failed = false, canceled = !started)
                else stopSelf()
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
        delaySec = RecordOptions.delaySeconds(this)
        autoStopSec = RecordOptions.autoStopSeconds(this)
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
        // The encoder writes here; only a cleanly stopped recording is
        // moved to the recordings dir, so the app's list and share
        // actions only ever see finished files.
        val partial = File(cacheDir, file.name + ".part")
        partial.delete()
        val bands = if (RecordOptions.barsHidden(this)) {
            SystemBars.bands(this)
        } else {
            SystemBars.Bands(0, 0)
        }
        val eng = try {
            CaptureEngine(width, height, partial, bands.topPx, bands.bottomPx)
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        // Track all three before any failing call so cleanup releases
        // the engine and deletes the partial file on every failure path.
        engine = eng
        outFile = file
        partialFile = partial
        try {
            eng.prepare()
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        val vd = try {
            mp.createVirtualDisplay(
                "bidik-screen", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                eng.displaySurface, null, handler
            )
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        virtualDisplay = vd

        // Optional audio: app sound and/or mic, mixed to one AAC track.
        // Attached before feeding starts so the muxer waits for the
        // audio track; any failure here means video-only, said visibly.
        val soundOn = RecordOptions.soundEnabled(this)
        val micOn = RecordOptions.micEnabled(this)
        audioWanted = soundOn || micOn
        if (audioWanted && ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val recorder = AppAudioRecorder(
                    projection = if (soundOn) mp else null,
                    micEnabled = micOn,
                    sink = eng.muxSink,
                    videoStartNanos = { eng.firstFrameNanos }
                )
                recorder.prepare()
                eng.audioRecorder = recorder
            } catch (e: Exception) {
                DiagLog.log(this, "record", "audio_unavailable",
                    detail = "category=" + DiagLog.errorCategory(e))
            }
        }

        // Optional motion capture: one accelerometer stream feeds the
        // sidecar CSV + live dot (motion option) and/or the picture
        // shake effect (shake option); the toggles are independent.
        val wantMotion = RecordOptions.motionEnabled(this)
        val wantShake = RecordOptions.shakeEnabled(this)
        if (wantMotion || wantShake) {
            val target = File(
                RecordingStore.dir(this),
                file.nameWithoutExtension + ".motion.csv"
            )
            val partial = File(cacheDir, target.name + ".part")
            val log = MotionLog(this, partial, writeCsv = wantMotion) { eng.firstFrameNanos }
            motionLog = log
            if (wantMotion) {
                motionTarget = target
                motionPartial = partial
                eng.motionTrace = log.trace
            }
            if (wantShake) {
                eng.shakeModel = log.shake
            }
            activeMotionTrace = log.trace
            activeShakeModel = log.shake
        }

        // The session is live from here: the stop control appears and a
        // second start is refused. The encoder feed itself may still wait
        // out the start delay.
        active = true
        RecordingStore.setActive(this, file.name)
        if (delaySec > 0) {
            runCountdown(eng, delaySec)
        } else {
            beginFeeding(eng)
        }
    }

    /**
     * Counts down the start delay, one notification update per second,
     * then starts the encoder feed. The projection and virtual display
     * already exist; nothing is encoded until the countdown ends.
     */
    private fun runCountdown(eng: CaptureEngine, secondsLeft: Int) {
        if (cleanedUp) return
        if (secondsLeft <= 0) {
            beginFeeding(eng)
            return
        }
        updateNotification("starting in ${secondsLeft}s - switch to the screen to record", "Cancel")
        broadcastStatus(STATE_STARTING, null, secondsLeft)
        handler.postDelayed({ runCountdown(eng, secondsLeft - 1) }, 1_000L)
    }

    private fun beginFeeding(eng: CaptureEngine) {
        if (cleanedUp) return
        try {
            eng.startFeeding()
        } catch (e: Exception) {
            cleanup(save = false, failed = true, error = e)
            return
        }
        started = true
        val audioMissing = audioWanted && (eng.audioRecorder == null || eng.audioFailed)
        if (audioMissing) {
            DiagLog.log(this, "record", "audio_unavailable", detail = "category=not_granted")
        }
        val motion = motionLog
        if (motion != null && !motion.start()) {
            motionLog = null
            DiagLog.log(this, "record", "motion_unavailable")
        }
        val note = when {
            audioMissing ->
                "Recording without the sound you chose - local only. Stop here or in the app."
            audioWanted ->
                "Recording screen + sound - local only. Stop here or in the app."
            else -> "Local only. Stop from here or from the app."
        }
        updateNotification(note, "Stop")
        val capMs = if (autoStopSec > 0) {
            autoStopSec * 1_000L
        } else {
            RecordingStore.MAX_DURATION_MS
        }
        handler.postDelayed({ cleanup(save = true, failed = false) }, capMs)
        val bars = if (RecordOptions.barsHidden(this)) "hidden" else "shown"
        val sound = when {
            audioMissing -> "unavailable"
            audioWanted -> "on"
            else -> "off"
        }
        DiagLog.log(this, "record", "start",
            detail = "delay=$delaySec autostop=$autoStopSec bars=$bars " +
                "sound=$sound motion=${RecordOptions.motionEnabled(this)} " +
                "shake=${RecordOptions.shakeEnabled(this)}")
        broadcastStatus(STATE_RECORDING, null, soundMissing = audioMissing)
    }

    @Synchronized
    private fun cleanup(
        save: Boolean,
        failed: Boolean,
        error: Throwable? = null,
        canceled: Boolean = false
    ) {
        if (cleanedUp) return
        cleanedUp = true
        handler.removeCallbacksAndMessages(null)
        var usable = save && started
        val eng = engine
        engine = null
        if (eng != null) {
            // Blocks briefly on the capture thread (final frame, drain,
            // muxer stop); false means no playable stream was produced.
            usable = eng.stop(save = usable) && usable
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
        // The motion sidecar stops with the session and only a saved
        // recording keeps it, moved next to its MP4.
        val motion = motionLog
        motionLog = null
        activeMotionTrace = null
        activeShakeModel = null
        val mPartial = motionPartial
        val mTarget = motionTarget
        motionPartial = null
        motionTarget = null
        if (motion != null) {
            val motionSaved = motion.stop(save = usable && !failed) &&
                mPartial != null && mTarget != null && moveTo(mPartial, mTarget)
            if (motionSaved) {
                DiagLog.log(this, "record", "motion_saved")
            } else {
                try {
                    mPartial?.delete()
                } catch (_: Exception) {
                }
            }
        }
        audioWanted = false
        started = false
        active = false
        RecordingStore.clearActive(this)
        val file = outFile
        val partial = partialFile
        outFile = null
        partialFile = null
        val savedOk = file != null && partial != null && usable && !failed &&
            partial.exists() && partial.length() > 0 && moveTo(partial, file)
        if (savedOk && file != null) {
            DiagLog.log(this, "record", "saved")
            broadcastStatus(STATE_SAVED, file.name)
        } else {
            try {
                partial?.delete()
            } catch (_: Exception) {
            }
            if (canceled) {
                DiagLog.log(this, "record", "canceled")
                broadcastStatus(STATE_CANCELED, null)
            } else {
                val category =
                    if (error != null) DiagLog.errorCategory(error) else "operation_failed"
                DiagLog.log(this, "record", "failed", detail = "category=$category")
                broadcastStatus(STATE_FAILED, null)
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Same-filesystem rename with a copy fallback, verified by size. */
    private fun moveTo(partial: File, target: File): Boolean {
        if (partial.renameTo(target)) return target.length() > 0
        return try {
            partial.copyTo(target, overwrite = true)
            partial.delete()
            target.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification(text: String, actionLabel: String): Notification {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("bidik is recording the screen")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, actionLabel, stop).build())
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW
            )
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            buildNotification("Local only. Stop from here or from the app.", "Stop"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    /** Swaps the foreground notification text/action (countdown vs recording). */
    private fun updateNotification(text: String, actionLabel: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, actionLabel))
    }

    private fun broadcastStatus(
        state: String,
        fileName: String?,
        remainingSec: Int = -1,
        soundMissing: Boolean = false
    ) {
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
        if (fileName != null) intent.putExtra(EXTRA_FILE, fileName)
        if (remainingSec >= 0) intent.putExtra(EXTRA_REMAINING_SEC, remainingSec)
        if (soundMissing) intent.putExtra(EXTRA_SOUND_MISSING, true)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        // If the system kills the service, end the session cleanly rather
        // than leaving a projection and partial file behind.
        cleanup(save = true, failed = false)
        super.onDestroy()
    }
}
