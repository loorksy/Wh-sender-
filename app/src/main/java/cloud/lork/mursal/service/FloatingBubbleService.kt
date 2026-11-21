package cloud.lork.mursal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import cloud.lork.mursal.R
import cloud.lork.mursal.ui.HomeActivity
import cloud.lork.mursal.util.BulkSession
import cloud.lork.mursal.util.BulkState
import cloud.lork.mursal.util.BulkUiState
import cloud.lork.mursal.util.DualAppSupport
import cloud.lork.mursal.util.MessageParser
import cloud.lork.mursal.util.WhatsAppApps
import cloud.lork.mursal.util.WhatsAppWindows
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var closeZoneView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private var panelInput: EditText? = null
    private var panelCount: TextView? = null
    private var panelMpm: TextView? = null
    private var panelSeek: SeekBar? = null
    private var panelFormView: View? = null
    private var panelSuccessView: View? = null
    private var panelSuccessBadge: View? = null
    private var panelSuccessCount: TextView? = null
    private var panelSuccessRate: TextView? = null
    private var panelSend: TextView? = null
    private var panelSegPara: View? = null
    private var panelSegLine: View? = null
    private var rateChips: List<Pair<View, Int>> = emptyList()

    // Message split mode: false = paragraphs (blank-line separated, line fallback), true = one per line.
    private var lineMode = false

    // Sending rate (messages/minute); kept across panel openings.
    private var currentMpm = DEFAULT_MPM

    private fun themedInflater(): LayoutInflater {
        val themed = ContextThemeWrapper(this, R.style.Theme_Mursal)
        return LayoutInflater.from(themed)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showBubble()
        collectJob = scope.launch {
            BulkSession.ui.collectLatest { state ->
                renderPanelState(state)
                if (state.state == BulkState.DONE) {
                    showPanelSafe()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                shutdownCompletely()
            }
            ACTION_SHOW_PANEL -> showPanelSafe()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        hideCloseZone()
        removeBubble()
        hidePanel()
        super.onDestroy()
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return
        try {
            val view = themedInflater().inflate(R.layout.overlay_bubble, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 220
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            var downX = 0f
            var downY = 0f
            var paramX = 0
            var paramY = 0
            var moved = false
            val screenHeight = resources.displayMetrics.heightPixels
            val closeThresholdY = (screenHeight * 0.78f).toInt()

            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        paramX = params.x
                        paramY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                        params.x = paramX + dx
                        params.y = paramY + dy
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (_: Exception) {
                        }
                        if (moved) {
                            showCloseZone()
                            highlightCloseZone(event.rawY >= closeThresholdY)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dropClose = moved && event.rawY >= closeThresholdY
                        hideCloseZone()
                        if (dropClose) {
                            mainHandler.post {
                                Toast.makeText(this, R.string.closed_by_drop, Toast.LENGTH_SHORT).show()
                                shutdownCompletely()
                            }
                        } else if (!moved) {
                            mainHandler.post { togglePanel() }
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (e: Exception) {
            Log.e(TAG, "showBubble failed", e)
            Toast.makeText(this, R.string.overlay_show_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showCloseZone() {
        if (closeZoneView != null) return
        try {
            val view = themedInflater().inflate(R.layout.overlay_close_zone, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
            }
            windowManager.addView(view, params)
            closeZoneView = view
        } catch (e: Exception) {
            Log.e(TAG, "showCloseZone failed", e)
        }
    }

    private fun highlightCloseZone(active: Boolean) {
        val icon = closeZoneView?.findViewById<TextView>(R.id.closeZoneIcon) ?: return
        icon.scaleX = if (active) 1.15f else 1f
        icon.scaleY = if (active) 1.15f else 1f
        icon.alpha = if (active) 1f else 0.85f
    }

    private fun hideCloseZone() {
        closeZoneView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        closeZoneView = null
    }

    private fun shutdownCompletely() {
        BulkSession.stop()
        BulkAccessibilityService.instance?.stopLoop()
        hidePanel()
        hideCloseZone()
        stopSelf()
    }

    private fun togglePanel() {
        if (panelView != null) hidePanel() else showPanelSafe()
    }

    private fun showPanelSafe() {
        mainHandler.post {
            try {
                showPanel()
            } catch (e: Exception) {
                Log.e(TAG, "showPanel failed", e)
                Toast.makeText(this, R.string.panel_show_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPanel() {
        if (panelView != null) {
            panelView?.visibility = View.VISIBLE
            return
        }

        val view = themedInflater().inflate(R.layout.overlay_panel, null)
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtLeast(280)
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        panelInput = view.findViewById(R.id.inputMessages)
        panelCount = view.findViewById(R.id.textCount)
        panelMpm = view.findViewById(R.id.textMpmValue)
        panelSeek = view.findViewById(R.id.seekMpm)
        panelFormView = view.findViewById(R.id.formView)
        panelSuccessView = view.findViewById(R.id.successView)
        panelSuccessBadge = view.findViewById(R.id.successBadge)
        panelSuccessCount = view.findViewById(R.id.textSuccessCount)
        panelSuccessRate = view.findViewById(R.id.textSuccessRate)
        panelSend = view.findViewById(R.id.btnSend)

        view.findViewById<View>(R.id.btnClosePanel).setOnClickListener { hidePanel() }

        panelSeek?.max = SEEK_STEPS
        panelSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentMpm = snapToPreset(progressToMpm(progress))
                renderMpm()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // Rate presets: segmented control; the matching preset stays highlighted.
        rateChips = listOf(
            view.findViewById<View>(R.id.chip10) to 10,
            view.findViewById<View>(R.id.chip60) to 60,
            view.findViewById<View>(R.id.chip300) to 300,
            view.findViewById<View>(R.id.chip1000) to 1000,
        )
        rateChips.forEach { (chip, value) -> chip.setOnClickListener { setMpm(value) } }
        // Keep the last chosen rate when the panel reopens (like the split mode).
        setMpm(currentMpm)

        // Paste clipboard text into the input (uses the focused EditText's paste action).
        view.findViewById<View>(R.id.btnPaste)?.setOnClickListener {
            panelInput?.let { ed ->
                ed.requestFocus()
                runCatching { ed.onTextContextMenuItem(android.R.id.paste) }
                updateMessageCountPreview()
            }
        }

        // Message split mode: segmented control (paragraphs | one per line).
        panelSegPara = view.findViewById(R.id.segPara)
        panelSegLine = view.findViewById(R.id.segLine)
        panelSegPara?.setOnClickListener { setLineMode(false) }
        panelSegLine?.setOnClickListener { setLineMode(true) }
        renderSplitMode()

        panelSend?.setOnClickListener { startSending() }

        // Success screen actions.
        view.findViewById<View>(R.id.btnSendAgain).setOnClickListener {
            BulkSession.clearSuccess()
            renderPanelState(BulkSession.ui.value)
            panelInput?.requestFocus()
        }
        view.findViewById<View>(R.id.btnSuccessClose).setOnClickListener {
            BulkSession.clearSuccess()
            hidePanel()
        }

        panelInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateMessageCountPreview()
            }
        })
        updateMessageCountPreview()

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                // keep panel open; user closes with X
                true
            } else {
                false
            }
        }

        // Render before attaching so a finished run opens straight on the success screen
        // (no focused field -> no keyboard popping up over it).
        renderPanelState(BulkSession.ui.value)

        windowManager.addView(view, params)
        panelView = view

        bubbleView?.bringToFront()

        if (panelFormView?.visibility == View.VISIBLE) {
            panelInput?.requestFocus()
        }
    }

    private fun parseMessages(raw: String): List<String> {
        if (lineMode) {
            return MessageParser.parse(raw, MessageParser.Mode.LINES)
        }
        var messages = MessageParser.parse(raw, MessageParser.Mode.PARAGRAPH)
        if (messages.isEmpty()) {
            messages = MessageParser.parse(raw, MessageParser.Mode.LINES)
        }
        return messages
    }

    private fun updateMessageCountPreview() {
        showCount(parseMessages(panelInput?.text?.toString().orEmpty()).size)
    }

    private fun showCount(count: Int) {
        panelCount?.visibility = View.VISIBLE
        panelCount?.text = count.toString()
        // Badge turns accent once there is something to send.
        panelCount?.isActivated = count > 0
    }

    private fun setLineMode(enabled: Boolean) {
        if (lineMode == enabled) return
        lineMode = enabled
        renderSplitMode()
        updateMessageCountPreview()
    }

    private fun renderSplitMode() {
        panelSegPara?.isSelected = !lineMode
        panelSegLine?.isSelected = lineMode
        panelInput?.setHint(if (lineMode) R.string.paste_hint_lines else R.string.paste_hint)
    }

    private fun updateRateChips(value: Int) {
        rateChips.forEach { (chip, preset) -> chip.isSelected = preset == value }
    }

    private fun hidePanel() {
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
        panelInput = null
        panelCount = null
        panelMpm = null
        panelSeek = null
        panelFormView = null
        panelSuccessView = null
        panelSuccessBadge = null
        panelSuccessCount = null
        panelSuccessRate = null
        panelSend = null
        panelSegPara = null
        panelSegLine = null
        rateChips = emptyList()
    }

    private fun removeBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    private fun mpmValue(): Int = currentMpm.coerceIn(1, BulkSession.MAX_MPM)

    private fun setMpm(value: Int) {
        currentMpm = value.coerceIn(1, BulkSession.MAX_MPM)
        panelSeek?.progress = mpmToProgress(currentMpm)
        renderMpm()
    }

    private fun renderMpm() {
        panelMpm?.text = currentMpm.toString()
        updateRateChips(currentMpm)
    }

    // The rate slider is logarithmic over 1…MAX_MPM: everyday rates (10, 60, 100, 300)
    // spread across the track instead of being squeezed into its first few percent.
    private fun progressToMpm(progress: Int): Int {
        val fraction = progress.coerceIn(0, SEEK_STEPS).toDouble() / SEEK_STEPS
        return 10.0.pow(fraction * log10(BulkSession.MAX_MPM.toDouble()))
            .roundToInt()
            .coerceIn(1, BulkSession.MAX_MPM)
    }

    // While dragging, values within 2% of a preset land exactly on it (so its segment lights up).
    private fun snapToPreset(mpm: Int): Int =
        rateChips.map { it.second }.firstOrNull { preset -> abs(mpm - preset) <= preset * 0.02 } ?: mpm

    private fun mpmToProgress(mpm: Int): Int {
        val fraction = log10(mpm.coerceIn(1, BulkSession.MAX_MPM).toDouble()) /
            log10(BulkSession.MAX_MPM.toDouble())
        return (fraction * SEEK_STEPS).roundToInt().coerceIn(0, SEEK_STEPS)
    }

    private fun startSending() {
        if (!BulkAccessibilityService.isEnabled() && !isAccessibilitySettingEnabled()) {
            Toast.makeText(this, R.string.accessibility_required, Toast.LENGTH_LONG).show()
            return
        }
        val messages = parseMessages(panelInput?.text?.toString().orEmpty())
        updateMessageCountPreview()
        if (messages.isEmpty()) {
            Toast.makeText(this, R.string.must_paste, Toast.LENGTH_SHORT).show()
            return
        }
        beginSend(messages)
    }

    private fun beginSend(messages: List<String>) {
        BulkSession.clearSuccess()
        showCount(messages.size)
        BulkSession.prepare(messages, mpmValue())
        BulkAccessibilityService.instance?.startLoop()
        hidePanel()
        val openedChooser = openWhatsAppIfNeeded()
        if (!openedChooser) {
            Toast.makeText(this, R.string.must_open_chat, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * @return true when a WhatsApp picker/app was shown.
     * If the user is already inside WhatsApp or dual WhatsApp, do nothing.
     */
    private fun openWhatsAppIfNeeded(): Boolean {
        val service = BulkAccessibilityService.instance
        if (service != null && WhatsAppWindows.isAnyWhatsAppVisible(service)) {
            return false
        }

        val apps = WhatsAppApps.installed(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.whatsapp_not_found, Toast.LENGTH_LONG).show()
            return false
        }

        val primary = apps.first().launchIntent
        val chooser = Intent.createChooser(primary, getString(R.string.choose_whatsapp)).apply {
            if (apps.size > 1) {
                putExtra(
                    Intent.EXTRA_INITIAL_INTENTS,
                    apps.drop(1).map { it.launchIntent }.toTypedArray(),
                )
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(chooser)
        if (DualAppSupport.shouldAvoidAutoLaunch(this)) {
            Toast.makeText(this, DualAppSupport.guidanceMessage(this), Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun renderPanelState(ui: BulkUiState) {
        val state = ui.state
        // While a run is in progress the send button turns grey and doubles as the live status.
        panelSend?.text = when (state) {
            BulkState.WAITING_CHAT -> getString(R.string.status_waiting_chat)
            BulkState.SENDING -> getString(R.string.status_sending) +
                if (ui.total > 0) " (${ui.sent}/${ui.total})" else ""
            else -> getString(R.string.send)
        }
        panelSend?.isEnabled = state != BulkState.SENDING && state != BulkState.WAITING_CHAT

        // A finished run swaps the whole form for the success screen.
        val finished = state == BulkState.DONE || !ui.successMessage.isNullOrBlank()
        if (finished) {
            panelSuccessCount?.text = ui.total.toString()
            panelSuccessRate?.text = ui.messagesPerMinute.toString()
        }
        setSuccessMode(finished)
    }

    private fun setSuccessMode(success: Boolean) {
        val successView = panelSuccessView ?: return
        val wasShowing = successView.visibility == View.VISIBLE
        panelFormView?.visibility = if (success) View.GONE else View.VISIBLE
        successView.visibility = if (success) View.VISIBLE else View.GONE
        if (success && !wasShowing) {
            successView.alpha = 0f
            successView.animate().alpha(1f).setDuration(180L).start()
            panelSuccessBadge?.apply {
                scaleX = 0.6f
                scaleY = 0.6f
                animate().scaleX(1f).scaleY(1f).setDuration(380L)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
        }
    }

    private fun isAccessibilitySettingEnabled(): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = "$packageName/${BulkAccessibilityService::class.java.canonicalName}"
            enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showPanel = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_SHOW_PANEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.open_form), showPanel)
            .addAction(0, getString(R.string.close_bubble), stop)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        private const val TAG = "FloatingBubble"
        private const val CHANNEL_ID = "bulk_bubble"
        private const val NOTIFICATION_ID = 4202
        const val ACTION_STOP_SERVICE = "cloud.lork.mursal.STOP_BUBBLE"
        const val ACTION_SHOW_PANEL = "cloud.lork.mursal.SHOW_PANEL"
        private const val DEFAULT_MPM = 100
        private const val SEEK_STEPS = 1000

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
