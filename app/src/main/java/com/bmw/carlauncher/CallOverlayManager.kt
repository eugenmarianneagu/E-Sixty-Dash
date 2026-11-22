package com.bmw.carlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.bmw.carlauncher.notification.CarNotificationListenerService

/**
 * Manager dedicat pentru fereastra de apel telefonic de tip System Overlay
 * (TYPE_ACCESSIBILITY_OVERLAY cu fallback pe TYPE_APPLICATION_OVERLAY).
 *
 * Garantează afișarea consecventă a interfeței BMW de apel deasupra oricărei aplicații
 * (inclusiv Google Phone / dialer nativ, Waze, Maps, Lockscreen) atât la apeluri primite
 * cât și la apeluri efectuate, fără condiții de cursă.
 */
object CallOverlayManager {

    private const val TAG = "CallOverlayManager"

    enum class CallUiState {
        IDLE,
        RINGING,
        OUTGOING,
        ACTIVE
    }

    data class CallNotificationInfo(
        val key: String,
        val title: String?,
        val text: String?,
        val photo: Bitmap?,
        val usesChronometer: Boolean,
        val whenTimeMs: Long,
        val isIncomingHint: Boolean,
        val answerIntent: PendingIntent?,
        val declineOrHangupIntent: PendingIntent?
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentState: CallUiState = CallUiState.IDLE

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var isUsingAccessibilityWindow: Boolean = false

    // UI References inside overlay
    private var tvStatusTitle: TextView? = null
    private var tvDurationTimer: TextView? = null
    private var photoFrame: FrameLayout? = null
    private var ivContactPhoto: ImageView? = null
    private var tvContactName: TextView? = null
    private var tvPhoneNumber: TextView? = null
    private var ringingActions: LinearLayout? = null
    private var btnDeny: LinearLayout? = null
    private var btnAnswer: LinearLayout? = null
    private var btnHangup: LinearLayout? = null

    // Tracked call metadata
    private var currentNumber: String? = null
    private var currentName: String? = null
    private var currentPhoto: Bitmap? = null
    private var resolvedContactForNumber: String? = null
    private var activeCallNotificationKey: String? = null
    private var answerPendingIntent: PendingIntent? = null
    private var declineOrHangupPendingIntent: PendingIntent? = null

    private var callStartedElapsedMs: Long = 0L
    private var callConnectedElapsedMs: Long = 0L
    private var lastDialerLaunchedFromLauncherMs: Long = 0L
    private var isManualTestOverlay: Boolean = false
    private var appContextRef: Context? = null

    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private val durationTimerRunnable = object : Runnable {
        override fun run() {
            if (currentState != CallUiState.ACTIVE || rootView == null) return
            val elapsedSec = ((SystemClock.elapsedRealtime() - callConnectedElapsedMs).coerceAtLeast(0L) / 1000L).toInt()
            val mins = elapsedSec / 60
            val secs = elapsedSec % 60
            tvDurationTimer?.text = String.format("%02d:%02d", mins, secs)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val ctx = appContextRef ?: return
            if (rootView == null || currentState == CallUiState.IDLE) return

            // Nu auto-închidem overlay-ul de test manual decât prin butoane
            if (isManualTestOverlay) return

            val elapsedSinceStart = SystemClock.elapsedRealtime() - callStartedElapsedMs
            // Perioadă de grație de 4 secunde la inițiere apel (TelecomManager trece în OFFHOOK cu mic delay)
            if (elapsedSinceStart >= 4000L) {
                val teleState = getTelephonyCallState(ctx)
                val hasInCall = BMWInCallService.currentCall != null
                if (teleState == TelephonyManager.CALL_STATE_IDLE && !hasInCall) {
                    Log.i(TAG, "Watchdog: TelephonyManager este IDLE fără apel activ -> închidere overlay.")
                    dismissInternal(ctx)
                    return
                }
            }
            mainHandler.postDelayed(this, 2000L)
        }
    }

    fun isShowing(): Boolean = rootView != null && currentState != CallUiState.IDLE

    fun getCurrentState(): CallUiState = currentState

    /**
     * Verifică dacă overlay-ul trebuie afișat pentru evenimentul curent de apel:
     * - Da dacă apelul a fost pornit explicit din E-Sixty-Dash (forceShow)
     * - Da dacă E-Sixty-Dash este în prim-plan (MainActivity.isInForeground)
     * - Da dacă telefonul este conectat la dispozitivul Bluetooth setat în aplicație
     * - Da dacă BMWInCallService gestionează activ apelul
     * - Nu dacă telefonul nu este conectat la mașină și E-Sixty-Dash nu este pe ecran
     */
    fun markDialerLaunchedFromLauncher() {
        lastDialerLaunchedFromLauncherMs = SystemClock.elapsedRealtime()
    }

    fun shouldShowOverlay(context: Context, forceShow: Boolean = false): Boolean {
        if (forceShow) return true
        if (isShowing()) return true
        if (MainActivity.isInForeground) return true
        if (BMWInCallService.currentCall != null) return true
        if (lastDialerLaunchedFromLauncherMs > 0L &&
            (SystemClock.elapsedRealtime() - lastDialerLaunchedFromLauncherMs) < 60_000L) {
            return true
        }
        return isCarDeviceConnected(context)
    }

    @SuppressLint("MissingPermission")
    fun isCarDeviceConnected(context: Context): Boolean {
        val prefs = context.getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val savedCarAddress = prefs.getString("car_bt_address", null)

        if (!savedCarAddress.isNullOrEmpty() && savedCarAddress.equals("NONE", ignoreCase = true)) {
            return false
        }

        // Dacă MainActivity are proxy-urile A2DP/HFP active, o folosim direct
        MainActivity.instance?.let { act ->
            try {
                if (act.isCarBluetoothConnected()) return true
            } catch (_: Exception) {}
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return MainActivity.isCarConnectedState
            }
        }

        try {
            if (!savedCarAddress.isNullOrEmpty()) {
                val gattConn = try {
                    btManager?.getConnectedDevices(BluetoothProfile.GATT)
                        ?.any { it.address.equals(savedCarAddress, ignoreCase = true) } == true
                } catch (_: Exception) { false }
                if (gattConn) {
                    MainActivity.isCarConnectedState = true
                    return true
                }

                var devIsConnected = false
                try {
                    val remoteDev = btAdapter.getRemoteDevice(savedCarAddress)
                    val method = remoteDev.javaClass.getMethod("isConnected")
                    devIsConnected = (method.invoke(remoteDev) as? Boolean) == true
                } catch (_: Exception) {}

                if (devIsConnected) {
                    MainActivity.isCarConnectedState = true
                    return true
                }
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "isCarDeviceConnected fallback: ${e.message}")
        }

        return MainActivity.isCarConnectedState
    }

    // =========================================================================
    // EVENIMENTE DE APEL (APELATE DIN RECEIVER, TELEPHONY CALLBACK, NOTIF, INCALL)
    // =========================================================================

    fun onIncomingCall(
        context: Context,
        number: String? = null,
        name: String? = null,
        photo: Bitmap? = null,
        forceShow: Boolean = false,
        isTest: Boolean = false
    ) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        mainHandler.post {
            if (!shouldShowOverlay(appCtx, forceShow)) {
                Log.d(TAG, "onIncomingCall ignorat (nu e conectat la BT mașină și launcherul nu e în prim-plan).")
                return@post
            }

            if (currentState == CallUiState.IDLE) {
                callStartedElapsedMs = SystemClock.elapsedRealtime()
                MainActivity.instance?.captureRadioStateBeforeCall()
            }
            isManualTestOverlay = isTest

            mergeCallerInfo(appCtx, number, name, photo)
            pullFromNotificationIfMissing(appCtx)

            currentState = CallUiState.RINGING
            ensureOverlayAttached(appCtx)
            renderCurrentState(focusPrimary = true)
            startWatchdog()

            // Re-interogare scurtă pentru numărul/poza din notificarea Google Phone dacă inițial a venit null
            if (currentName.isNullOrBlank() || currentNumber.isNullOrBlank()) {
                mainHandler.postDelayed({
                    if (currentState == CallUiState.RINGING) {
                        pullFromNotificationIfMissing(appCtx)
                        renderCurrentState(focusPrimary = false)
                    }
                }, 400L)
            }
        }
    }

    fun onOutgoingCall(
        context: Context,
        number: String? = null,
        name: String? = null,
        photo: Bitmap? = null,
        forceShow: Boolean = false
    ) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        mainHandler.post {
            if (!shouldShowOverlay(appCtx, forceShow)) {
                Log.d(TAG, "onOutgoingCall ignorat (nu e conectat la BT mașină și launcherul nu e în prim-plan).")
                return@post
            }

            if (currentState == CallUiState.IDLE) {
                callStartedElapsedMs = SystemClock.elapsedRealtime()
                MainActivity.instance?.captureRadioStateBeforeCall()
            }
            isManualTestOverlay = false

            mergeCallerInfo(appCtx, number, name, photo)
            pullFromNotificationIfMissing(appCtx)

            if (currentState != CallUiState.ACTIVE) {
                currentState = CallUiState.OUTGOING
            }
            ensureOverlayAttached(appCtx)
            renderCurrentState(focusPrimary = true)
            startWatchdog()
        }
    }

    fun onOffhook(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        mainHandler.post {
            if (!shouldShowOverlay(appCtx, forceShow = false)) {
                Log.d(TAG, "onOffhook ignorat (nu e conectat la BT mașină și launcherul nu e în prim-plan).")
                return@post
            }

            isManualTestOverlay = false

            when (currentState) {
                CallUiState.RINGING -> {
                    // Apel primit -> tocmai a fost preluat!
                    transitionToActive(appCtx, SystemClock.elapsedRealtime())
                }
                CallUiState.OUTGOING -> {
                    // Apel inițiat de noi -> rămânem pe "APELARE ÎN CURS..." până când notificarea/InCallService confirmă preluarea,
                    // dar ne asigurăm că overlay-ul este atașat și vizibil deasupra dialerului nativ!
                    ensureOverlayAttached(appCtx)
                    renderCurrentState(focusPrimary = false)
                    startWatchdog()
                }
                CallUiState.ACTIVE -> {
                    ensureOverlayAttached(appCtx)
                    renderCurrentState(focusPrimary = false)
                }
                CallUiState.IDLE -> {
                    // Apel de ieșire pornit din afara E-Sixty-Dash (ex: Google Phone, Google Assistant, redial de pe volan)
                    callStartedElapsedMs = SystemClock.elapsedRealtime()
                    MainActivity.instance?.captureRadioStateBeforeCall()
                    pullFromNotificationIfMissing(appCtx)

                    val notifInfo = CarNotificationListenerService.getActiveCallNotificationInfo(appCtx)
                    if (notifInfo != null && notifInfo.usesChronometer) {
                        val elapsedOffset = (System.currentTimeMillis() - notifInfo.whenTimeMs).coerceAtLeast(0L)
                        transitionToActive(appCtx, SystemClock.elapsedRealtime() - elapsedOffset)
                    } else {
                        currentState = CallUiState.OUTGOING
                        ensureOverlayAttached(appCtx)
                        renderCurrentState(focusPrimary = true)
                        startWatchdog()
                    }

                    // Re-verificare la 450ms și 1100ms pentru a prelua numele/numărul din notificarea Google Phone
                    for (delay in longArrayOf(450L, 1100L)) {
                        mainHandler.postDelayed({
                            if (currentState == CallUiState.OUTGOING || currentState == CallUiState.ACTIVE) {
                                pullFromNotificationIfMissing(appCtx)
                                renderCurrentState(focusPrimary = false)
                            }
                        }, delay)
                    }
                }
            }
        }
    }

    fun onCallActive(context: Context, connectedElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        mainHandler.post {
            if (!shouldShowOverlay(appCtx, forceShow = false)) return@post
            isManualTestOverlay = false
            transitionToActive(appCtx, connectedElapsedMs)
        }
    }

    fun onCallNotificationUpdated(context: Context, info: CallNotificationInfo) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        mainHandler.post {
            activeCallNotificationKey = info.key
            if (info.answerIntent != null) answerPendingIntent = info.answerIntent
            if (info.declineOrHangupIntent != null) declineOrHangupPendingIntent = info.declineOrHangupIntent

            if (!shouldShowOverlay(appCtx, forceShow = false)) return@post

            val teleState = getTelephonyCallState(appCtx)
            // Dacă suntem încă IDLE dar există notificare activă de apel și telefonul nu e IDLE (sau are intent de răspuns)
            if (currentState == CallUiState.IDLE) {
                if (teleState == TelephonyManager.CALL_STATE_RINGING || info.isIncomingHint) {
                    callStartedElapsedMs = SystemClock.elapsedRealtime()
                    MainActivity.instance?.captureRadioStateBeforeCall()
                    applyNotificationMetadata(appCtx, info)
                    currentState = CallUiState.RINGING
                    ensureOverlayAttached(appCtx)
                    renderCurrentState(focusPrimary = true)
                    startWatchdog()
                    return@post
                } else if (teleState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    callStartedElapsedMs = SystemClock.elapsedRealtime()
                    MainActivity.instance?.captureRadioStateBeforeCall()
                    applyNotificationMetadata(appCtx, info)
                    if (info.usesChronometer && info.whenTimeMs > 0L) {
                        val elapsedOffset = (System.currentTimeMillis() - info.whenTimeMs).coerceAtLeast(0L)
                        transitionToActive(appCtx, SystemClock.elapsedRealtime() - elapsedOffset)
                    } else {
                        currentState = CallUiState.OUTGOING
                        ensureOverlayAttached(appCtx)
                        renderCurrentState(focusPrimary = true)
                        startWatchdog()
                    }
                    return@post
                }
                return@post
            }

            // Îmbogățim metadatele apelului deja afișat (nume, număr, poză)
            applyNotificationMetadata(appCtx, info)

            // Detectare moment exact când interlocutorul răspunde la un apel efectuat!
            if (currentState == CallUiState.OUTGOING && info.usesChronometer && info.whenTimeMs > 0L) {
                val elapsedOffset = (System.currentTimeMillis() - info.whenTimeMs).coerceAtLeast(0L)
                transitionToActive(appCtx, SystemClock.elapsedRealtime() - elapsedOffset)
            } else {
                ensureOverlayAttached(appCtx)
                renderCurrentState(focusPrimary = false)
            }
        }
    }

    fun onCallNotificationRemoved(context: Context, key: String) {
        val appCtx = context.applicationContext
        mainHandler.post {
            if (key == activeCallNotificationKey) {
                activeCallNotificationKey = null
                answerPendingIntent = null
                declineOrHangupPendingIntent = null
                if (!isManualTestOverlay && currentState != CallUiState.IDLE) {
                    val teleState = getTelephonyCallState(appCtx)
                    if (teleState == TelephonyManager.CALL_STATE_IDLE && BMWInCallService.currentCall == null) {
                        dismissInternal(appCtx)
                    }
                }
            }
        }
    }

    fun onCallEnded(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            dismissInternal(appCtx)
            MainActivity.instance?.onTelephonyCallIdle()
        }
    }

    fun dismiss(context: Context? = appContextRef) {
        mainHandler.post {
            dismissInternal(context)
        }
    }

    // =========================================================================
    // CONTROL TASTE HARDWARE (BMW iDrive: TAB, SHIFT+TAB, ENTER)
    // =========================================================================

    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (!isShowing()) return false
        val code = event.keyCode
        val isNavigationOrConfirm = code == KeyEvent.KEYCODE_TAB ||
                code == KeyEvent.KEYCODE_DPAD_LEFT ||
                code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                code == KeyEvent.KEYCODE_DPAD_CENTER

        if (!isNavigationOrConfirm) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            val isShift = event.isShiftPressed
            mainHandler.post {
                processHardwareKeyOnMainThread(code, isShift)
            }
        }
        return true
    }

    private fun processHardwareKeyOnMainThread(keyCode: Int, isShift: Boolean = false) {
        if (!isShowing()) return
        when (keyCode) {
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (currentState == CallUiState.RINGING) {
                    val answer = btnAnswer
                    val deny = btnDeny
                    if (answer != null && deny != null) {
                        if (answer.isFocused) {
                            deny.requestFocus()
                        } else {
                            answer.requestFocus()
                        }
                    }
                } else {
                    // În timpul apelului activ / în curs, rotirea encoderului reglează volumul apelului
                    val ctx = appContextRef
                    if (ctx != null) {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        val lower = isShift || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        val direction = if (lower) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                        try {
                            am?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, AudioManager.FLAG_SHOW_UI)
                        } catch (_: Exception) {
                            try {
                                am?.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                            } catch (_: Exception) {}
                        }
                    }
                    btnHangup?.requestFocus()
                }
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (currentState == CallUiState.RINGING) {
                    if (btnDeny?.isFocused == true) {
                        btnDeny?.performClick()
                    } else {
                        btnAnswer?.performClick()
                    }
                } else {
                    btnHangup?.performClick()
                }
            }
        }
    }

    // =========================================================================
    // INTERN: ATAȘARE FEREASTRĂ OVERLAY, RENDERING & ACȚIUNI TELECOM
    // =========================================================================

    private fun transitionToActive(appCtx: Context, connectedAtElapsedMs: Long) {
        val wasAlreadyActive = (currentState == CallUiState.ACTIVE)
        currentState = CallUiState.ACTIVE
        if (!wasAlreadyActive || callConnectedElapsedMs == 0L) {
            callConnectedElapsedMs = connectedAtElapsedMs
        }
        ensureOverlayAttached(appCtx)
        renderCurrentState(focusPrimary = !wasAlreadyActive)
        mainHandler.removeCallbacks(durationTimerRunnable)
        durationTimerRunnable.run()
        startWatchdog()
    }

    private fun ensureOverlayAttached(appCtx: Context) {
        val accService = BMWAccessibilityService.instance

        // Dacă overlay-ul există deja pe TYPE_APPLICATION_OVERLAY dar între timp AccessibilityService e disponibil, păstrăm fereastra actuală fără flicker
        if (rootView != null) {
            return
        }

        val tryAccessibility = (accService != null)
        if (tryAccessibility) {
            if (attachOverlayWindow(accService!!, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, appCtx)) {
                isUsingAccessibilityWindow = true
                Log.i(TAG, "Call overlay atașat cu succes folosind TYPE_ACCESSIBILITY_OVERLAY.")
                return
            }
        }

        // Fallback pe TYPE_APPLICATION_OVERLAY dacă AccessibilityService nu e disponibil
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appCtx)
        if (canDraw) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            if (attachOverlayWindow(appCtx, type, appCtx)) {
                isUsingAccessibilityWindow = false
                Log.i(TAG, "Call overlay atașat cu succes folosind TYPE_APPLICATION_OVERLAY (fallback).")
                return
            }
        } else {
            Log.e(TAG, "Nu se poate afișa call overlay: nici AccessibilityService, nici SYSTEM_ALERT_WINDOW nu sunt disponibile!")
        }
    }

    private fun attachOverlayWindow(windowContext: Context, windowType: Int, appCtx: Context): Boolean {
        return try {
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
            val themedCtx = ContextThemeWrapper(windowContext, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)

            val container = object : FrameLayout(themedCtx) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (handleHardwareKey(event)) return true
                    return super.dispatchKeyEvent(event)
                }
            }

            val inflated = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_call_panel, container, false)
            container.addView(
                inflated,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            // Lățime card optimizată pentru mod landscape (forțat prin screenOrientation)
            val card = inflated.findViewById<ConstraintLayout>(R.id.call_overlay_card)
            val dm = appCtx.resources.displayMetrics
            val landscapeWidthPx = maxOf(dm.widthPixels, dm.heightPixels)
            val maxCardWidthPx = (520 * dm.density).toInt()
            val horizontalMarginPx = (32 * dm.density).toInt()
            val targetWidth = minOf(maxCardWidthPx, (landscapeWidthPx - horizontalMarginPx).coerceAtLeast(280))
            card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
                width = targetWidth
                gravity = Gravity.CENTER
            }

            // Referințe UI
            tvStatusTitle = inflated.findViewById(R.id.overlay_call_status_title)
            tvDurationTimer = inflated.findViewById(R.id.overlay_call_duration_timer)
            photoFrame = inflated.findViewById(R.id.overlay_call_photo_frame)
            ivContactPhoto = inflated.findViewById(R.id.overlay_call_contact_photo)
            tvContactName = inflated.findViewById(R.id.overlay_call_contact_name)
            tvPhoneNumber = inflated.findViewById(R.id.overlay_call_phone_number)
            ringingActions = inflated.findViewById(R.id.overlay_call_ringing_actions)
            btnDeny = inflated.findViewById(R.id.overlay_btn_call_deny)
            btnAnswer = inflated.findViewById(R.id.overlay_btn_call_answer)
            btnHangup = inflated.findViewById(R.id.overlay_btn_call_hangup)

            photoFrame?.apply {
                outlineProvider = circleOutlineProvider
                clipToOutline = true
            }
            ivContactPhoto?.apply {
                outlineProvider = circleOutlineProvider
                clipToOutline = true
            }
            tvContactName?.isSelected = true

            // Click instant dintr-o singură atingere chiar cu focusableInTouchMode=true
            enableInstantClick(btnAnswer) {
                Log.i(TAG, "Apăsat RĂSPUNDE în CallOverlay")
                answerCall(appCtx)
                transitionToActive(appCtx, SystemClock.elapsedRealtime())
            }
            enableInstantClick(btnDeny) {
                Log.i(TAG, "Apăsat RESPINGE în CallOverlay")
                rejectOrEndCall(appCtx, isRinging = true)
                dismissInternal(appCtx)
            }
            enableInstantClick(btnHangup) {
                Log.i(TAG, "Apăsat ÎNCHEIE APELUL în CallOverlay")
                rejectOrEndCall(appCtx, isRinging = false)
                dismissInternal(appCtx)
            }

            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            wm.addView(container, params)
            windowManager = wm
            rootView = container
            true
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la atașarea ferestrei overlay (type=$windowType): ${e.message}", e)
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableInstantClick(view: View?, onClick: () -> Unit) {
        if (view == null) return
        view.setOnClickListener { onClick() }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    val inside = event.x >= 0f && event.x <= v.width && event.y >= 0f && event.y <= v.height
                    if (inside) {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun renderCurrentState(focusPrimary: Boolean) {
        val ctx = appContextRef ?: return
        if (rootView == null) return

        val displayName = when {
            !currentName.isNullOrBlank() -> currentName!!
            !currentNumber.isNullOrBlank() -> currentNumber!!
            else -> "Număr Necunoscut"
        }
        val displayPhone = when {
            !currentNumber.isNullOrBlank() && currentNumber != displayName -> currentNumber!!
            else -> ""
        }

        tvContactName?.text = displayName
        tvPhoneNumber?.text = displayPhone
        tvPhoneNumber?.visibility = if (displayPhone.isNotEmpty()) View.VISIBLE else View.GONE

        val photo = currentPhoto
        if (photo != null) {
            ivContactPhoto?.setPadding(0, 0, 0, 0)
            try {
                ivContactPhoto?.setImageBitmap(getCircularBitmap(photo))
            } catch (_: Exception) {
                ivContactPhoto?.setImageBitmap(photo)
            }
            ivContactPhoto?.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val pad = (14 * ctx.resources.displayMetrics.density).toInt()
            ivContactPhoto?.setPadding(pad, pad, pad, pad)
            ivContactPhoto?.setImageResource(R.drawable.ic_call_avatar)
            ivContactPhoto?.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        when (currentState) {
            CallUiState.RINGING -> {
                tvStatusTitle?.text = "⚡ APEL DE INTRARE"
                tvDurationTimer?.visibility = View.GONE
                ringingActions?.visibility = View.VISIBLE
                btnHangup?.visibility = View.GONE
                if (focusPrimary) {
                    btnAnswer?.post { btnAnswer?.requestFocus() }
                }
            }
            CallUiState.OUTGOING -> {
                tvStatusTitle?.text = "● APELARE ÎN CURS..."
                tvDurationTimer?.visibility = View.GONE
                ringingActions?.visibility = View.GONE
                btnHangup?.visibility = View.VISIBLE
                if (focusPrimary) {
                    btnHangup?.post { btnHangup?.requestFocus() }
                }
            }
            CallUiState.ACTIVE -> {
                tvStatusTitle?.text = "● APEL ÎN DESFĂȘURARE"
                tvDurationTimer?.visibility = View.VISIBLE
                ringingActions?.visibility = View.GONE
                btnHangup?.visibility = View.VISIBLE
                if (focusPrimary) {
                    btnHangup?.post { btnHangup?.requestFocus() }
                }
            }
            CallUiState.IDLE -> {
                dismissInternal(ctx)
            }
        }
    }

    private fun dismissInternal(context: Context?) {
        mainHandler.removeCallbacks(durationTimerRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)

        val wasShowing = (rootView != null || currentState != CallUiState.IDLE)

        try {
            rootView?.let { v ->
                windowManager?.removeViewImmediate(v)
            }
        } catch (e: Exception) {
            try {
                rootView?.let { v -> windowManager?.removeView(v) }
            } catch (_: Exception) {}
        }

        rootView = null
        windowManager = null
        tvStatusTitle = null
        tvDurationTimer = null
        photoFrame = null
        ivContactPhoto = null
        tvContactName = null
        tvPhoneNumber = null
        ringingActions = null
        btnDeny = null
        btnAnswer = null
        btnHangup = null

        currentState = CallUiState.IDLE
        currentNumber = null
        currentName = null
        currentPhoto = null
        resolvedContactForNumber = null
        activeCallNotificationKey = null
        answerPendingIntent = null
        declineOrHangupPendingIntent = null
        callStartedElapsedMs = 0L
        callConnectedElapsedMs = 0L
        lastDialerLaunchedFromLauncherMs = 0L
        isManualTestOverlay = false
        isUsingAccessibilityWindow = false

        if (wasShowing) {
            Log.i(TAG, "Call overlay închis.")
            MainActivity.instance?.onOverlayCallEnded()
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        if (!isManualTestOverlay) {
            mainHandler.postDelayed(watchdogRunnable, 2000L)
        }
    }

    // =========================================================================
    // METADATE CONTACT & NOTIFICARE
    // =========================================================================

    private fun mergeCallerInfo(context: Context, number: String?, name: String?, photo: Bitmap?) {
        val cleanNum = number?.trim()?.takeIf { it.isNotEmpty() }
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Număr Necunoscut", ignoreCase = true) }

        if (cleanNum != null) {
            currentNumber = cleanNum
        }
        if (cleanName != null) {
            currentName = cleanName
        }
        if (photo != null) {
            currentPhoto = photo
        }

        val numToLookup = currentNumber
        if (!numToLookup.isNullOrBlank() && numToLookup != resolvedContactForNumber && (currentName.isNullOrBlank() || currentPhoto == null)) {
            resolvedContactForNumber = numToLookup
            val info = lookupContact(context, numToLookup)
            if (currentName.isNullOrBlank() && !info.first.isNullOrBlank()) {
                currentName = info.first
            }
            if (currentPhoto == null && info.second != null) {
                currentPhoto = info.second
            }
        }
    }

    private fun pullFromNotificationIfMissing(context: Context) {
        val info = CarNotificationListenerService.getActiveCallNotificationInfo(context) ?: return
        activeCallNotificationKey = info.key
        if (info.answerIntent != null) answerPendingIntent = info.answerIntent
        if (info.declineOrHangupIntent != null) declineOrHangupPendingIntent = info.declineOrHangupIntent
        applyNotificationMetadata(context, info)
    }

    private fun applyNotificationMetadata(context: Context, info: CallNotificationInfo) {
        val title = info.title?.trim()?.takeIf { it.isNotEmpty() }
        // Uneori titlul notificării e chiar numărul de telefon, alteori numele contactului
        if (title != null) {
            val looksLikePhone = title.replace("[\\s\\-+().]".toRegex(), "").all { it.isDigit() } && title.length >= 3
            if (looksLikePhone) {
                mergeCallerInfo(context, number = title, name = null, photo = info.photo)
            } else {
                mergeCallerInfo(context, number = null, name = title, photo = info.photo)
            }
        } else if (info.photo != null && currentPhoto == null) {
            currentPhoto = info.photo
        }
    }

    @SuppressLint("MissingPermission")
    private fun getTelephonyCallState(context: Context): Int {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
        } catch (_: Exception) {
            TelephonyManager.CALL_STATE_IDLE
        }
    }

    private fun answerCall(context: Context) {
        if (BMWInCallService.answerCurrentCall()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    tm?.acceptRingingCall()
                    Log.i(TAG, "Apel preluat prin TelecomManager.acceptRingingCall()")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.acceptRingingCall a eșuat: ${e.message}")
        }

        try {
            answerPendingIntent?.send()
            Log.i(TAG, "Apel preluat prin notification answerPendingIntent")
        } catch (e: Exception) {
            Log.w(TAG, "answerPendingIntent a eșuat: ${e.message}")
        }
    }

    private fun rejectOrEndCall(context: Context, isRinging: Boolean) {
        if (isRinging) {
            if (BMWInCallService.rejectCurrentCall()) return
        } else {
            if (BMWInCallService.hangupCurrentCall()) return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    val ended = tm?.endCall() == true
                    Log.i(TAG, "TelecomManager.endCall() a returnat: $ended")
                    if (ended) return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.endCall a eșuat: ${e.message}")
        }

        try {
            declineOrHangupPendingIntent?.send()
            Log.i(TAG, "Apel închis prin notification declineOrHangupPendingIntent")
        } catch (e: Exception) {
            Log.w(TAG, "declineOrHangupPendingIntent a eșuat: ${e.message}")
        }
    }

    private fun lookupContact(context: Context, phoneNumber: String): Pair<String?, Bitmap?> {
        var name: String? = null
        var photo: Bitmap? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
                val projection = arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (nameIdx != -1) name = cursor.getString(nameIdx)
                        val photoIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                        if (photoIdx != -1) {
                            val photoUriStr = cursor.getString(photoIdx)
                            if (!photoUriStr.isNullOrEmpty()) {
                                context.contentResolver.openInputStream(Uri.parse(photoUriStr))?.use { stream ->
                                    photo = BitmapFactory.decodeStream(stream)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eroare lookupContact: ${e.message}")
        }
        return Pair(name, photo)
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        val srcLeft = (bitmap.width - size) / 2
        val srcTop = (bitmap.height - size) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + size, srcTop + size)

        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, srcRect, rect, paint)
        return output
    }
}
