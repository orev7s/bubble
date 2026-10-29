package com.accio.whisperbubble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Holds the floating bubble and runs record -> upload -> inject.
 *
 * Feedback policy: the bubble colour is the primary signal, the ongoing
 * notification carries the detail, and a toast appears only when something
 * needs the user's attention.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_FOCUS = "com.accio.whisperbubble.FOCUS"
        const val ACTION_INSERT = "com.accio.whisperbubble.INSERT"
        const val ACTION_INSERT_RESULT = "com.accio.whisperbubble.INSERT_RESULT"
        const val EXTRA_FOCUSED = "focused"
        const val EXTRA_TEXT = "text"
        const val EXTRA_OK = "ok"

        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 42
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        @Volatile
        var alive = false
            private set
    }

    private lateinit var wm: WindowManager
    private var bubble: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    private val recorder = AudioRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var fieldFocused = false

    @Volatile
    private var busy = false

    private var lastText: String? = null
    private var pendingInsert: Job? = null
    private var resetJob: Job? = null

    private val density by lazy { resources.displayMetrics.density }
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private var downRawX = 0f
    private var downRawY = 0f
    private var anchorX = 0
    private var anchorY = 0
    private var dragging = false
    private var multiTouch = false
    private var pressed = false
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var pressJob: Job? = null

    private val focusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            fieldFocused = intent?.getBooleanExtra(EXTRA_FOCUSED, false) ?: false
            updateBubbleVisibility()
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pendingInsert?.cancel()
            pendingInsert = null
            val ok = intent?.getBooleanExtra(EXTRA_OK, false) ?: false
            if (ok) {
                settle("Inserted")
            } else {
                fallbackToClipboard(lastText.orEmpty())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Ready"), foregroundType())
        ContextCompat.registerReceiver(
            this, focusReceiver, IntentFilter(ACTION_FOCUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, resultReceiver, IntentFilter(ACTION_INSERT_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        addBubble()
        alive = true
        GroqApi.warmup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val p = params ?: return
        val b = bubble ?: return
        clamp(p)
        runCatching { wm.updateViewLayout(b, p) }
    }

    override fun onDestroy() {
        alive = false
        runCatching { unregisterReceiver(focusReceiver) }
        runCatching { unregisterReceiver(resultReceiver) }
        recorder.cancel()
        scope.cancel()
        bubble?.let { b -> runCatching { wm.removeView(b) } }
        bubble = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- bubble

    private fun addBubble() {
        val size = (64 * density).toInt()
        val view = BubbleView(this)
        view.setOnTouchListener { _, event -> onBubbleTouch(event) }

        val (sw, sh) = screenSize()
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (Prefs.bubbleX >= 0) Prefs.bubbleX else sw - size - (12 * density).toInt()
            y = if (Prefs.bubbleY >= 0) Prefs.bubbleY else sh / 2
        }

        bubble = view
        params = p
        runCatching { wm.addView(view, p) }
        updateBubbleVisibility()
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = resources.displayMetrics
            m.widthPixels to m.heightPixels
        }

    private fun onBubbleTouch(event: MotionEvent): Boolean {
        val p = params ?: return false
        val b = bubble ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                downRawX = event.rawX
                downRawY = event.rawY
                anchorX = p.x
                anchorY = p.y
                dragging = false
                multiTouch = false
                pressed = true
                // Dwell before listening, so brushing past the bubble does not
                // start a recording.
                pressJob?.cancel()
                pressJob = scope.launch {
                    delay(Prefs.pressDelayMs.toLong())
                    if (pressed && !dragging && Prefs.trigger == Trigger.HOLD) beginRecord()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointer) multiTouch = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) return true
                if (event.findPointerIndex(activePointer) < 0) return true

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    pressJob?.cancel()
                    if (recorder.isRecording) cancelRecord()
                }
                if (dragging) {
                    p.x = anchorX + dx.toInt()
                    p.y = anchorY + dy.toInt()
                    clamp(p)
                    runCatching { wm.updateViewLayout(b, p) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                pressed = false
                pressJob?.cancel()
                if (dragging || multiTouch) {
                    clamp(p)
                    Prefs.bubbleX = p.x
                    Prefs.bubbleY = p.y
                    runCatching { wm.updateViewLayout(b, p) }
                } else {
                    when (Prefs.trigger) {
                        Trigger.HOLD -> if (recorder.isRecording) endRecord()
                        Trigger.TAP -> if (recorder.isRecording) endRecord() else beginRecord()
                    }
                }
                dragging = false
                multiTouch = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                pressJob?.cancel()
                if (recorder.isRecording) cancelRecord()
                dragging = false
                multiTouch = false
                return true
            }
        }
        return false
    }

    /** Keeps the bubble inside the screen, including after a rotation. */
    private fun clamp(p: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        val size = (64 * density).toInt()
        p.x = p.x.coerceIn(0, (sw - size).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (sh - size).coerceAtLeast(0))
    }

    private fun updateBubbleVisibility() {
        val b = bubble ?: return
        val p = params ?: return
        val show = !Prefs.onlyWhenFocused || fieldFocused || recorder.isRecording || busy
        p.alpha = if (show) 1f else 0f
        p.flags = if (show) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(b, p) }
    }

    private fun setBubbleState(state: Int) {
        bubble?.state = state
    }

    // ------------------------------------------------------------- recording

    private fun beginRecord() {
        if (busy || recorder.isRecording) return
        resetJob?.cancel()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail("Microphone permission missing")
            return
        }
        if (!recorder.start()) {
            fail("Microphone unavailable")
            return
        }
        setBubbleState(BubbleView.RECORDING)
        setStatus("Listening")
        vibrate(16)
        updateBubbleVisibility()
    }

    private fun cancelRecord() {
        if (!recorder.isRecording) return
        recorder.cancel()
        setBubbleState(BubbleView.IDLE)
        setStatus("Ready")
        updateBubbleVisibility()
    }

    private fun endRecord() {
        if (!recorder.isRecording) return
        val wav = recorder.stop()
        if (wav == null) {
            neutral("Too short")
            updateBubbleVisibility()
            return
        }

        busy = true
        setBubbleState(BubbleView.BUSY)
        setStatus("Transcribing")
        vibrate(10)
        updateBubbleVisibility()

        scope.launch {
            val outcome = GroqApi.transcribe(wav)
            busy = false
            val text = outcome.text
            if (text.isNullOrBlank()) {
                fail(outcome.error ?: "Transcription failed")
            } else {
                lastText = text
                requestInsert(text)
            }
            updateBubbleVisibility()
        }
    }

    // --------------------------------------------------------------- insert

    private fun requestInsert(text: String) {
        pendingInsert?.cancel()
        // If the accessibility service is off or never answers, keep the text safe.
        pendingInsert = scope.launch {
            delay(1500)
            fallbackToClipboard(text)
        }
        sendBroadcast(
            Intent(ACTION_INSERT).setPackage(packageName).putExtra(EXTRA_TEXT, text)
        )
    }

    private fun fallbackToClipboard(text: String) {
        pendingInsert = null
        if (text.isBlank()) {
            settle("Ready")
            return
        }
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = runCatching {
            clip.setPrimaryClip(ClipData.newPlainText("Bubble", text))
        }.isSuccess

        settle(if (copied) "Copied to clipboard" else "No text field")
        toast(if (copied) "Copied to clipboard" else "No text field found")
    }

    /** No colour signal, just a line in the notification that fades back to Ready. */
    private fun neutral(status: String) {
        setBubbleState(BubbleView.IDLE)
        setStatus(status)
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(1200)
            if (!busy && !recorder.isRecording) setStatus("Ready")
        }
    }

    private fun settle(status: String) {
        setBubbleState(BubbleView.DONE)
        setStatus(status)
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(700)
            if (!busy && !recorder.isRecording) {
                setBubbleState(BubbleView.IDLE)
                if (status != "Ready") setStatus("Ready")
            }
        }
    }

    private fun fail(reason: String) {
        setBubbleState(BubbleView.ERROR)
        setStatus(reason)
        toast(reason)
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(1400)
            if (!busy && !recorder.isRecording) {
                setBubbleState(BubbleView.IDLE)
                setStatus("Ready")
            }
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun vibrate(ms: Long) {
        vibrateImpl(ms)
    }

    @Suppress("DEPRECATION")
    private fun vibrateImpl(ms: Long) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(ms, 90))
            } else {
                v?.vibrate(ms)
            }
        }
    }

    // ----------------------------------------------------------- notification

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0

    private fun setStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, notification(text)) }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "Bubble", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Bubble")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(tap)
            .build()
    }
}
