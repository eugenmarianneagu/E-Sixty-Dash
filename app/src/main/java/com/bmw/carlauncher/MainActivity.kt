package com.bmw.carlauncher

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.KeyguardManager
import android.os.PowerManager
import android.provider.CallLog
import java.util.Calendar
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.telephony.TelephonyManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.app.role.RoleManager
import android.telecom.TelecomManager
import android.util.Log
import android.content.res.Configuration
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.view.WindowManager
import android.widget.AdapterView
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.speech.tts.TextToSpeech
import android.widget.ImageButton
import android.os.Environment
import com.bmw.carlauncher.notification.CarNotification
import com.bmw.carlauncher.notification.CarNotificationHub
import com.bmw.carlauncher.notification.CarNotificationListenerService
import com.bmw.carlauncher.notification.VoiceMessagePlayer

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var isCarConnectedState: Boolean = false
        @Volatile
        var lastDisconnectTimestamp: Long = 0L
        @Volatile
        var isInForeground: Boolean = false
        @Volatile
        var currentTaskId: Int = -1

        var instance: MainActivity? = null

        fun onPhoneRinging(context: Context, number: String?) {
            instance?.runOnUiThread {
                instance?.showIncomingCallUI(number, null)
            }
        }

        fun onPhoneOffhook(context: Context) {
            instance?.runOnUiThread {
                instance?.showActiveCallUI()
            }
        }

        fun onPhoneIdle(context: Context) {
            instance?.runOnUiThread {
                instance?.dismissCallUI()
            }
        }

        fun closeLauncher() {
            instance?.let { act ->
                act.runOnUiThread {
                    Log.i("MainActivity", "closeLauncher: Oprire muzică și închidere automată la deconectarea ESP32...")
                    // 1. Oprește radioul intern
                    try {
                        act.controller?.pause()
                        act.controller?.stop()
                    } catch (_: Exception) {}
                    // 2. Oprește YouTube Web dacă este activ
                    try {
                        if (act.isYouTubeWebMode && act.isYouTubeWebPlaying) {
                            act.youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
                            act.isYouTubeWebPlaying = false
                        }
                    } catch (_: Exception) {}
                    // 3. Pune pe pauză orice aplicație media externă conectată (Spotify, YouTube Music etc.)
                    try {
                        ExternalMediaManager.getActiveMusicController(act)?.transportControls?.pause()
                    } catch (_: Exception) {}
                    // 4. Oprește redarea mesajelor vocale și TTS
                    try {
                        VoiceMessagePlayer.stop()
                    } catch (_: Exception) {}
                    try {
                        act.textToSpeech?.stop()
                    } catch (_: Exception) {}

                    // 5. Închide launcherul complet
                    act.finishAndRemoveTask()
                }
            }
        }
    }

    data class FavoriteContact(
        val name: String,
        val number: String,
        val photo: Bitmap?
    )

    enum class CenterMode {
        BMW, MEDIA, RADIO, MAP, ENERGY, NOTIFICATION
    }

    enum class ActiveMediaSource {
        LOCAL_PLAYER,
        EXTERNAL_APP,
        YOUTUBE_WEB,
        RADIO
    }

    private var currentMode = CenterMode.BMW
    private var activeMediaSource = ActiveMediaSource.EXTERNAL_APP
    private var isNightMode = true
    enum class BrightnessMode {
        DAY,
        NIGHT,
        AUTO
    }

    private var currentBrightnessMode: BrightnessMode = BrightnessMode.AUTO

    data class RecentCall(
        val name: String,
        val number: String,
        val type: Int,
        val date: Long,
        val photo: Bitmap?
    )

    private var isFirstRadioConnect = true

    // YouTube Live Video in Center Circle
    private var isYouTubeWebMode = false
    private var isYouTubeWebPlaying = false
    private var isYouTubeDockedInCircle = false
    private var urlAtFullscreenOpen = ""
    private var youTubeWebTitle = ""
    private var youTubeWebArtist = ""
    private var youtubeWebView: WebView? = null
    private lateinit var youtubeCircleContainer: FrameLayout
    private lateinit var youtubeCircleTouchOverlay: View
    private lateinit var youtubeFullscreenOverlay: LinearLayout
    private lateinit var youtubeFullscreenContainer: FrameLayout
    private lateinit var btnYtBack: TextView
    private lateinit var btnYtDockCircle: TextView
    private lateinit var centerMediaScrim: View
    private lateinit var centerMediaInfoContainer: LinearLayout

    // Media Controller & Stations
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var stations = mutableListOf<RadioStation>()

    // Top Bar elements
    private lateinit var textClock: TextView

    // Bottom Bar elements
    private lateinit var btnPrevTrack: ImageView
    private lateinit var btnNextTrack: ImageView
    private lateinit var btnPlayPause: ImageView
    private var isCurrentlyPlayingState = false
    private lateinit var bottomTrackTitle: TextView
    private lateinit var bottomTrackArtist: TextView
    private lateinit var bottomBatteryIcon: TextView
    private var bottomGpsCount: TextView? = null
    private lateinit var btnVolumeDown: ImageView
    private lateinit var btnVolumeUp: ImageView
    private lateinit var btnNightMode: ImageView
    private lateinit var btnQuickSettings: ImageView

    // Agama Wings - Left column
    private lateinit var btnLeft1: FrameLayout
    private lateinit var btnLeft2: FrameLayout
    private lateinit var btnLeft3: FrameLayout

    // Agama Wings - Right column
    private lateinit var btnRight1: FrameLayout
    private lateinit var btnRight2: FrameLayout
    private lateinit var btnRight3: FrameLayout

    // Center Circle & Views
    private lateinit var centerCircleHalo: BmwCircleHaloView
    private lateinit var centerCircle: FrameLayout
    private lateinit var viewCenterBmw: FrameLayout
    private lateinit var viewCenterMedia: FrameLayout
    private lateinit var centerMediaThumbnail: ImageView
    private lateinit var centerMediaSource: TextView
    private lateinit var centerMediaTitle: TextView
    private lateinit var centerMediaArtist: TextView
    private lateinit var centerMediaPlayerBadge: View
    private lateinit var ivCenterMediaPlayerIcon: ImageView
    private lateinit var tvCenterMediaPlayerName: TextView
    private var lastKnownLocation: Location? = null

    private lateinit var viewCenterRadio: LinearLayout
    private lateinit var centerRadioArt: ImageView
    private lateinit var centerRadioTitle: TextView
    private lateinit var centerRadioSub: TextView
    private lateinit var centerRadioStatus: TextView

    private lateinit var viewCenterWaze: LinearLayout
    private lateinit var centerWazeIcon: TextView
    private lateinit var centerWazeDistance: TextView
    private lateinit var centerWazeRoad: TextView
    private lateinit var centerWazeEta: TextView

    private lateinit var viewCenterSpeedo: LinearLayout
    private lateinit var centerSpeedValue: TextView
    private lateinit var centerSpeedUnit: TextView
    private lateinit var centerSpeedSub: TextView

    // Solar & Energy Center View UI
    private lateinit var viewCenterEnergy: RelativeLayout
    private lateinit var tvEnergySolarPower: TextView
    private lateinit var tvEnergyHouseLoad: TextView
    private lateinit var tvEnergyBatterySoc: TextView
    private lateinit var tvEnergyBatteryStatus: TextView
    private lateinit var tvEnergyBatteryVoltage: TextView
    private lateinit var tvEnergyBatteryPower: TextView
    private val energyHandler = Handler(Looper.getMainLooper())
    private val energyPollRunnable = object : Runnable {
        override fun run() {
            if (isInForeground && currentMode == CenterMode.ENERGY) {
                fetchAndDisplayEnergyData()
                energyHandler.postDelayed(this, 5000L)
            }
        }
    }

    // Car Notification Center View UI
    private lateinit var viewCenterNotification: RelativeLayout
    private lateinit var notifHeaderRow: RelativeLayout
    private lateinit var notifCenterContent: LinearLayout
    private lateinit var notifBottomActions: LinearLayout
    private lateinit var layoutNotifEmptyState: LinearLayout
    private lateinit var btnNotifPrev: ImageButton
    private lateinit var btnNotifNext: ImageButton
    private lateinit var ivNotifAppIcon: ImageView
    private lateinit var tvNotifAppName: TextView
    private lateinit var tvNotifTime: TextView
    private lateinit var btnNotifClose: ImageButton
    private lateinit var tvNotifSender: TextView
    private lateinit var layoutNotifVoiceBadge: LinearLayout
    private lateinit var tvNotifVoiceLabel: TextView
    private lateinit var tvNotifText: TextView
    private lateinit var btnNotifAction: LinearLayout
    private lateinit var ivNotifActionIcon: ImageView
    private lateinit var tvNotifActionText: TextView
    private lateinit var btnNotifSpeak: LinearLayout
    private var ivNotifSpeakIcon: ImageView? = null
    private var tvNotifSpeakText: TextView? = null
    private var tvNotifCounter: TextView? = null
    private var activeNotification: CarNotification? = null
    private var preNotificationMode: CenterMode = CenterMode.BMW
    private val notifDismissHandler = Handler(Looper.getMainLooper())
    private val notifDismissRunnable = Runnable { dismissNotification() }
    private var textToSpeech: TextToSpeech? = null
    private var wasRadioPlayingBeforeVoice: Boolean = false
    private var wasYouTubePlayingBeforeVoice: Boolean = false
    private var wasExternalMediaPlayingBeforeVoice: Boolean = false
    private var wasLocalMusicPlayingBeforeVoice: Boolean = false

    // Favorites Overlay UI
    private lateinit var favoritesOverlayContainer: FrameLayout
    private lateinit var btnCloseFavorites: ImageView
    private lateinit var btnOpenDialer: TextView
    private lateinit var listFavoriteContacts: ListView
    private lateinit var tvEmptyFavorites: TextView

    // Radio Overlay UI
    private lateinit var radioOverlayContainer: FrameLayout
    private lateinit var btnCloseRadio: ImageView
    private lateinit var btnAddStation: TextView
    private lateinit var listRadioStations: ListView

    // Music Apps Overlay UI
    private lateinit var musicOverlayContainer: FrameLayout
    private lateinit var btnCloseMusic: ImageView
    private lateinit var listMusicApps: ListView

    // Media Player Overlay UI
    private lateinit var mediaPlayerOverlayContainer: FrameLayout
    private lateinit var btnMediaBack: ImageView
    private lateinit var mediaHeaderTitle: TextView
    private lateinit var btnAddPlaylist: TextView
    private lateinit var btnScanMusic: TextView
    private lateinit var btnMediaShuffle: FrameLayout
    private lateinit var iconMediaShuffle: ImageView
    private lateinit var btnMediaRepeat: FrameLayout
    private lateinit var iconMediaRepeat: ImageView
    private lateinit var btnCloseMediaPlayer: ImageView
    private lateinit var listMediaPlaylists: ListView
    private lateinit var listMediaTracks: ListView
    private lateinit var mediaEmptyText: TextView
    private var selectedPlaylist: LocalPlaylist? = null

    // All Apps Overlay UI
    private lateinit var appsOverlayContainer: FrameLayout
    private lateinit var btnCloseApps: ImageView
    private lateinit var gridAllApps: GridView

    // Settings Overlay UI
    private lateinit var settingsOverlayContainer: FrameLayout
    private lateinit var btnCloseSettings: ImageView
    private lateinit var seekDayBrightness: SeekBar
    private lateinit var seekNightBrightness: SeekBar
    private lateinit var tvDayBrightnessVal: TextView
    private lateinit var tvNightBrightnessVal: TextView
    private lateinit var btnOpenNotifSettings: LinearLayout
    private lateinit var tvNotifAccessStatus: TextView
    private lateinit var btnCarBtSelect: LinearLayout
    private lateinit var tvCarBtStatus: TextView
    private var tvNavSourceLabel: TextView? = null
    private var centerNavIconImg: ImageView? = null

    // Bluetooth Car Detection & Orientation
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null

    fun isPortraitOrientation(): Boolean = false

    private val requestBtConnectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("MainActivity", "Permisiune BLUETOOTH_CONNECT acordata de utilizator")
            initBluetoothProfiles()
            checkBluetoothConnectionAndUpdateOrientation()
        }
    }

    private fun handleCarDisconnected(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!isCarConnectedState && now - lastDisconnectTimestamp < 3000L) {
            return
        }
        Log.i("MainActivity", "handleCarDisconnected ($reason): oprire audio, blocare ecran și background")
        isCarConnectedState = false
        lastDisconnectTimestamp = now
        try {
            val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_disconnect_elapsed_time", now).apply()
        } catch (_: Exception) {}
        ExternalMediaManager.cancelAutoReturn()
        stopAllAudioPlayers()

        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            clearShowWhenLocked()
        } catch (_: Exception) {}

        // Elimina aplicatia din prim-plan si din Recents
        try {
            finishAndRemoveTask()
        } catch (_: Exception) {
            try { moveTaskToBack(true) } catch (_: Exception) {}
        }

        // Curata aplicatiile din Recents si blocheaza ecranul
        BMWAccessibilityService.clearRecentsAndLockScreen()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            Log.i("MainActivity", "Bluetooth broadcast event: $action")

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devAddress = device?.address ?: ""
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""

            val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            val savedCarAddress = prefs.getString("car_bt_address", null)
            val isCarDevice = if (!savedCarAddress.isNullOrEmpty()) {
                devAddress.equals(savedCarAddress, ignoreCase = true)
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            val now = android.os.SystemClock.elapsedRealtime()
            val prefsDisconnect = prefs.getLong("last_disconnect_elapsed_time", 0L)
            val effectiveDisconnect = maxOf(lastDisconnectTimestamp, prefsDisconnect)
            val isInDisconnectCooldown = (effectiveDisconnect > 0L && now - effectiveDisconnect < 15000L)

            when (action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (isCarDevice && devAddress.isNotEmpty()) {
                        handleCarDisconnected("ACL_DISCONNECTED: $devName ($devAddress)")
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (isCarDevice && state == BluetoothProfile.STATE_CONNECTED && !isInDisconnectCooldown) {
                        Log.i("MainActivity", "Car device A2DP connected: $devName")
                        lastDisconnectTimestamp = 0L
                        isCarConnectedState = true
                        startRadioPlayback()
                        BMWAccessibilityService.bringToForeground(this@MainActivity)
                    } else if (isCarDevice && state == BluetoothProfile.STATE_DISCONNECTED) {
                        handleCarDisconnected("A2DP_DISCONNECTED: $devName")
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (isCarDevice && state == BluetoothProfile.STATE_CONNECTED && !isInDisconnectCooldown) {
                        Log.i("MainActivity", "Car device Headset connected: $devName")
                        lastDisconnectTimestamp = 0L
                        isCarConnectedState = true
                        startRadioPlayback()
                        BMWAccessibilityService.bringToForeground(this@MainActivity)
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (isCarDevice && devAddress.isNotEmpty() && !isInDisconnectCooldown) {
                        Log.i("MainActivity", "Car device ACL connected: $devName ($devAddress)")
                        lastDisconnectTimestamp = 0L
                        isCarConnectedState = true
                        startRadioPlayback()
                        BMWAccessibilityService.bringToForeground(this@MainActivity)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        handleCarDisconnected("BT_ADAPTER_OFF")
                    }
                }
            }
        }
    }

    // Incoming / In-Call Overlay UI
    private lateinit var callOverlayContainer: FrameLayout
    private lateinit var callPhotoFrame: FrameLayout
    private lateinit var callStatusTitle: TextView
    private lateinit var callDurationTimer: TextView
    private lateinit var callContactPhoto: ImageView
    private lateinit var callContactName: TextView
    private lateinit var callPhoneNumber: TextView
    private lateinit var callRingingActions: LinearLayout
    private lateinit var btnCallDeny: LinearLayout
    private lateinit var btnCallAnswer: LinearLayout
    private lateinit var btnCallHangup: LinearLayout

    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val size = minOf(view.width, view.height)
            if (size > 0) {
                val left = (view.width - size) / 2
                val top = (view.height - size) / 2
                outline.setOval(left, top, left + size, top + size)
            } else {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    private var callDurationSeconds = 0
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            callDurationSeconds++
            val mins = callDurationSeconds / 60
            val secs = callDurationSeconds % 60
            callDurationTimer.text = String.format("%02d:%02d", mins, secs)
            callTimerHandler.postDelayed(this, 1000)
        }
    }

    // Clock updater
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    // Battery Monitor
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                val prefix = if (isCharging) "⚡ " else "🔋 "
                bottomBatteryIcon.text = "$prefix$pct%"
            }
        }
    }

    // GPS Speedometer & 3D Camera Follower
    private var locationManager: LocationManager? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            val speedInt = Math.round(speedKmh).coerceAtLeast(0)
            centerSpeedValue.text = "$speedInt"
            centerSpeedSub.text = "GPS ACTIV • ±${location.accuracy.toInt()}m"
            bottomGpsCount?.text = "⌖ LOCK"

            // Actualizare badge viteza pe harta
        }
        override fun onProviderEnabled(provider: String) {
            centerSpeedSub.text = "GPS CONECTAT"
            bottomGpsCount?.text = "⌖ CON"
        }
        override fun onProviderDisabled(provider: String) {
            centerSpeedSub.text = "GPS OPRIT"
            bottomGpsCount?.text = "⌖ NO"
        }
    }

    private lateinit var tvSatelliteCount: TextView
    private var lastGnssTotal = 0
    private var lastGnssUsed = 0

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            lastGnssTotal = status.satelliteCount
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                }
            }
            lastGnssUsed = used
            if (::tvSatelliteCount.isInitialized) {
                tvSatelliteCount.text = "$lastGnssTotal ($lastGnssUsed)"
            }
        }

        override fun onStopped() {
            super.onStopped()
            lastGnssTotal = 0
            lastGnssUsed = 0
            if (::tvSatelliteCount.isInitialized) {
                tvSatelliteCount.text = "0 (0)"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra("EXTRA_CAR_BT_CONNECTED", false)) {
            isCarConnectedState = true
        }

        checkAndRequestBluetoothPermissions()
        initBluetoothProfiles()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        isNightMode = prefs.getBoolean("night_mode", true)

        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        initViews(savedInstanceState)
        val bModeStr = prefs.getString("brightness_mode", "auto") ?: "auto"
        currentBrightnessMode = when (bModeStr) {
            "day" -> BrightnessMode.DAY
            "night" -> BrightnessMode.NIGHT
            else -> BrightnessMode.AUTO
        }
        setBrightnessMode(currentBrightnessMode, showToast = false)
        setupListeners()
                updateCenterModeUI()
        refreshStations()

        instance = this
        if (intent.getBooleanExtra("EXTRA_AUTO_CLOSE", false)) {
            closeLauncher()
            return
        }
        if (intent.getBooleanExtra("EXTRA_CAR_BT_CONNECTED", false)) {
            startRadioPlayback()
        }
        checkAndRequestPhonePermissions()
        setupCallServiceListener()
        handleIncomingCallIntent(intent)
    }

    private fun enableImmersiveMode() {
        val inDisconnectCooldown = !isCarConnectedState && lastDisconnectTimestamp > 0L &&
                (android.os.SystemClock.elapsedRealtime() - lastDisconnectTimestamp < 10000L)
        if (!inDisconnectCooldown) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Permite desenarea bordului pe 100% din ecran fizic (inclusiv in jurul camerei de selfie, eliminand banda neagra)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        // Ecran complet imersiv (fara bara de notificari si bara de navigare)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
            focusPlayPauseButton()
        }
    }

    private fun initViews(savedInstanceState: Bundle?) {
        // Top Status Pill
        textClock = findViewById(R.id.text_clock)
        tvSatelliteCount = findViewById(R.id.tv_satellite_count)
        findViewById<View>(R.id.container_gps_satellites)?.setOnClickListener {
            val msg = if (lastGnssTotal > 0) {
                "Sateliți GNSS în vizor: $lastGnssTotal ($lastGnssUsed folosiți pentru fix)"
            } else {
                "Căutare sateliți GPS..."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Bottom Bar
        btnPrevTrack = findViewById(R.id.btn_prev_track)
        btnNextTrack = findViewById(R.id.btn_next_track)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        bottomTrackTitle = findViewById(R.id.bottom_track_title)
        bottomTrackTitle.isSelected = true
        bottomTrackArtist = findViewById(R.id.bottom_track_artist)
        bottomBatteryIcon = findViewById(R.id.bottom_battery_icon)
        btnVolumeDown = findViewById(R.id.btn_volume_down)
        btnVolumeUp = findViewById(R.id.btn_volume_up)
        btnNightMode = findViewById(R.id.btn_night_mode)
        btnQuickSettings = findViewById(R.id.btn_quick_settings)

        // Wings Stanga
        btnLeft1 = findViewById(R.id.btn_left_1)
        btnLeft2 = findViewById(R.id.btn_left_2)
        btnLeft3 = findViewById(R.id.btn_left_3)

        // Wings Dreapta
        btnRight1 = findViewById(R.id.btn_right_1)
        btnRight2 = findViewById(R.id.btn_right_2)
        btnRight3 = findViewById(R.id.btn_right_3)

        // Center
        centerCircleHalo = findViewById(R.id.center_circle_halo)
        centerCircle = findViewById(R.id.center_circle)
        centerCircle.setOnFocusChangeListener { _, hasFocus ->
            centerCircleHalo.setGlowActive(hasFocus)
        }
        viewCenterBmw = findViewById(R.id.view_center_bmw)

        viewCenterMedia = findViewById(R.id.view_center_media)
        viewCenterMedia.outlineProvider = circleOutlineProvider
        viewCenterMedia.clipToOutline = true

        youtubeCircleContainer = findViewById(R.id.youtube_circle_container)
        youtubeCircleContainer.outlineProvider = circleOutlineProvider
        youtubeCircleContainer.clipToOutline = true
        youtubeCircleTouchOverlay = findViewById(R.id.youtube_circle_touch_overlay)

        youtubeFullscreenOverlay = findViewById(R.id.youtube_fullscreen_overlay)
        youtubeFullscreenContainer = findViewById(R.id.youtube_fullscreen_container)
        btnYtBack = findViewById(R.id.btn_yt_back)
        btnYtDockCircle = findViewById(R.id.btn_yt_dock_circle)

        centerMediaThumbnail = findViewById(R.id.center_media_thumbnail)
        centerMediaScrim = findViewById(R.id.center_media_scrim)
        centerMediaInfoContainer = findViewById(R.id.center_media_info_container)
        centerMediaSource = findViewById(R.id.center_media_source)
        centerMediaTitle = findViewById(R.id.center_media_title)
        centerMediaArtist = findViewById(R.id.center_media_artist)
        centerMediaPlayerBadge = findViewById(R.id.center_media_player_badge)
        ivCenterMediaPlayerIcon = findViewById(R.id.iv_center_media_player_icon)
        tvCenterMediaPlayerName = findViewById(R.id.tv_center_media_player_name)

        initYouTubeWebView()

        viewCenterRadio = findViewById(R.id.view_center_radio)
        viewCenterRadio.outlineProvider = circleOutlineProvider
        viewCenterRadio.clipToOutline = true
        centerRadioArt = findViewById(R.id.center_radio_art)
        centerRadioTitle = findViewById(R.id.center_radio_title)
        centerRadioSub = findViewById(R.id.center_radio_sub)
        centerRadioStatus = findViewById(R.id.center_radio_status)

        viewCenterWaze = findViewById(R.id.view_center_waze)
        centerWazeIcon = findViewById(R.id.center_waze_icon)
        centerWazeDistance = findViewById(R.id.center_waze_distance)
        centerWazeRoad = findViewById(R.id.center_waze_road)
        centerWazeEta = findViewById(R.id.center_waze_eta)

        viewCenterSpeedo = findViewById(R.id.view_center_speedo)
        centerSpeedValue = findViewById(R.id.center_speed_value)
        centerSpeedUnit = findViewById(R.id.center_speed_unit)
        centerSpeedSub = findViewById(R.id.center_speed_sub)

        // Solar & Energy View
        viewCenterEnergy = findViewById(R.id.view_center_energy)
        viewCenterEnergy.outlineProvider = circleOutlineProvider
        viewCenterEnergy.clipToOutline = true
        tvEnergySolarPower = findViewById(R.id.tv_energy_solar_power)
        tvEnergyHouseLoad = findViewById(R.id.tv_energy_house_load)
        tvEnergyBatterySoc = findViewById(R.id.tv_energy_battery_soc)
        tvEnergyBatteryStatus = findViewById(R.id.tv_energy_battery_status)
        tvEnergyBatteryVoltage = findViewById(R.id.tv_energy_battery_voltage)
        tvEnergyBatteryPower = findViewById(R.id.tv_energy_battery_power)

        // Notification View
        viewCenterNotification = findViewById(R.id.view_center_notification)
        viewCenterNotification.outlineProvider = circleOutlineProvider
        viewCenterNotification.clipToOutline = true
        notifHeaderRow = findViewById(R.id.notif_header_row)
        notifCenterContent = findViewById(R.id.notif_center_content)
        notifBottomActions = findViewById(R.id.notif_bottom_actions)
        layoutNotifEmptyState = findViewById(R.id.layout_notif_empty_state)
        btnNotifPrev = findViewById(R.id.btn_notif_prev)
        btnNotifNext = findViewById(R.id.btn_notif_next)
        ivNotifAppIcon = findViewById(R.id.iv_notif_app_icon)
        tvNotifAppName = findViewById(R.id.tv_notif_app_name)
        tvNotifTime = findViewById(R.id.tv_notif_time)
        tvNotifCounter = findViewById(R.id.tv_notif_counter)
        btnNotifClose = findViewById(R.id.btn_notif_close)
        tvNotifSender = findViewById(R.id.tv_notif_sender)
        layoutNotifVoiceBadge = findViewById(R.id.layout_notif_voice_badge)
        tvNotifVoiceLabel = findViewById(R.id.tv_notif_voice_label)
        tvNotifText = findViewById(R.id.tv_notif_text)
        btnNotifAction = findViewById(R.id.btn_notif_action)
        ivNotifActionIcon = findViewById(R.id.iv_notif_action_icon)
        tvNotifActionText = findViewById(R.id.tv_notif_action_text)
        btnNotifSpeak = findViewById(R.id.btn_notif_speak)
        ivNotifSpeakIcon = findViewById(R.id.iv_notif_speak_icon)
        tvNotifSpeakText = findViewById(R.id.tv_notif_speak_text)

        btnNotifPrev.isFocusable = true
        btnNotifPrev.isFocusableInTouchMode = true
        btnNotifPrev.enableInstantClickOnTouch()
        btnNotifPrev.setOnClickListener {
            navigateToPreviousNotification()
        }

        btnNotifNext.isFocusable = true
        btnNotifNext.isFocusableInTouchMode = true
        btnNotifNext.enableInstantClickOnTouch()
        btnNotifNext.setOnClickListener {
            navigateToNextNotification()
        }

        btnNotifClose.isFocusable = true
        btnNotifClose.isFocusableInTouchMode = true
        btnNotifClose.enableInstantClickOnTouch()
        btnNotifClose.setOnClickListener {
            dismissCurrentNotificationWithAnim()
        }

        btnNotifAction.isFocusable = true
        btnNotifAction.isFocusableInTouchMode = true
        btnNotifAction.isClickable = true
        btnNotifAction.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        btnNotifAction.enableInstantClickOnTouch()

        btnNotifSpeak.isFocusable = true
        btnNotifSpeak.isFocusableInTouchMode = true
        btnNotifSpeak.isClickable = true
        btnNotifSpeak.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        btnNotifSpeak.enableInstantClickOnTouch()

        btnNotifAction.setOnClickListener {
            val notif = activeNotification ?: return@setOnClickListener
            Log.i("MainActivity", "btnNotifAction apasat! pkg=${notif.packageName}")
            notifDismissHandler.removeCallbacks(notifDismissRunnable)
            openNotification(notif)
        }

        btnNotifSpeak.setOnClickListener {
            val notif = activeNotification ?: return@setOnClickListener
            Log.i("MainActivity", "btnNotifSpeak apasat pentru pkg=${notif.packageName}, isVoice=${notif.isVoiceMessage}")
            notifDismissHandler.removeCallbacks(notifDismissRunnable)
            if (notif.isVoiceMessage) {
                if (VoiceMessagePlayer.isPlaying()) {
                    VoiceMessagePlayer.pause()
                } else if (VoiceMessagePlayer.getCurrentFile() != null) {
                    pauseOtherPlayersForVoiceMessage()
                    VoiceMessagePlayer.resume()
                } else {
                    pauseOtherPlayersForVoiceMessage()
                    playVoiceMessageOrFallback(notif)
                }
            } else {
                speakNotification(notif)
            }
        }

        VoiceMessagePlayer.init(this)
        VoiceMessagePlayer.listener = object : VoiceMessagePlayer.PlaybackListener {
            override fun onPlaybackStarted() {
                runOnUiThread {
                    notifDismissHandler.removeCallbacks(notifDismissRunnable)
                    tvNotifSpeakText?.text = "PAUZĂ"
                    ivNotifSpeakIcon?.setImageResource(R.drawable.ic_media_pause)
                    pauseOtherPlayersForVoiceMessage()
                }
            }

            override fun onPlaybackPaused() {
                runOnUiThread {
                    tvNotifSpeakText?.text = "REIA"
                    ivNotifSpeakIcon?.setImageResource(R.drawable.ic_media_play)
                }
            }

            override fun onPlaybackCompleted() {
                runOnUiThread {
                    tvNotifSpeakText?.text = "REDĂ"
                    ivNotifSpeakIcon?.setImageResource(R.drawable.ic_speaker)
                    resumeOtherPlayersAfterVoiceMessage()
                    resetNotifDismissTimer()
                }
            }

            override fun onPlaybackError(error: String) {
                runOnUiThread {
                    tvNotifSpeakText?.text = "REDĂ"
                    ivNotifSpeakIcon?.setImageResource(R.drawable.ic_speaker)
                    resumeOtherPlayersAfterVoiceMessage()
                    resetNotifDismissTimer()
                    val notif = activeNotification
                    if (notif != null) {
                        openNotification(notif)
                    }
                }
            }
        }

        // Initializare Text to Speech
        initTextToSpeech()

        // Abonare la evenimentele de notificare
        CarNotificationHub.onNotificationReceived = { notif ->
            displayNotification(notif)
        }
        CarNotificationHub.onUnreadCountChanged = { _ ->
        }
        CarNotificationHub.onNotificationsListChanged = { _ ->
            checkAndShowPendingNotifications()
        }
        if (CarNotificationListenerService.isNotificationAccessGranted(this)) {
            CarNotificationListenerService.rebindService(this)
        }
        handleNotificationTestIntent(intent)

        // Inițializare Fereastră Contacte Favorite (Half-Screen Overlay)
        favoritesOverlayContainer = findViewById(R.id.favorites_overlay_container)
        btnCloseFavorites = findViewById(R.id.btn_close_favorites)
        btnOpenDialer = findViewById(R.id.btn_open_dialer)
        listFavoriteContacts = findViewById(R.id.list_favorite_contacts)
        listFavoriteContacts.itemsCanFocus = true
        tvEmptyFavorites = findViewById(R.id.tv_empty_favorites)

        btnCloseFavorites.setOnClickListener {
            favoritesOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        favoritesOverlayContainer.setOnClickListener {
            favoritesOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        btnOpenDialer.setOnClickListener {
            favoritesOverlayContainer.visibility = View.GONE
            launchPhoneDialer()
            focusPlayPauseButton()
        }

        // Inițializare Fereastră Posturi Radio
        radioOverlayContainer = findViewById(R.id.radio_overlay_container)
        btnCloseRadio = findViewById(R.id.btn_close_radio)
        btnAddStation = findViewById(R.id.btn_add_station)
        listRadioStations = findViewById(R.id.list_radio_stations)
        listRadioStations.itemsCanFocus = true
        btnCloseRadio.setOnClickListener {
            radioOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        radioOverlayContainer.setOnClickListener {
            radioOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        btnAddStation.setOnClickListener {
            radioOverlayContainer.visibility = View.GONE
            showAddStationDialog()
            focusPlayPauseButton()
        }

        // Inițializare Fereastră Aplicații Muzică
        musicOverlayContainer = findViewById(R.id.music_overlay_container)
        btnCloseMusic = findViewById(R.id.btn_close_music)
        listMusicApps = findViewById(R.id.list_music_apps)
        listMusicApps.itemsCanFocus = true
        btnCloseMusic.setOnClickListener {
            musicOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        musicOverlayContainer.setOnClickListener {
            musicOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }

        // Inițializare Media Player & Music Manager
        LocalMusicManager.initialize(this)
        LocalMusicPlayer.initialize(this)
        LocalMusicPlayer.addListener(object : LocalMusicPlayer.PlaybackListener {
            override fun onTrackChanged(track: LocalTrack?) {
                runOnUiThread {
                    if (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER && track != null && currentMode == CenterMode.MEDIA && !isYouTubeWebMode) {
                        val bmp = LocalMusicPlayer.loadArtwork(this@MainActivity, track)
                        applyMusicArtworkToCenterMedia(bmp)
                    }
                    updateCenterMediaPlayerBadge()
                    updateNowPlayingBar()
                }
            }

            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                runOnUiThread {
                    if (isPlaying) {
                        activeMediaSource = ActiveMediaSource.LOCAL_PLAYER
                    }
                    if (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER && currentMode == CenterMode.MEDIA && !isYouTubeWebMode) {
                        updatePlayPauseIcon(isPlaying)
                    }
                    updateCenterMediaPlayerBadge()
                    updateNowPlayingBar()
                }
            }
        })

        // Inițializare Fereastră Media Player
        mediaPlayerOverlayContainer = findViewById(R.id.media_player_overlay_container)
        btnMediaBack = findViewById(R.id.btn_media_back)
        mediaHeaderTitle = findViewById(R.id.media_header_title)
        btnAddPlaylist = findViewById(R.id.btn_add_playlist)
        btnScanMusic = findViewById(R.id.btn_scan_music)
        btnMediaShuffle = findViewById(R.id.btn_media_shuffle)
        iconMediaShuffle = findViewById(R.id.icon_media_shuffle)
        btnMediaRepeat = findViewById(R.id.btn_media_repeat)
        iconMediaRepeat = findViewById(R.id.icon_media_repeat)
        btnCloseMediaPlayer = findViewById(R.id.btn_close_media_player)
        listMediaPlaylists = findViewById(R.id.list_media_playlists)
        listMediaTracks = findViewById(R.id.list_media_tracks)
        mediaEmptyText = findViewById(R.id.media_empty_text)

        listMediaPlaylists.itemsCanFocus = true
        listMediaTracks.itemsCanFocus = true

        btnCloseMediaPlayer.setOnClickListener {
            mediaPlayerOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        mediaPlayerOverlayContainer.setOnClickListener {
            mediaPlayerOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        btnMediaBack.setOnClickListener {
            showMediaPlaylistsView()
        }
        btnAddPlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }
        btnScanMusic.setOnClickListener {
            Toast.makeText(this, "Scanare fisiere audio...", Toast.LENGTH_SHORT).show()
            kotlin.concurrent.thread {
                LocalMusicManager.scanDeviceTracks(this)
                runOnUiThread {
                    refreshMediaOverlayUI()
                    Toast.makeText(this, "Scanare completă: ${LocalMusicManager.cachedTracks.size} piese", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnMediaShuffle.setOnClickListener {
            val isShuffle = LocalMusicPlayer.toggleShuffle()
            updateShuffleIcon(isShuffle)
            Toast.makeText(this, if (isShuffle) "Shuffle: ACTIVAT" else "Shuffle: DEZACTIVAT", Toast.LENGTH_SHORT).show()
        }
        btnMediaRepeat.setOnClickListener {
            val nextRepeat = LocalMusicPlayer.cycleRepeatMode()
            updateRepeatIcon(nextRepeat)
            val msg = when (nextRepeat) {
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repetare: TOATE"
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "Repetare: O PIESĂ"
                else -> "Repetare: OPRIT"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Inițializare Fereastră Toate Aplicațiile
        appsOverlayContainer = findViewById(R.id.apps_overlay_container)
        btnCloseApps = findViewById(R.id.btn_close_apps)
        gridAllApps = findViewById(R.id.grid_all_apps)
        btnCloseApps.setOnClickListener {
            appsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        appsOverlayContainer.setOnClickListener {
            appsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }

        // Inițializare Fereastră Setări Aplicație
        settingsOverlayContainer = findViewById(R.id.settings_overlay_container)
        btnCloseSettings = findViewById(R.id.btn_close_settings)
        seekDayBrightness = findViewById(R.id.seek_day_brightness)
        seekNightBrightness = findViewById(R.id.seek_night_brightness)
        tvDayBrightnessVal = findViewById(R.id.tv_day_brightness_val)
        tvNightBrightnessVal = findViewById(R.id.tv_night_brightness_val)
        btnOpenNotifSettings = findViewById(R.id.btn_open_notif_settings)
        tvNotifAccessStatus = findViewById(R.id.tv_notif_access_status)
        btnCarBtSelect = findViewById(R.id.btn_car_bt_select)
        tvCarBtStatus = findViewById(R.id.tv_car_bt_status)

        tvNavSourceLabel = findViewById(R.id.tv_nav_source_label)
        centerNavIconImg = findViewById(R.id.center_nav_icon_img)

        initSettingsOverlay()

        // Inițializare Fereastră Apel Telefonic (Half-Screen Overlay)
        callOverlayContainer = findViewById(R.id.call_overlay_container)
        callPhotoFrame = findViewById(R.id.call_photo_frame)
        callPhotoFrame.outlineProvider = circleOutlineProvider
        callPhotoFrame.clipToOutline = true

        callStatusTitle = findViewById(R.id.call_status_title)
        callDurationTimer = findViewById(R.id.call_duration_timer)
        callContactPhoto = findViewById(R.id.call_contact_photo)
        callContactPhoto.outlineProvider = circleOutlineProvider
        callContactPhoto.clipToOutline = true
        callContactName = findViewById(R.id.call_contact_name)
        callPhoneNumber = findViewById(R.id.call_phone_number)
        callRingingActions = findViewById(R.id.call_ringing_actions)
        btnCallDeny = findViewById(R.id.btn_call_deny)
        btnCallAnswer = findViewById(R.id.btn_call_answer)
        btnCallHangup = findViewById(R.id.btn_call_hangup)

        btnCallAnswer.setOnClickListener {
            if (!BMWInCallService.answerCurrentCall()) {
                answerPhoneCall()
            }
            showActiveCallUI()
        }

        btnCallDeny.setOnClickListener {
            if (!BMWInCallService.rejectCurrentCall()) {
                hangupPhoneCall()
            }
            dismissCallUI()
            focusPlayPauseButton()
        }

        btnCallHangup.setOnClickListener {
            if (!BMWInCallService.hangupCurrentCall()) {
                hangupPhoneCall()
            }
            dismissCallUI()
            focusPlayPauseButton()
        }

        // Configurare focusare pentru navigare TAB / tastatură / iDrive și clic instant tactil
        focusOrderViews.forEach { view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.enableInstantClickOnTouch()
        }
        viewCenterBmw.enableInstantClickOnTouch()
        viewCenterRadio.enableInstantClickOnTouch()
        viewCenterMedia.enableInstantClickOnTouch()
        youtubeCircleTouchOverlay.enableInstantClickOnTouch()
        viewCenterEnergy.enableInstantClickOnTouch()

        // Butoane din ferestrele auxiliare - clic instant la prima atingere
        val overlayButtons = listOf(
            btnCloseFavorites, btnOpenDialer,
            btnCloseRadio, btnAddStation,
            btnCloseMusic, btnCloseApps,
            btnCloseSettings,
            btnCallAnswer, btnCallDeny, btnCallHangup
        )
        overlayButtons.forEach { btn ->
            btn.isFocusable = true
            btn.isFocusableInTouchMode = false
            btn.enableInstantClickOnTouch()
        }

        
        focusPlayPauseButton()
    }

    private fun expandStatusBar() {
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val expandMethod = statusBarManager.getMethod("expandNotificationsPanel")
            expandMethod.invoke(statusBarService)
        } catch (e: Exception) {
            Log.e("MainActivity", "expandStatusBar failed: ${e.message}")
        }
    }

    private fun launchGalleryApp() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_GALLERY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (intent.resolveActivity(packageManager) != null) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        val packages = listOf(
            "com.sec.android.gallery3d",
            "com.google.android.apps.photos",
            "com.android.gallery3d",
            "com.miui.gallery"
        )
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (_: Exception) {}
            }
        }
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(viewIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Aplicație Galerie indisponibilă", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFacebookApp() {
        val pkg = "com.facebook.katana"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage("com.facebook.lite")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                return
            } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Facebook nu este instalat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPlayStoreApp() {
        val pkg = "com.android.vending"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                return
            } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Google Play Store indisponibil", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFilesApp() {
        val packages = listOf(
            "com.sec.android.app.myfiles",
            "com.google.android.apps.nbu.files",
            "com.android.documentsui",
            "com.mi.android.globalFileexplorer"
        )
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (_: Exception) {}
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_FILES)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (intent.resolveActivity(packageManager) != null) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        try {
            val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(docIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Aplicație Fișiere indisponibilă", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchHomeAssistantApp() {
        val pkg = "io.homeassistant.companion.android"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage("io.homeassistant.companion.android.minimal")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: Exception) {}
        } else {
            Toast.makeText(this, "Home Assistant nu este instalat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchNotesApp() {
        val packages = listOf(
            "com.samsung.android.app.notes",
            "com.google.android.keep",
            "com.miui.notes",
            "com.microsoft.notes"
        )
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (_: Exception) {}
            }
        }
        try {
            val intent = Intent(Intent.ACTION_CREATE_NOTE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        } catch (_: Exception) {}
        Toast.makeText(this, "Aplicație de Note indisponibilă", Toast.LENGTH_SHORT).show()
    }

    private fun launchWalletApp() {
        val pkg = "com.google.android.apps.walletnfcrel"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.walletnfcrel.staging")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: Exception) {}
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Google Wallet nu este instalat", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchChromeApp() {
        val pkg = "com.android.chrome"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: Exception) {}
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Google Chrome indisponibil", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCameraApp() {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera foto indisponibilă", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun initYouTubeWebView() {
        val wv = object : WebView(this) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                // Mentine redarea fluida chiar si cand utilizatorul comuta Cercul Central pe Harta / Vitezometru
                if (isYouTubeWebMode) {
                    super.onWindowVisibilityChanged(View.VISIBLE)
                } else {
                    super.onWindowVisibilityChanged(visibility)
                }
            }
        }

        wv.setBackgroundColor(Color.BLACK)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
        } catch (_: Exception) {}

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVideoStateChanged(playing: Boolean) {
                runOnUiThread {
                    val stateChanged = (isYouTubeWebPlaying != playing)
                    isYouTubeWebPlaying = playing
                    if (playing && stateChanged) {
                        controller?.pause()
                        try {
                            val mc = ExternalMediaManager.getActiveMusicController(this@MainActivity)
                            if (mc != null && mc.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                                mc.transportControls.pause()
                            }
                        } catch (_: Exception) {}
                    }
                    if (currentMode == CenterMode.MEDIA && isYouTubeWebMode) {
                        updatePlayPauseIcon(playing)
                    }
                    updateNowPlayingBar()
                }
            }

            @JavascriptInterface
            fun onVideoMetadataChanged(title: String, artist: String) {
                runOnUiThread {
                    youTubeWebTitle = title.trim()
                    youTubeWebArtist = artist.trim()
                    updateNowPlayingBar()
                }
            }
        }, "BmwYtBridge")

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val currentUrl = url ?: return
                injectYouTubeMonitorJs()

                val isWatchUrl = currentUrl.contains("/watch") || currentUrl.contains("/shorts/")
                if (isWatchUrl && youtubeFullscreenOverlay.visibility == View.VISIBLE && currentUrl != urlAtFullscreenOpen) {
                    // Cand utilizatorul apasa pe un videoclip din lista YouTube, il mutam automat in Cercul Central
                    urlAtFullscreenOpen = currentUrl
                    window.decorView.postDelayed({
                        if (youtubeFullscreenOverlay.visibility == View.VISIBLE) {
                            dockYouTubeToCircle()
                        }
                    }, 450L)
                } else if (isYouTubeDockedInCircle) {
                    applyCircleVideoModeJs()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectYouTubeMonitorJs()
                if (isYouTubeDockedInCircle) {
                    applyCircleVideoModeJs()
                }
            }
        }

        youtubeWebView = wv
    }

    private fun injectYouTubeMonitorJs() {
        val js = """
            (function() {
                if (!HTMLMediaElement.prototype.__bmwOrigPause) {
                    HTMLMediaElement.prototype.__bmwOrigPause = HTMLMediaElement.prototype.pause;
                    HTMLMediaElement.prototype.pause = function() {
                        if (window.__bmwCircleModeActive && !window.__bmwUserPaused && !this.ended && !this.seeking) {
                            return;
                        }
                        return HTMLMediaElement.prototype.__bmwOrigPause.apply(this, arguments);
                    };
                }
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch(e) {}

                if (window.__bmwYtMonitorInstalled) return;
                window.__bmwYtMonitorInstalled = true;
                window.__bmwLastPlayAttempt = 0;
                setInterval(function() {
                    var isWatch = location.pathname.indexOf('/watch') !== -1 || location.pathname.indexOf('/shorts') !== -1;
                    if (window.__bmwCircleModeActive && isWatch) {
                        if (!document.getElementById('bmw-circle-style') && window.__bmwInstallCircleCss) {
                            window.__bmwInstallCircleCss();
                        }
                    }
                    var v = document.querySelector('video');
                    if (v && isWatch) {
                        if (window.__bmwCircleModeActive) {
                            if (v.muted) v.muted = false;
                            var now = Date.now();
                            if (!window.__bmwUserPaused && v.paused && !v.seeking && !v.ended && (now - (window.__bmwLastPlayAttempt || 0) > 1200)) {
                                window.__bmwLastPlayAttempt = now;
                                var mp = document.getElementById('movie_player');
                                if (mp && typeof mp.playVideo === 'function') {
                                    try { mp.playVideo(); } catch(e) {}
                                }
                                v.play().catch(function(){});
                            }
                        }
                        var isPlaying = !v.paused && !v.ended;
                        if (window.BmwYtBridge && window.__bmwLastReportedPlaying !== isPlaying) {
                            window.__bmwLastReportedPlaying = isPlaying;
                            window.BmwYtBridge.onVideoStateChanged(isPlaying);
                        }
                        var rawTitle = '';
                        var rawArtist = '';
                        if (navigator.mediaSession && navigator.mediaSession.metadata) {
                            rawTitle = navigator.mediaSession.metadata.title || '';
                            rawArtist = navigator.mediaSession.metadata.artist || '';
                        }
                        if (!rawTitle) {
                            var titleEl = document.querySelector('.slim-video-information-title, ytm-slim-video-metadata-section-renderer h2, .ytp-title-link');
                            if (titleEl) rawTitle = (titleEl.textContent || '').trim();
                        }
                        if (!rawTitle && document.title) {
                            rawTitle = document.title.replace(/\s*-\s*YouTube$/i, '').trim();
                        }
                        if (!rawArtist) {
                            var artistEl = document.querySelector('ytm-slim-owner-renderer .subhead, .slim-owner-icon-and-title .subhead, .ytm-channel-name');
                            if (artistEl) rawArtist = (artistEl.textContent || '').trim();
                        }
                        if (window.BmwYtBridge && rawTitle && (window.__bmwLastTitle !== rawTitle || window.__bmwLastArtist !== rawArtist)) {
                            window.__bmwLastTitle = rawTitle;
                            window.__bmwLastArtist = rawArtist;
                            window.BmwYtBridge.onVideoMetadataChanged(rawTitle, rawArtist || 'YouTube');
                        }
                    }
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                    if (skipBtn) skipBtn.click();
                }, 600);
            })();
        """.trimIndent()
        youtubeWebView?.evaluateJavascript(js, null)
    }

    private fun applyCircleVideoModeJs() {
        val js = """
            (function() {
                window.__bmwCircleModeActive = true;
                window.__bmwUserPaused = false;
                if (!HTMLMediaElement.prototype.__bmwOrigPause) {
                    HTMLMediaElement.prototype.__bmwOrigPause = HTMLMediaElement.prototype.pause;
                    HTMLMediaElement.prototype.pause = function() {
                        if (window.__bmwCircleModeActive && !window.__bmwUserPaused && !this.ended && !this.seeking) {
                            return;
                        }
                        return HTMLMediaElement.prototype.__bmwOrigPause.apply(this, arguments);
                    };
                }
                window.__bmwInstallCircleCss = function() {
                    if (document.getElementById('bmw-circle-style')) return;
                    var style = document.createElement('style');
                    style.id = 'bmw-circle-style';
                    style.textContent = `
                        header, ytm-mobile-topbar-renderer, .mobile-topbar-header,
                        ytm-single-column-watch-next-results-renderer, ytm-item-section-renderer,
                        ytm-structured-description-content-renderer, ytm-comment-section-renderer,
                        .related-chips-slot-wrapper, ytm-engagement-panel-section-list-renderer,
                        .player-controls-content, .ytp-chrome-top, .ytp-chrome-bottom,
                        #player-control-overlay, ytm-custom-control, .slim-video-metadata-header,
                        ytm-pivot-bar-renderer, ytm-mealbar-promo-renderer, .ytp-watermark,
                        .ytp-pause-overlay, .ytp-unmute, .ytp-cued-thumbnail-overlay {
                            opacity: 0 !important;
                            pointer-events: none !important;
                        }
                        html, body, ytm-app, #app, ytm-watch, .watch-below-the-player {
                            margin: 0 !important;
                            padding: 0 !important;
                            background: #000 !important;
                            overflow: hidden !important;
                            width: 100vw !important;
                            height: 100vh !important;
                        }
                        #player-container-id, .player-container, #player, .html5-video-player, .html5-video-container {
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            right: 0 !important;
                            bottom: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            max-width: 100vw !important;
                            max-height: 100vh !important;
                            margin: 0 !important;
                            padding: 0 !important;
                            z-index: 2147483646 !important;
                            background: #000 !important;
                        }
                        video, .html5-main-video {
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            min-width: 100vw !important;
                            min-height: 100vh !important;
                            object-fit: cover !important;
                            z-index: 2147483647 !important;
                        }
                    `;
                    if (document.head) document.head.appendChild(style);
                };

                var isWatch = location.pathname.indexOf('/watch') !== -1 || location.pathname.indexOf('/shorts') !== -1;
                if (!isWatch && !window.__bmwAutoOpeningWatch) {
                    var firstLink = document.querySelector('ytm-video-with-context-renderer a[href*="/watch"], ytm-media-item a[href*="/watch"], a[href*="/watch?v="]');
                    if (firstLink) {
                        window.__bmwAutoOpeningWatch = true;
                        setTimeout(function() { window.__bmwAutoOpeningWatch = false; }, 2500);
                        firstLink.click();
                        return;
                    }
                }

                window.__bmwInstallCircleCss();
                var mp = document.getElementById('movie_player');
                if (mp && typeof mp.playVideo === 'function') {
                    try { mp.playVideo(); } catch(e) {}
                }
                var v = document.querySelector('video');
                if (v) {
                    if (v.muted) v.muted = false;
                    if (v.paused) {
                        window.__bmwLastPlayAttempt = Date.now();
                        v.play().catch(function(){});
                    }
                }
            })();
        """.trimIndent()
        youtubeWebView?.evaluateJavascript(js, null)
    }

    private fun disableCircleVideoModeJs() {
        val js = """
            (function() {
                window.__bmwCircleModeActive = false;
                var style = document.getElementById('bmw-circle-style');
                if (style) style.remove();
            })();
        """.trimIndent()
        youtubeWebView?.evaluateJavascript(js, null)
    }

    private fun openYouTubeFullscreen() {
        // 1. Opreste radioul intern si sesiunile audio externe
        controller?.pause()
        LocalMusicPlayer.pause()
        try {
            ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
        } catch (_: Exception) {}

        isYouTubeWebMode = true
        activeMediaSource = ActiveMediaSource.YOUTUBE_WEB
        isYouTubeDockedInCircle = false
        currentMode = CenterMode.MEDIA

        val wv = youtubeWebView ?: return
        urlAtFullscreenOpen = wv.url ?: ""

        disableCircleVideoModeJs()

        (wv.parent as? ViewGroup)?.removeView(wv)
        youtubeFullscreenContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        youtubeFullscreenOverlay.visibility = View.VISIBLE
        updateCenterModeUI()

        if (wv.url.isNullOrEmpty() || wv.url == "about:blank") {
            wv.loadUrl("https://m.youtube.com")
        } else {
            wv.evaluateJavascript("var v = document.querySelector('video'); if (v && v.paused) v.play();", null)
        }
    }

    private fun dockYouTubeToCircle() {
        val wv = youtubeWebView ?: return
        isYouTubeWebMode = true
        isYouTubeDockedInCircle = true
        currentMode = CenterMode.MEDIA

        youtubeFullscreenOverlay.visibility = View.GONE

        (wv.parent as? ViewGroup)?.removeView(wv)
        youtubeCircleContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        youtubeCircleContainer.visibility = View.VISIBLE
        youtubeCircleTouchOverlay.visibility = View.VISIBLE
        centerMediaThumbnail.visibility = View.GONE
        centerMediaScrim.visibility = View.GONE
        centerMediaInfoContainer.visibility = View.VISIBLE

        updateCenterModeUI()
        injectYouTubeMonitorJs()
        applyCircleVideoModeJs()

        window.decorView.postDelayed({
            applyCircleVideoModeJs()
        }, 400L)
        window.decorView.postDelayed({
            applyCircleVideoModeJs()
        }, 1000L)
    }

    private fun stopYouTubeWebPlayer() {
        isYouTubeWebMode = false
        isYouTubeWebPlaying = false
        isYouTubeDockedInCircle = false
        youtubeFullscreenOverlay.visibility = View.GONE
        youtubeCircleContainer.visibility = View.GONE
        youtubeCircleTouchOverlay.visibility = View.GONE
        centerMediaScrim.visibility = View.GONE
        centerMediaInfoContainer.visibility = View.VISIBLE
        centerMediaThumbnail.visibility = View.VISIBLE
        youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
        updateNowPlayingBar()
        updateCenterMediaPlayerBadge()
    }

    private fun setupListeners() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Bottom Bar Media Controls
        btnPrevTrack.setOnClickListener {
            if (activeMediaSource == ActiveMediaSource.RADIO || controller?.isPlaying == true) {
                controller?.seekToPreviousMediaItem()
                return@setOnClickListener
            }
            if (currentMode == CenterMode.MEDIA) {
                if (isYouTubeWebMode || activeMediaSource == ActiveMediaSource.YOUTUBE_WEB) {
                    youtubeWebView?.evaluateJavascript(
                        "(function(){ window.__bmwUserPaused = false; var v = document.querySelector('video'); if (v && v.currentTime > 5) { v.currentTime = 0; v.play(); } else { history.back(); } })();",
                        null
                    )
                    updatePlayPauseIcon(true)
                    return@setOnClickListener
                }
                if (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER || LocalMusicPlayer.isPlaying) {
                    LocalMusicPlayer.previous()
                    updatePlayPauseIcon(true)
                    return@setOnClickListener
                }
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.skipToPrevious()
                    updatePlayPauseIcon(true)
                    ExternalMediaManager.attachCallback(mc)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 300L)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 800L)
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    updatePlayPauseIcon(true)
                }
            } else if (LocalMusicPlayer.isPlaying) {
                LocalMusicPlayer.previous()
                updatePlayPauseIcon(true)
            } else {
                controller?.seekToPreviousMediaItem()
            }
        }
        btnPlayPause.setOnClickListener {
            when (currentMode) {
                CenterMode.RADIO, CenterMode.MEDIA -> {
                    if (activeMediaSource == ActiveMediaSource.RADIO || controller?.isPlaying == true) {
                        controller?.let { c ->
                            if (c.isPlaying) {
                                c.pause()
                                updatePlayPauseIcon(false)
                            } else {
                                LocalMusicPlayer.pause()
                                stopYouTubeWebPlayer()
                                try {
                                    ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
                                } catch (_: Exception) {}
                                c.play()
                                updatePlayPauseIcon(true)
                            }
                        }
                        return@setOnClickListener
                    }
                    if (isYouTubeWebMode || activeMediaSource == ActiveMediaSource.YOUTUBE_WEB) {
                        val isCurrentlyPlaying = isCurrentlyPlayingState
                        if (isCurrentlyPlaying) {
                            youtubeWebView?.evaluateJavascript(
                                "window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();",
                                null
                            )
                            isYouTubeWebPlaying = false
                            updatePlayPauseIcon(false)
                        } else {
                            applyCircleVideoModeJs()
                            isYouTubeWebPlaying = true
                            updatePlayPauseIcon(true)
                        }
                        return@setOnClickListener
                    }
                    if (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER) {
                        LocalMusicPlayer.togglePlayPause()
                        updatePlayPauseIcon(LocalMusicPlayer.isPlaying)
                        return@setOnClickListener
                    }
                    val mc = ExternalMediaManager.getActiveMusicController(this)
                    val pbState = mc?.playbackState?.state
                    val isActuallyPlaying = (pbState == PlaybackState.STATE_PLAYING || pbState == PlaybackState.STATE_BUFFERING)

                    if (isActuallyPlaying) {
                        mc?.transportControls?.pause()
                        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                        updatePlayPauseIcon(false)
                    } else {
                        val errorMsg = mc?.playbackState?.errorMessage?.toString() ?: ""
                        val isYtMusicUpgradeError = mc != null &&
                                mc.packageName.contains("youtube.music", ignoreCase = true) &&
                                (pbState == PlaybackState.STATE_ERROR || errorMsg.contains("upgrade", ignoreCase = true))

                        if (isYtMusicUpgradeError) {
                            Toast.makeText(
                                this,
                                "YouTube Music gratuit necesită redare pe ecran (sau Premium/ReVanced pt fundal). Se deschide...",
                                Toast.LENGTH_LONG
                            ).show()
                            val launchIntent = packageManager.getLaunchIntentForPackage(mc?.packageName ?: "")
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launchIntent)
                            }
                        } else if (mc != null) {
                            mc.transportControls.play()
                            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                            updatePlayPauseIcon(true)
                            window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 300L)
                            window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 800L)
                        } else {
                            // Dacă nicio aplicație externă nu e activă, pornește Radio-ul!
                            activeMediaSource = ActiveMediaSource.RADIO
                            controller?.play()
                            updatePlayPauseIcon(true)
                            updateCenterModeUI()
                        }
                    }
                }
                else -> {
                    if (LocalMusicPlayer.isPlaying) {
                        LocalMusicPlayer.pause()
                        updatePlayPauseIcon(false)
                    } else if (controller?.isPlaying == true) {
                        controller?.pause()
                    } else if (isYouTubeWebMode && isYouTubeWebPlaying) {
                        youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
                        isYouTubeWebPlaying = false
                        updatePlayPauseIcon(false)
                    } else if (ExternalMediaManager.currentData.isPlaying) {
                        val mc = ExternalMediaManager.getActiveMusicController(this)
                        if (mc != null) {
                            mc.transportControls.pause()
                        } else {
                            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                        }
                    } else {
                        // Niciun player nu canta in acest moment: apasarea pe PLAY porneste Radio-ul!
                        stopYouTubeWebPlayer()
                        try {
                            ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
                        } catch (_: Exception) {}
                        activeMediaSource = ActiveMediaSource.RADIO
                        controller?.play()
                    }
                }
            }
        }
        btnNextTrack.setOnClickListener {
            if (activeMediaSource == ActiveMediaSource.RADIO || controller?.isPlaying == true) {
                controller?.seekToNextMediaItem()
                return@setOnClickListener
            }
            if (currentMode == CenterMode.MEDIA) {
                if (isYouTubeWebMode || activeMediaSource == ActiveMediaSource.YOUTUBE_WEB) {
                    val nextJs = """
                        (function() {
                            var nextBtn = document.querySelector('.ytp-next-button, [aria-label*="Next"], [aria-label*="Înainte"], [aria-label*="Următor"]');
                            if (nextBtn) {
                                nextBtn.click();
                                return;
                            }
                            var links = document.querySelectorAll('ytm-media-item a[href*="/watch"], a.compact-media-item-image[href*="/watch"]');
                            if (links && links.length > 0) {
                                links[0].click();
                            }
                        })();
                    """.trimIndent()
                    youtubeWebView?.evaluateJavascript(nextJs, null)
                    updatePlayPauseIcon(true)
                    return@setOnClickListener
                }
                if (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER || LocalMusicPlayer.isPlaying) {
                    LocalMusicPlayer.next()
                    updatePlayPauseIcon(true)
                    return@setOnClickListener
                }
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.skipToNext()
                    updatePlayPauseIcon(true)
                    ExternalMediaManager.attachCallback(mc)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 300L)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 800L)
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                    updatePlayPauseIcon(true)
                }
            } else if (LocalMusicPlayer.isPlaying) {
                LocalMusicPlayer.next()
                updatePlayPauseIcon(true)
            } else {
                controller?.seekToNextMediaItem()
            }
        }

        // Butoane Volum Jos (dezactivate conform E-Sixty Dash)
        btnVolumeDown.visibility = View.GONE
        btnVolumeUp.visibility = View.GONE

        val openCurrentMediaApp = View.OnClickListener {
            if (activeMediaSource == ActiveMediaSource.RADIO || controller?.isPlaying == true) {
                if (currentMode != CenterMode.MEDIA) {
                    currentMode = CenterMode.MEDIA
                    updateCenterModeUI()
                }
                showStationPickerDialog()
                return@OnClickListener
            }
            if (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER) {
                if (currentMode != CenterMode.MEDIA) {
                    currentMode = CenterMode.MEDIA
                    updateCenterModeUI()
                }
                showMediaPlayerOverlay(openCurrentPlaying = true)
                return@OnClickListener
            }
            val pkg = ExternalMediaManager.currentData.packageName.ifEmpty {
                ExternalMediaManager.preferredPackage
            }
            if (isYouTubeWebMode || activeMediaSource == ActiveMediaSource.YOUTUBE_WEB) {
                if (currentMode != CenterMode.MEDIA) {
                    currentMode = CenterMode.MEDIA
                    updateCenterModeUI()
                }
                openYouTubeFullscreen()
            } else if (pkg.isNotEmpty() && (currentMode == CenterMode.MEDIA || controller?.isPlaying != true)) {
                if (currentMode != CenterMode.MEDIA) {
                    currentMode = CenterMode.MEDIA
                    updateCenterModeUI()
                }
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
            } else {
                showMusicAppPickerDialog()
            }
        }
        bottomTrackTitle.setOnClickListener(openCurrentMediaApp)
        bottomTrackArtist.setOnClickListener(openCurrentMediaApp)
        findViewById<View>(R.id.pill_status_right)?.setOnClickListener(openCurrentMediaApp)
        centerMediaPlayerBadge.setOnClickListener(openCurrentMediaApp)

        // Night Mode / Auto Brightness dezactivat conform E-Sixty Dash
        btnNightMode.visibility = View.GONE
        btnQuickSettings.setOnClickListener {
            showSettingsOverlay()
        }
        btnQuickSettings.setOnLongClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Setari Accesibilitate -> Activati 'BMW Car Launcher'", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            true
        }

        // Clic / Enter pe Cercul Central -> comuta afisajul in bucla (BMW -> Media -> Radio -> Map -> BMW)
        centerCircle.setOnClickListener {
            cycleCenterMode()
        }
        centerCircle.setOnLongClickListener {
            openAllApps()
            true
        }

        viewCenterBmw.setOnClickListener {
            cycleCenterMode()
        }
        viewCenterBmw.setOnLongClickListener {
            openAllApps()
            true
        }

        viewCenterEnergy.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterNotification.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterRadio.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterMedia.setOnClickListener {
            cycleCenterMode()
        }

        youtubeCircleTouchOverlay.setOnClickListener {
            cycleCenterMode()
        }
        youtubeCircleTouchOverlay.setOnLongClickListener {
            if (currentMode == CenterMode.MEDIA) {
                openYouTubeFullscreen()
                true
            } else {
                false
            }
        }

        btnYtBack.setOnClickListener {
            val wv = youtubeWebView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                dockYouTubeToCircle()
            }
        }

        btnYtDockCircle.setOnClickListener {
            dockYouTubeToCircle()
        }

        
        // L1: RADIO - Deschide direct lista cu posturile radio (Station Picker Dialog)
        btnLeft1.setOnClickListener {
            showStationPickerDialog()
        }
        btnLeft1.setOnLongClickListener {
            showStationPickerDialog()
            true
        }

        // L2: NAVI - Comută cercul central pe Navigare Google Maps sau lansează aplicația
        btnLeft2.setOnClickListener {
            if (currentMode != CenterMode.MAP) {
                val prev = currentMode
                currentMode = CenterMode.MAP
                updateCenterModeUI(animateFrom = prev)
            } else {
                launchNavigationApp()
            }
        }
        btnLeft2.setOnLongClickListener {
            launchNavigationApp()
            true
        }

        // L3: MUSIC - Deschide lista de selectie (YouTube Music / YouTube) si lanseaza aplicatia aleasa
        btnLeft3.setOnClickListener {
            showMusicAppPickerDialog()
        }
        btnLeft3.setOnLongClickListener {
            showMusicAppPickerDialog()
            true
        }

        // R1: PHONE - Deschide fereastra cu Apeluri Recente (sau dialer la apasare lunga)
        btnRight1.setOnClickListener {
            showRecentCallsOverlay()
        }
        btnRight1.setOnLongClickListener {
            launchPhoneDialer()
            true
        }

        // R2: WHATSAPP - Deschide aplicatia WhatsApp
        btnRight2.setOnClickListener {
            launchWhatsApp()
        }

        // R3: APPS - Deschide lista de aplicatii instalate (App Drawer)
        btnRight3.setOnClickListener {
            openAllApps()
        }
    }

    private fun getViewForMode(mode: CenterMode): View = when (mode) {
        CenterMode.BMW -> viewCenterBmw
        CenterMode.MEDIA -> viewCenterMedia
        CenterMode.RADIO -> viewCenterRadio
        CenterMode.MAP -> viewCenterWaze
        CenterMode.ENERGY -> viewCenterEnergy
        CenterMode.NOTIFICATION -> viewCenterNotification
    }

    private fun cycleCenterMode() {
        val previousMode = currentMode
        if (previousMode == CenterMode.NOTIFICATION) {
            if (VoiceMessagePlayer.isPlaying() || VoiceMessagePlayer.getCurrentFile() != null) {
                VoiceMessagePlayer.stop()
                resumeOtherPlayersAfterVoiceMessage()
            }
        }
        currentMode = when (currentMode) {
            CenterMode.BMW -> CenterMode.NOTIFICATION
            CenterMode.NOTIFICATION -> CenterMode.MEDIA
            CenterMode.MEDIA -> CenterMode.MAP
            CenterMode.RADIO -> CenterMode.MAP
            CenterMode.MAP -> CenterMode.ENERGY
            CenterMode.ENERGY -> CenterMode.BMW
        }
        updateCenterModeUI(animateFrom = previousMode)

        if (currentMode == CenterMode.MEDIA && !isYouTubeWebMode && activeMediaSource == ActiveMediaSource.EXTERNAL_APP) {
            ExternalMediaManager.syncActiveSessions(this)
        }

        if (currentMode == CenterMode.MEDIA && !isYouTubeWebMode && activeMediaSource == ActiveMediaSource.EXTERNAL_APP && !isNotificationAccessGranted()) {
            promptNotificationAccess()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun promptNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("Acces Notificari Media")
            .setMessage("Pentru a afisa playerul YouTube Music in Cercul Central, acordati permisiunea de citire a notificarilor.")
            .setPositiveButton("Setari") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Deschideti Setari -> Notificari", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Mai tarziu", null)
            .show()
    }

    private fun isMonochromeBitmap(bmp: Bitmap): Boolean {
        return try {
            val w = bmp.width
            val h = bmp.height
            for (step in 0 until 12) {
                val x = (step * w / 12).coerceIn(0, w - 1)
                val y = (step * h / 12).coerceIn(0, h - 1)
                val pixel = bmp.getPixel(x, y)
                val alpha = (pixel shr 24) and 0xff
                if (alpha > 30) {
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    if (Math.abs(r - g) > 15 || Math.abs(r - b) > 15 || Math.abs(g - b) > 15) {
                        return false
                    }
                }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun updateWazeUI(data: WazeNavData) {
        centerWazeDistance.text = data.distance
        centerWazeRoad.text = data.road
        centerWazeEta.text = data.eta

        tvNavSourceLabel?.text = "NAVIGARE ${data.sourceApp}"

        if (data.iconBitmap != null) {
            centerNavIconImg?.setImageBitmap(data.iconBitmap)
            if (isMonochromeBitmap(data.iconBitmap)) {
                centerNavIconImg?.setColorFilter(ContextCompat.getColor(this, R.color.bmw_amber))
            } else {
                centerNavIconImg?.clearColorFilter()
            }
            centerNavIconImg?.visibility = View.VISIBLE
            centerWazeIcon.visibility = View.GONE
        } else {
            centerNavIconImg?.visibility = View.GONE
            centerWazeIcon.visibility = View.VISIBLE
            centerWazeIcon.text = data.directionSymbol ?: "↑"
        }
    }

    private fun updateCenterModeUI(animateFrom: CenterMode? = null) {
        viewCenterWaze.visibility = View.GONE
        viewCenterSpeedo.visibility = View.GONE

        // Mentine youtubeCircleTouchOverlay activ peste WebView cat timp e docat in cerc,
        // astfel incat WebView-ul invizibil din fundal sa nu fure niciodata click-urile!
        youtubeCircleTouchOverlay.visibility =
            if (isYouTubeWebMode && isYouTubeDockedInCircle) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val allModes = CenterMode.values()

        if (animateFrom != null && animateFrom != currentMode) {
            // Asigura ca ecranele care nu participa la tranzitie sunt ascunse (sau alpha 0 pt YouTube WebView)
            for (mode in allModes) {
                if (mode != animateFrom && mode != currentMode) {
                    val v = getViewForMode(mode)
                    v.animate().cancel()
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.rotation = 0f
                    v.translationX = 0f
                    v.translationY = 0f
                    if (mode == CenterMode.MEDIA && isYouTubeWebMode && isYouTubeDockedInCircle) {
                        v.visibility = View.VISIBLE
                        v.alpha = 0f
                    } else {
                        v.visibility = View.GONE
                        v.alpha = 1f
                    }
                }
            }

            // Puls tactil pe inelul cromat al Cercului Central (stil iDrive controller)
            centerCircle.animate().cancel()
            centerCircle.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(110L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    centerCircle.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(230L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
                .start()

            // Ecranul care iese: rotatie fina spre stanga + micsorare + fade out
            val outView = getViewForMode(animateFrom)
            outView.animate().cancel()
            outView.visibility = View.VISIBLE
            outView.alpha = 1f
            outView.scaleX = 1f
            outView.scaleY = 1f
            outView.rotation = 0f
            outView.animate()
                .alpha(0f)
                .scaleX(0.76f)
                .scaleY(0.76f)
                .rotation(-22f)
                .setDuration(250L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    outView.scaleX = 1f
                    outView.scaleY = 1f
                    outView.rotation = 0f
                    outView.translationX = 0f
                    outView.translationY = 0f
                    if (animateFrom == CenterMode.MEDIA && isYouTubeWebMode && isYouTubeDockedInCircle && currentMode != CenterMode.MEDIA) {
                        outView.visibility = View.VISIBLE
                        outView.alpha = 0f
                    } else if (currentMode != animateFrom) {
                        outView.visibility = View.GONE
                        outView.alpha = 1f
                    }
                }
                .start()

            // Ecranul care intra: apare cu rotatie fina + zoom elastic (Overshoot) + fade in
            val inView = getViewForMode(currentMode)
            inView.animate().cancel()
            inView.visibility = View.VISIBLE
            inView.alpha = 0f
            inView.scaleX = 0.76f
            inView.scaleY = 0.76f
            inView.rotation = 22f
            inView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setDuration(340L)
                .setInterpolator(OvershootInterpolator(1.15f))
                .start()
        } else {
            for (mode in allModes) {
                val v = getViewForMode(mode)
                v.animate().cancel()
                v.scaleX = 1f
                v.scaleY = 1f
                v.rotation = 0f
                v.translationX = 0f
                v.translationY = 0f
                if (mode == currentMode) {
                    v.visibility = View.VISIBLE
                    v.alpha = 1f
                } else if (mode == CenterMode.MEDIA && isYouTubeWebMode && isYouTubeDockedInCircle) {
                    // Mentine viewCenterMedia activ cu alpha 0 in spate pentru ca WebView sa nu opreasca redarea video/audio
                    v.visibility = View.VISIBLE
                    v.alpha = 0f
                } else {
                    v.visibility = View.GONE
                    v.alpha = 1f
                }
            }
        }

        
        if (currentMode == CenterMode.ENERGY) {
            energyHandler.removeCallbacks(energyPollRunnable)
            energyPollRunnable.run()
        } else {
            energyHandler.removeCallbacks(energyPollRunnable)
        }

        if (currentMode == CenterMode.NOTIFICATION) {
            updateNotificationCenterView()
        } else {
            notifDismissHandler.removeCallbacks(notifDismissRunnable)
        }

        if (currentMode == CenterMode.RADIO) {
            currentMode = CenterMode.MEDIA
            activeMediaSource = ActiveMediaSource.RADIO
        }

        when (currentMode) {
            CenterMode.RADIO, CenterMode.MEDIA -> {
                if (controller?.isPlaying == true && activeMediaSource != ActiveMediaSource.LOCAL_PLAYER && !isYouTubeWebMode && !ExternalMediaManager.currentData.isPlaying) {
                    activeMediaSource = ActiveMediaSource.RADIO
                }
                when {
                    isYouTubeWebMode || activeMediaSource == ActiveMediaSource.YOUTUBE_WEB -> {
                        updatePlayPauseIcon(isYouTubeWebPlaying)
                        centerMediaInfoContainer.visibility = View.VISIBLE
                    }
                    activeMediaSource == ActiveMediaSource.RADIO || controller?.isPlaying == true -> {
                        updatePlayPauseIcon(controller?.isPlaying == true)
                        applyRadioArtworkToCenterMedia(controller)
                    }
                    activeMediaSource == ActiveMediaSource.LOCAL_PLAYER -> {
                        updatePlayPauseIcon(LocalMusicPlayer.isPlaying)
                        val localTrack = LocalMusicPlayer.currentTrack
                        if (localTrack != null) {
                            val bmp = LocalMusicPlayer.loadArtwork(this, localTrack)
                            applyMusicArtworkToCenterMedia(bmp)
                        } else {
                            applyMusicArtworkToCenterMedia(null)
                        }
                    }
                    else -> {
                        val extData = ExternalMediaManager.currentData
                        updatePlayPauseIcon(extData.isPlaying)
                        val croppedBmp = if (extData.title.isNotEmpty() && extData.artBitmap != null) {
                            LocalMusicPlayer.cropSolidBorders(extData.artBitmap)
                        } else {
                            null
                        }
                        applyMusicArtworkToCenterMedia(croppedBmp)
                    }
                }
            }
            else -> {
                val isAnyPlaying = (controller?.isPlaying == true) ||
                        (isYouTubeWebMode && isYouTubeWebPlaying) ||
                        LocalMusicPlayer.isPlaying ||
                        ExternalMediaManager.currentData.isPlaying
                updatePlayPauseIcon(isAnyPlaying)
            }
        }
        updateNowPlayingBar()
        updateCenterMediaPlayerBadge()
    }

    private fun applyMusicArtworkToCenterMedia(bmp: Bitmap?) {
        if (!::centerMediaThumbnail.isInitialized) return
        centerMediaThumbnail.setPadding(0, 0, 0, 0)
        centerMediaThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        centerMediaThumbnail.setBackgroundColor(Color.TRANSPARENT)
        if (bmp != null) {
            centerMediaThumbnail.setImageBitmap(bmp)
        } else {
            centerMediaThumbnail.setImageResource(R.drawable.ic_no_player_active)
        }
        centerMediaThumbnail.visibility = View.VISIBLE
        centerMediaScrim.visibility = View.GONE
        centerMediaInfoContainer.visibility = View.VISIBLE
    }

    private fun applyRadioArtworkToCenterMedia(ctrl: MediaController?) {
        if (!::centerMediaThumbnail.isInitialized) return
        centerMediaThumbnail.setBackgroundResource(R.drawable.bg_circle_clip)
        centerMediaThumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
        val artBytes = ctrl?.mediaMetadata?.artworkData
        val bmp = if (artBytes != null) BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) else null
        if (bmp != null) {
            val padH = (38 * resources.displayMetrics.density).toInt()
            val padTop = (26 * resources.displayMetrics.density).toInt()
            val padBot = (64 * resources.displayMetrics.density).toInt()
            centerMediaThumbnail.setPadding(padH, padTop, padH, padBot)
            centerMediaThumbnail.setImageBitmap(bmp)
        } else {
            val padH = (44 * resources.displayMetrics.density).toInt()
            val padTop = (30 * resources.displayMetrics.density).toInt()
            val padBot = (68 * resources.displayMetrics.density).toInt()
            centerMediaThumbnail.setPadding(padH, padTop, padH, padBot)
            centerMediaThumbnail.setImageResource(R.drawable.ic_gauge_radio)
        }
        centerMediaThumbnail.visibility = View.VISIBLE
        centerMediaScrim.visibility = View.GONE
        centerMediaInfoContainer.visibility = View.VISIBLE
    }

    private fun updateCenterMediaPlayerBadge() {
        if (!::centerMediaPlayerBadge.isInitialized) return
        if (currentMode != CenterMode.MEDIA) {
            centerMediaPlayerBadge.visibility = View.GONE
            return
        }

        when {
            activeMediaSource == ActiveMediaSource.RADIO || controller?.isPlaying == true -> {
                ivCenterMediaPlayerIcon.setImageResource(R.drawable.ic_gauge_radio)
                tvCenterMediaPlayerName.text = "RADIO"
                centerMediaPlayerBadge.visibility = View.VISIBLE
            }
            activeMediaSource == ActiveMediaSource.LOCAL_PLAYER -> {
                ivCenterMediaPlayerIcon.setImageResource(R.drawable.ic_music_player_badge)
                tvCenterMediaPlayerName.text = "MUSIC PLAYER"
                centerMediaPlayerBadge.visibility = View.VISIBLE
            }
            activeMediaSource == ActiveMediaSource.YOUTUBE_WEB -> {
                ivCenterMediaPlayerIcon.setImageResource(R.drawable.ic_yt_music_badge)
                tvCenterMediaPlayerName.text = "YOUTUBE"
                centerMediaPlayerBadge.visibility = View.VISIBLE
            }
            else -> {
                val extData = ExternalMediaManager.currentData
                val isYtMusic = extData.packageName.contains("youtube.music") ||
                        extData.sourceName.contains("YouTube", ignoreCase = true)
                val isSpotify = extData.packageName.contains("spotify") ||
                        extData.sourceName.contains("Spotify", ignoreCase = true)

                if (isYtMusic) {
                    ivCenterMediaPlayerIcon.setImageResource(R.drawable.ic_yt_music_badge)
                    tvCenterMediaPlayerName.text = "YT MUSIC"
                } else if (isSpotify) {
                    ivCenterMediaPlayerIcon.setImageResource(R.drawable.ic_music_player_badge)
                    tvCenterMediaPlayerName.text = "SPOTIFY"
                } else if (extData.sourceName.isNotEmpty()) {
                    ivCenterMediaPlayerIcon.setImageResource(R.drawable.ic_music_player_badge)
                    tvCenterMediaPlayerName.text = extData.sourceName.uppercase()
                } else {
                    ivCenterMediaPlayerIcon.setImageResource(R.drawable.ic_yt_music_badge)
                    tvCenterMediaPlayerName.text = "YT MUSIC"
                }
                centerMediaPlayerBadge.visibility = View.VISIBLE
            }
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        isCurrentlyPlayingState = isPlaying
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play)
        }
    }

    private fun updateNowPlayingBar() {
        if (!::bottomTrackTitle.isInitialized || !::bottomTrackArtist.isInitialized) return

        val extData = ExternalMediaManager.currentData
        val isRadioPlaying = (controller?.isPlaying == true)
        val isLocalPlaying = LocalMusicPlayer.isPlaying
        val localTrack = LocalMusicPlayer.currentTrack

        val (titleText, artistText) = when {
            isYouTubeWebMode || activeMediaSource == ActiveMediaSource.YOUTUBE_WEB -> {
                val t = youTubeWebTitle.ifEmpty { "YouTube" }
                val a = youTubeWebArtist.ifEmpty { "YouTube Video" }
                Pair(t, a)
            }
            (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER || isLocalPlaying) && !isRadioPlaying -> {
                val t = localTrack?.title ?: "Media Player"
                val a = localTrack?.artist ?: "Muzică locală"
                Pair(t, a)
            }
            (activeMediaSource == ActiveMediaSource.RADIO || isRadioPlaying || currentMode == CenterMode.RADIO) -> {
                val idx = controller?.currentMediaItemIndex ?: 0
                val stationName = if (idx in stations.indices) stations[idx].name else "BMW RADIO"
                val stationGenre = if (idx in stations.indices) stations[idx].genre else "Radio Live"
                val liveTitle = controller?.mediaMetadata?.title?.toString()?.trim() ?: ""

                if (liveTitle.isNotEmpty() && !liveTitle.equals(stationName, ignoreCase = true)) {
                    if (liveTitle.contains(" - ")) {
                        val parts = liveTitle.split(" - ", limit = 2)
                        val artistPart = parts[0].trim()
                        val songPart = parts[1].trim().ifEmpty { liveTitle }
                        Pair(songPart, "$artistPart • $stationName")
                    } else {
                        Pair(liveTitle, stationName)
                    }
                } else {
                    Pair(stationName, stationGenre.ifEmpty { "Radio Live" })
                }
            }
            (extData.isPlaying || (currentMode == CenterMode.MEDIA && extData.title.isNotEmpty())) && !isRadioPlaying -> {
                val t = extData.title.ifEmpty { "YouTube Music" }
                val a = extData.artist.ifEmpty { extData.sourceName.ifEmpty { "YouTube Music" } }
                Pair(t, a)
            }
            extData.title.isNotEmpty() && activeMediaSource != ActiveMediaSource.LOCAL_PLAYER -> {
                val t = extData.title
                val a = extData.artist.ifEmpty { extData.sourceName.ifEmpty { "YouTube Music" } }
                Pair(t, a)
            }
            localTrack != null -> {
                val t = localTrack.title
                val a = localTrack.artist
                Pair(t, a)
            }
            extData.title.isNotEmpty() -> {
                val t = extData.title
                val a = extData.artist.ifEmpty { extData.sourceName.ifEmpty { "YouTube Music" } }
                Pair(t, a)
            }
            else -> {
                Pair("No Media", "Selectează o piesă...")
            }
        }

        if (bottomTrackTitle.text.toString() != titleText) {
            bottomTrackTitle.text = titleText
            bottomTrackTitle.isSelected = true
        }
        if (bottomTrackArtist.text.toString() != artistText) {
            bottomTrackArtist.text = artistText
        }

        if (::centerMediaTitle.isInitialized) {
            if (centerMediaTitle.text.toString() != titleText) {
                centerMediaTitle.text = titleText
            }
            centerMediaTitle.visibility = View.VISIBLE
            centerMediaTitle.isSelected = true
        }
        if (::centerMediaArtist.isInitialized) {
            if (centerMediaArtist.text.toString() != artistText) {
                centerMediaArtist.text = artistText
            }
            centerMediaArtist.visibility = View.VISIBLE
        }
        if (::centerMediaInfoContainer.isInitialized) {
            centerMediaInfoContainer.visibility = View.VISIBLE
        }
    }

    private fun updateClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        textClock.text = timeFormat.format(now)
    }

    private fun launchNavigationApp() {
        val mapsPackage = "com.google.android.apps.maps"
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(mapsPackage)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Google Maps nu a fost gasit", Toast.LENGTH_SHORT).show()
        }
    }

    private data class MusicOption(
        val title: String,
        val desc: String,
        val iconRes: Int,
        val action: () -> Unit
    )

    private fun showMusicAppPickerDialog() {
        val options = mutableListOf<MusicOption>()

        val hasReVancedYtMusic = packageManager.getLaunchIntentForPackage("app.revanced.android.apps.youtube.music") != null ||
                packageManager.getLaunchIntentForPackage("com.vanced.android.apps.youtube.music") != null

        // 1. YouTube Music
        options.add(MusicOption(
            title = "YouTube Music",
            desc = if (hasReVancedYtMusic) "Redare audio sincronizată în Cercul Central" else "Streaming audio (necesită Premium sau ReVanced pt fundal)",
            iconRes = R.drawable.ic_gauge_music,
            action = { launchSelectedMediaApp(isYouTubeMusic = true) }
        ))

        // 2. Media Player
        options.add(MusicOption(
            title = "Media Player",
            desc = "Player audio integrat",
            iconRes = R.drawable.ic_media_play,
            action = { launchMediaPlayer() }
        ))

        listMusicApps.adapter = object : BaseAdapter() {
            override fun getCount(): Int = options.size
            override fun getItem(position: Int): Any = options[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_music_app, parent, false)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                val opt = options[position]

                val frame = view.findViewById<FrameLayout>(R.id.music_app_icon_frame)
                frame.outlineProvider = circleOutlineProvider
                frame.clipToOutline = true

                val icon = view.findViewById<ImageView>(R.id.music_app_icon)
                val title = view.findViewById<TextView>(R.id.music_app_title)
                val desc = view.findViewById<TextView>(R.id.music_app_desc)

                icon.setImageResource(opt.iconRes)
                title.text = opt.title
                desc.text = opt.desc

                view.setOnClickListener {
                    musicOverlayContainer.visibility = View.GONE
                    focusPlayPauseButton()
                    opt.action()
                }

                return view
            }
        }

        musicOverlayContainer.visibility = View.VISIBLE
        btnCloseMusic.isFocusable = true
        btnCloseMusic.isFocusableInTouchMode = false
        btnCloseMusic.enableInstantClickOnTouch()
    }

    private fun launchMediaPlayer() {
        musicOverlayContainer.visibility = View.GONE
        focusPlayPauseButton()
        controller?.pause()
        stopYouTubeWebPlayer()
        try {
            ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
        } catch (_: Exception) {}
        activeMediaSource = ActiveMediaSource.LOCAL_PLAYER
        currentMode = CenterMode.MEDIA
        updateCenterModeUI()
        showMediaPlayerOverlay(openCurrentPlaying = true)
    }

    fun showMediaPlayerOverlay(openCurrentPlaying: Boolean = true) {
        dismissAllOverlays()
        mediaPlayerOverlayContainer.visibility = View.VISIBLE
        btnCloseMediaPlayer.isFocusable = true
        btnCloseMediaPlayer.isFocusableInTouchMode = false
        btnCloseMediaPlayer.enableInstantClickOnTouch()

        val activePl = LocalMusicPlayer.currentPlaylist
        if (openCurrentPlaying && activePl != null && LocalMusicPlayer.currentTrack != null) {
            showMediaTracksView(activePl)
        } else {
            showMediaPlaylistsView()
        }
    }

    private fun showMediaPlaylistsView() {
        selectedPlaylist = null
        btnMediaBack.visibility = View.GONE
        mediaHeaderTitle.text = "🎵 MEDIA PLAYER"
        btnAddPlaylist.visibility = View.VISIBLE
        btnScanMusic.visibility = View.VISIBLE
        btnMediaShuffle.visibility = View.GONE
        btnMediaRepeat.visibility = View.GONE

        listMediaPlaylists.visibility = View.VISIBLE
        listMediaTracks.visibility = View.GONE

        val playlists = LocalMusicManager.getAllPlaylists()
        if (playlists.isEmpty() && LocalMusicManager.cachedTracks.isEmpty()) {
            mediaEmptyText.visibility = View.VISIBLE
        } else {
            mediaEmptyText.visibility = View.GONE
        }

        listMediaPlaylists.adapter = object : BaseAdapter() {
            override fun getCount(): Int = playlists.size
            override fun getItem(position: Int): Any = playlists[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_media_playlist, parent, false)
                val pl = playlists[position]

                val iconView = view.findViewById<ImageView>(R.id.item_playlist_icon)
                val nameView = view.findViewById<TextView>(R.id.item_playlist_name)
                val subView = view.findViewById<TextView>(R.id.item_playlist_subtitle)
                val btnDelete = view.findViewById<FrameLayout>(R.id.btn_delete_playlist)

                nameView.text = pl.name

                val trackCount = LocalMusicManager.getTracksForPlaylist(pl).size
                subView.text = when {
                    pl.id == LocalMusicManager.ID_ALL_SONGS -> "$trackCount piese • Toate"
                    pl.id == LocalMusicManager.ID_FAVORITES -> "$trackCount piese favorite"
                    pl.isFolder -> "$trackCount piese • Folder"
                    else -> "$trackCount piese • Personal"
                }

                when {
                    pl.id == LocalMusicManager.ID_ALL_SONGS -> {
                        iconView.setImageResource(R.drawable.ic_playlist_music)
                        iconView.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.bmw_amber))
                    }
                    pl.id == LocalMusicManager.ID_FAVORITES -> {
                        iconView.setImageResource(R.drawable.ic_star_filled)
                        iconView.setColorFilter(Color.parseColor("#FFB300"))
                    }
                    pl.isFolder -> {
                        iconView.setImageResource(R.drawable.ic_folder)
                        iconView.setColorFilter(Color.parseColor("#FFB300"))
                    }
                    else -> {
                        iconView.setImageResource(R.drawable.ic_playlist_music)
                        iconView.setColorFilter(Color.WHITE)
                    }
                }

                if (!pl.isSystem && !pl.isFolder) {
                    btnDelete.visibility = View.VISIBLE
                    btnDelete.isFocusable = true
                    btnDelete.isFocusableInTouchMode = false
                    btnDelete.enableInstantClickOnTouch()
                    btnDelete.setOnClickListener {
                        showDeletePlaylistDialog(pl)
                    }
                } else {
                    btnDelete.visibility = View.GONE
                }

                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                view.setOnClickListener {
                    showMediaTracksView(pl)
                }

                return view
            }
        }
    }

    private fun showMediaTracksView(playlist: LocalPlaylist) {
        selectedPlaylist = playlist
        btnMediaBack.visibility = View.VISIBLE
        btnMediaBack.isFocusable = true
        btnMediaBack.isFocusableInTouchMode = false
        btnMediaBack.enableInstantClickOnTouch()

        mediaHeaderTitle.text = playlist.name
        btnAddPlaylist.visibility = View.GONE
        btnScanMusic.visibility = View.GONE
        btnMediaShuffle.visibility = View.VISIBLE
        btnMediaRepeat.visibility = View.VISIBLE

        updateShuffleIcon(LocalMusicPlayer.isShuffleEnabled)
        updateRepeatIcon(LocalMusicPlayer.repeatMode)

        listMediaPlaylists.visibility = View.GONE
        listMediaTracks.visibility = View.VISIBLE

        val tracks = LocalMusicManager.getTracksForPlaylist(playlist)
        if (tracks.isEmpty()) {
            mediaEmptyText.text = "Acest playlist este gol."
            mediaEmptyText.visibility = View.VISIBLE
        } else {
            mediaEmptyText.visibility = View.GONE
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = tracks.size
            override fun getItem(position: Int): Any = tracks[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_media_track, parent, false)
                val track = tracks[position]

                val titleView = view.findViewById<TextView>(R.id.item_track_title)
                val artistDurView = view.findViewById<TextView>(R.id.item_track_artist_duration)
                val playStateIcon = view.findViewById<ImageView>(R.id.item_track_play_state)
                val btnFav = view.findViewById<FrameLayout>(R.id.btn_track_favorite)
                val favIcon = view.findViewById<ImageView>(R.id.item_track_fav_icon)
                val btnAction = view.findViewById<FrameLayout>(R.id.btn_track_playlist_action)
                val actionIcon = view.findViewById<ImageView>(R.id.item_track_playlist_action_icon)

                titleView.text = track.title
                artistDurView.text = "${track.artist} • ${track.formatDuration()}"

                val isThisPlaying = LocalMusicPlayer.currentTrack?.path == track.path
                if (isThisPlaying) {
                    titleView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bmw_amber))
                    playStateIcon.setImageResource(if (LocalMusicPlayer.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play)
                    playStateIcon.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.bmw_amber))
                } else {
                    titleView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bmw_white))
                    playStateIcon.setImageResource(R.drawable.ic_media_play)
                    playStateIcon.setColorFilter(Color.parseColor("#88FFFFFF"))
                }

                val isFav = LocalMusicManager.isFavorite(track)
                favIcon.setImageResource(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_border)
                favIcon.setColorFilter(if (isFav) Color.parseColor("#FFB300") else Color.parseColor("#88FFFFFF"))

                btnFav.isFocusable = true
                btnFav.isFocusableInTouchMode = false
                btnFav.enableInstantClickOnTouch()
                btnFav.setOnClickListener {
                    val newFav = LocalMusicManager.toggleFavorite(this@MainActivity, track)
                    favIcon.setImageResource(if (newFav) R.drawable.ic_star_filled else R.drawable.ic_star_border)
                    favIcon.setColorFilter(if (newFav) Color.parseColor("#FFB300") else Color.parseColor("#88FFFFFF"))
                    Toast.makeText(this@MainActivity, if (newFav) "Adăugat la Favorite ⭐" else "Șters din Favorite", Toast.LENGTH_SHORT).show()
                }

                val isCustomPl = !playlist.isSystem && !playlist.isFolder
                if (isCustomPl) {
                    actionIcon.setImageResource(R.drawable.ic_delete)
                    actionIcon.setColorFilter(Color.parseColor("#FF5555"))
                } else {
                    actionIcon.setImageResource(R.drawable.ic_add)
                    actionIcon.setColorFilter(Color.WHITE)
                }

                btnAction.isFocusable = true
                btnAction.isFocusableInTouchMode = false
                btnAction.enableInstantClickOnTouch()
                btnAction.setOnClickListener {
                    if (isCustomPl) {
                        LocalMusicManager.removeTrackFromPlaylist(this@MainActivity, playlist.id, track)
                        Toast.makeText(this@MainActivity, "Piesă eliminată din playlist", Toast.LENGTH_SHORT).show()
                        showMediaTracksView(playlist)
                    } else {
                        showAddToPlaylistDialog(track)
                    }
                }

                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                view.setOnClickListener {
                    controller?.pause()
                    stopYouTubeWebPlayer()
                    try {
                        ExternalMediaManager.getActiveMusicController(this@MainActivity)?.transportControls?.pause()
                    } catch (_: Exception) {}
                    activeMediaSource = ActiveMediaSource.LOCAL_PLAYER
                    isYouTubeWebMode = false
                    LocalMusicPlayer.playPlaylist(this@MainActivity, playlist, position)
                    val prevMode = currentMode
                    currentMode = CenterMode.MEDIA
                    updateCenterModeUI(if (prevMode != CenterMode.MEDIA) prevMode else null)

                    val trackToPlay = tracks.getOrNull(position)
                    if (trackToPlay != null) {
                        val bmp = LocalMusicPlayer.loadArtwork(this@MainActivity, trackToPlay)
                        applyMusicArtworkToCenterMedia(bmp)
                    }
                    dismissAllOverlays()
                    focusPlayPauseButton()
                }

                return view
            }
        }

        listMediaTracks.adapter = adapter

        val playingIdx = tracks.indexOfFirst { it.path == LocalMusicPlayer.currentTrack?.path }
        if (playingIdx >= 0) {
            listMediaTracks.post {
                focusAndCenterListItem(listMediaTracks, playingIdx)
            }
        }
    }

    private fun refreshMediaOverlayUI() {
        val selPl = selectedPlaylist
        if (selPl != null && listMediaTracks.visibility == View.VISIBLE) {
            showMediaTracksView(selPl)
        } else {
            showMediaPlaylistsView()
        }
    }

    private fun updateShuffleIcon(isShuffle: Boolean) {
        if (::iconMediaShuffle.isInitialized) {
            iconMediaShuffle.setColorFilter(if (isShuffle) ContextCompat.getColor(this, R.color.bmw_amber) else Color.parseColor("#88FFFFFF"))
        }
    }

    private fun updateRepeatIcon(repeatMode: Int) {
        if (!::iconMediaRepeat.isInitialized) return
        when (repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_ALL -> {
                iconMediaRepeat.setImageResource(R.drawable.ic_repeat)
                iconMediaRepeat.setColorFilter(ContextCompat.getColor(this, R.color.bmw_amber))
            }
            androidx.media3.common.Player.REPEAT_MODE_ONE -> {
                iconMediaRepeat.setImageResource(R.drawable.ic_repeat_one)
                iconMediaRepeat.setColorFilter(ContextCompat.getColor(this, R.color.bmw_amber))
            }
            else -> {
                iconMediaRepeat.setImageResource(R.drawable.ic_repeat)
                iconMediaRepeat.setColorFilter(Color.parseColor("#88FFFFFF"))
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this).apply {
            hint = "Nume playlist (ex: Drum lung, Rock...)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("PLAYLIST NOU")
            .setView(input)
            .setPositiveButton("CREEAZĂ") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val pl = LocalMusicManager.createCustomPlaylist(this, name)
                    Toast.makeText(this, "Playlist '${pl.name}' creat!", Toast.LENGTH_SHORT).show()
                    showMediaPlaylistsView()
                }
            }
            .setNegativeButton("ANULEAZĂ", null)
            .show()
    }

    private fun showDeletePlaylistDialog(playlist: LocalPlaylist) {
        AlertDialog.Builder(this)
            .setTitle("ȘTERGERE PLAYLIST")
            .setMessage("Sigur doriți să ștergeți playlistul '${playlist.name}'?")
            .setPositiveButton("ȘTERGE") { _, _ ->
                LocalMusicManager.deleteCustomPlaylist(this, playlist.id)
                Toast.makeText(this, "Playlist șters!", Toast.LENGTH_SHORT).show()
                showMediaPlaylistsView()
            }
            .setNegativeButton("ANULEAZĂ", null)
            .show()
    }

    private fun showAddToPlaylistDialog(track: LocalTrack) {
        val customPls = LocalMusicManager.customPlaylists
        if (customPls.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("ADĂUGARE ÎN PLAYLIST")
                .setMessage("Nu ai creat încă niciun playlist personal. Vrei să creezi unul acum?")
                .setPositiveButton("CREEAZĂ") { _, _ ->
                    showCreatePlaylistDialog()
                }
                .setNegativeButton("ANULEAZĂ", null)
                .show()
            return
        }

        val names = customPls.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ADĂUGARE ÎN PLAYLIST")
            .setItems(names) { _, which ->
                val targetPl = customPls[which]
                val added = LocalMusicManager.addTrackToPlaylist(this, targetPl.id, track)
                if (added) {
                    Toast.makeText(this, "Adăugat în '${targetPl.name}'!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Piesa există deja în '${targetPl.name}'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ANULEAZĂ", null)
            .show()
    }

    private fun launchSelectedMediaApp(isYouTubeMusic: Boolean) {
        // 1. Opreste radioul intern si playerul local
        controller?.pause()
        LocalMusicPlayer.pause()

        if (!isYouTubeMusic) {
            // Deschide playerul video YouTube integrat care reda videoclipul live cropat in Cercul Central
            openYouTubeFullscreen()
            return
        }

        // Daca a fost selectat YouTube Music, oprim playerul video YouTube WebView
        stopYouTubeWebPlayer()
        activeMediaSource = ActiveMediaSource.EXTERNAL_APP

        // 2. Comuta Cercul Central pe modul MEDIA (playerul integrat YT Music)
        currentMode = CenterMode.MEDIA
        updateCenterModeUI()

        val candidatePackages = listOf(
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "com.vanced.android.apps.youtube.music"
        )

        val pm = packageManager
        var launchIntent: Intent? = null
        var resolvedPkg = candidatePackages.first()

        for (pkg in candidatePackages) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                launchIntent = intent
                resolvedPkg = pkg
                break
            }
        }

        // 3. Seteaza pachetul preferat pentru integrarea cu Cercul Central
        ExternalMediaManager.preferredPackage = resolvedPkg

        // Daca in Cercul Central era afisata cealalta aplicatie, actualizeaza imediat afisajul de jos
        if (!ExternalMediaManager.currentData.packageName.equals(resolvedPkg, ignoreCase = true)) {
            applyMusicArtworkToCenterMedia(null)
            bottomTrackTitle.text = "YouTube Music"
            bottomTrackArtist.text = "Selectează o melodie..."
            centerMediaTitle.text = "YouTube Music"
            centerMediaArtist.text = "Selectează o melodie..."
        }

        // Sincronizeaza sesiunea activa daca aplicatia ruleaza deja in fundal
        ExternalMediaManager.syncActiveSessions(this)
        updateNowPlayingBar()

        // Armeaza revenirea automata in BMW Car Launcher imediat ce utilizatorul selecteaza o melodie in YouTube Music
        ExternalMediaManager.armAutoReturnFromYtMusic(this, taskId)

        // 4. Deschide aplicatia selectata
        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } catch (e: Exception) {
                ExternalMediaManager.cancelAutoReturn()
                Toast.makeText(this, "Eroare la deschiderea aplicației", Toast.LENGTH_SHORT).show()
            }
        } else {
            ExternalMediaManager.cancelAutoReturn()
            Toast.makeText(this, "YouTube Music nu este instalat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPhoneDialer() {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nu se poate deschide ecranul telefon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchWhatsApp() {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Eroare la deschiderea WhatsApp", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Aplicația WhatsApp nu este instalată", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAllApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString() }

        gridAllApps.adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_grid, parent, false)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                val info = apps[position]

                val icon = view.findViewById<ImageView>(R.id.app_icon)
                val name = view.findViewById<TextView>(R.id.app_name)

                icon.setImageDrawable(info.loadIcon(pm))
                name.text = info.loadLabel(pm)

                view.setOnClickListener {
                    appsOverlayContainer.visibility = View.GONE
                    focusPlayPauseButton()
                    val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    }
                }

                return view
            }
        }

        appsOverlayContainer.visibility = View.VISIBLE
        btnCloseApps.isFocusable = true
        btnCloseApps.isFocusableInTouchMode = false
        btnCloseApps.enableInstantClickOnTouch()
    }

    private fun initSettingsOverlay() {
        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val dayBrightness = prefs.getInt("day_brightness", 90).coerceIn(5, 100)
        val nightBrightness = prefs.getInt("night_brightness", 10).coerceIn(5, 100)

        seekDayBrightness.progress = dayBrightness
        tvDayBrightnessVal.text = "$dayBrightness%"

        seekNightBrightness.progress = nightBrightness
        tvNightBrightnessVal.text = "$nightBrightness%"

        seekDayBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(5, 100)
                if (clamped != progress) {
                    seekBar?.progress = clamped
                }
                tvDayBrightnessVal.text = "$clamped%"
                if (fromUser) {
                    prefs.edit().putInt("day_brightness", clamped).apply()
                    if (!isNightMode) {
                        applyScreenBrightness(false)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekNightBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(5, 100)
                if (clamped != progress) {
                    seekBar?.progress = clamped
                }
                tvNightBrightnessVal.text = "$clamped%"
                if (fromUser) {
                    prefs.edit().putInt("night_brightness", clamped).apply()
                    if (isNightMode) {
                        applyScreenBrightness(true)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnCloseSettings.setOnClickListener {
            settingsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        settingsOverlayContainer.setOnClickListener {
            settingsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }

        btnOpenNotifSettings.setOnClickListener {
            val isNotifGranted = CarNotificationListenerService.isNotificationAccessGranted(this)
            val isStorageGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
            if (!isNotifGranted) {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Deschide Setări -> Notificări", Toast.LENGTH_SHORT).show()
                }
            } else if (!isStorageGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            } else {
                Toast.makeText(this, "Accesul este deja configurat complet!", Toast.LENGTH_SHORT).show()
            }
        }

        btnCarBtSelect.setOnClickListener {
            showCarBluetoothPicker()
        }
    }

    private fun showSettingsOverlay() {
        dismissAllOverlays()
        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val dayBrightness = prefs.getInt("day_brightness", 90).coerceIn(5, 100)
        val nightBrightness = prefs.getInt("night_brightness", 10).coerceIn(5, 100)

        seekDayBrightness.progress = dayBrightness
        tvDayBrightnessVal.text = "$dayBrightness%"

        seekNightBrightness.progress = nightBrightness
        tvNightBrightnessVal.text = "$nightBrightness%"

        val carBtName = prefs.getString("car_bt_name", null)
        val carBtAddress = prefs.getString("car_bt_address", null)
        if (!carBtAddress.isNullOrEmpty()) {
            tvCarBtStatus.text = if (!carBtName.isNullOrEmpty()) "$carBtName ($carBtAddress)" else carBtAddress
        } else {
            tvCarBtStatus.text = "Neselectat (Apasă pentru selectare)"
        }

        val isNotifGranted = CarNotificationListenerService.isNotificationAccessGranted(this)
        val isStorageGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val allOk = isNotifGranted && isStorageGranted

        tvNotifAccessStatus.text = when {
            allOk -> "Activat (WhatsApp / SMS / Note Vocale)"
            !isNotifGranted -> "Inactiv • Necesită Acces Notificări"
            else -> "Atenție • Necesită Acces Fișiere (Note Vocale)"
        }
        tvNotifAccessStatus.setTextColor(ContextCompat.getColor(this, if (allOk) R.color.bmw_amber else R.color.bmw_gray_text))

        settingsOverlayContainer.visibility = View.VISIBLE
        btnCloseSettings.isFocusable = true
        btnCloseSettings.isFocusableInTouchMode = false
        btnCloseSettings.enableInstantClickOnTouch()
    }

    private fun showCarBluetoothPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBtConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth este dezactivat!", Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = try {
            btAdapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Lipsă permisiune Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val currentCarAddress = prefs.getString("car_bt_address", "") ?: ""

        val items = ArrayList<String>()
        items.add("Niciunul (Dezactivat)")
        var selectedIndex = 0

        bondedDevices.forEachIndexed { index, device ->
            val devName = try { device.name } catch (_: SecurityException) { null } ?: "Dispozitiv necunoscut"
            items.add("$devName (${device.address})")
            if (device.address.equals(currentCarAddress, ignoreCase = true)) {
                selectedIndex = index + 1
            }
        }

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Selectează Bluetooth-ul Mașinii")
        builder.setSingleChoiceItems(items.toTypedArray(), selectedIndex) { dialog, which ->
            if (which == 0) {
                prefs.edit().remove("car_bt_address").remove("car_bt_name").putString("force_display_mode", "auto").apply()
                lastDisconnectTimestamp = 0L
                isCarConnectedState = false
                tvCarBtStatus.text = "Neselectat (Apasă pentru selectare)"
                Toast.makeText(this, "Bluetooth mașină dezactivat", Toast.LENGTH_SHORT).show()
            } else {
                val selectedDevice = bondedDevices[which - 1]
                val devName = try { selectedDevice.name } catch (_: SecurityException) { null } ?: "Dispozitiv necunoscut"
                prefs.edit()
                    .putString("car_bt_address", selectedDevice.address)
                    .putString("car_bt_name", devName)
                    .putString("force_display_mode", "auto")
                    .apply()
                lastDisconnectTimestamp = 0L
                isCarConnectedState = false
                tvCarBtStatus.text = "$devName (${selectedDevice.address})"
                Toast.makeText(this, "Bluetooth mașină setat: $devName", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
            checkBluetoothConnectionAndUpdateOrientation()
        }
        builder.setNegativeButton("Anulează", null)
        builder.show()
    }

    private fun checkAndRequestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBtConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    private fun initBluetoothProfiles() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            btAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = proxy as? BluetoothA2dp
                    } else if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = proxy as? BluetoothHeadset
                    }
                    checkBluetoothConnectionAndUpdateOrientation()
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                    if (profile == BluetoothProfile.HEADSET) headsetProxy = null
                }
            }, BluetoothProfile.A2DP)

            btAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = proxy as? BluetoothHeadset
                    }
                    checkBluetoothConnectionAndUpdateOrientation()
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) headsetProxy = null
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare init profile proxies Bluetooth: ${e.message}")
        }
    }

    private fun isCarBluetoothConnected(): Boolean {
        // Dacă s-a deconectat în ultimele 5 secunde, ignoră orice stare reziduală din proxy-uri/reflection
        if (!isCarConnectedState && android.os.SystemClock.elapsedRealtime() - lastDisconnectTimestamp < 5000L) {
            return false
        }

        if (isCarConnectedState) return true

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val savedCarAddress = prefs.getString("car_bt_address", null)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        try {
            if (!savedCarAddress.isNullOrEmpty()) {
                val a2dpConn = a2dpProxy?.connectedDevices?.any { it.address.equals(savedCarAddress, ignoreCase = true) } == true
                val headsetConn = headsetProxy?.connectedDevices?.any { it.address.equals(savedCarAddress, ignoreCase = true) } == true
                val gattConn = try {
                    btManager?.getConnectedDevices(BluetoothProfile.GATT)?.any { it.address.equals(savedCarAddress, ignoreCase = true) } == true
                } catch (_: Exception) { false }

                var devIsConnected = false
                try {
                    val remoteDev = btAdapter.getRemoteDevice(savedCarAddress)
                    val method = remoteDev.javaClass.getMethod("isConnected")
                    devIsConnected = (method.invoke(remoteDev) as? Boolean) == true
                } catch (_: Exception) {}

                Log.d("MainActivity", "isCarBluetoothConnected check for $savedCarAddress: a2dp=$a2dpConn, hfp=$headsetConn, gatt=$gattConn, isConnected=$devIsConnected")
                if (a2dpConn || headsetConn || gattConn || devIsConnected) {
                    isCarConnectedState = true
                    return true
                }
                return false
            }

            // Fallback dacă nu este selectat explicit un dispozitiv: detectează BMW / iDrive / ESP32 doar prin profiluri active A2DP/Headset
            val connectedA2dp = a2dpProxy?.connectedDevices ?: emptyList()
            val connectedHeadset = headsetProxy?.connectedDevices ?: emptyList()
            val allConnected = (connectedA2dp + connectedHeadset).distinctBy { it.address }
            for (dev in allConnected) {
                val name = try { dev.name } catch (_: Exception) { null } ?: ""
                if (name.contains("BMW", ignoreCase = true) || name.contains("iDrive", ignoreCase = true) || name.contains("ESP32", ignoreCase = true)) {
                    isCarConnectedState = true
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare verificare conexiune Bluetooth: ${e.message}")
        }

        return false
    }

    fun stopAllAudioPlayers() {
        try {
            sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(packageName))
        } catch (_: Exception) {}
        try {
            controller?.pause()
            controller?.stop()
        } catch (_: Exception) {}
        try {
            if (isYouTubeWebMode && isYouTubeWebPlaying) {
                youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
                isYouTubeWebPlaying = false
            }
        } catch (_: Exception) {}
        try {
            ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
        } catch (_: Exception) {}
        try {
            VoiceMessagePlayer.stop()
        } catch (_: Exception) {}
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
        try {
            updatePlayPauseIcon(false)
        } catch (_: Exception) {}
    }

    fun wakeScreenAndShowOverLockscreen() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    fun clearShowWhenLocked() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(false)
                setTurnScreenOn(false)
            } else {
                @Suppress("DEPRECATION")
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.w("MainActivity", "clearShowWhenLocked warning: ${e.message}")
        }
    }

    private var lastRadioStartTimestamp = 0L

    fun startRadioPlayback() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRadioStartTimestamp < 2500L) return
        lastRadioStartTimestamp = now
        try {
            val ctrl = controller
            if (ctrl != null) {
                if (!ctrl.isPlaying && currentMode != CenterMode.MEDIA && !ExternalMediaManager.currentData.isPlaying) {
                    ctrl.play()
                    Log.i("MainActivity", "Radio intern pornit via controller la conectare la mașină")
                }
            } else {
                val radioIntent = Intent(this, RadioService::class.java).apply {
                    action = RadioService.ACTION_START_PLAY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(radioIntent)
                } else {
                    startService(radioIntent)
                }
                Log.i("MainActivity", "RadioService pornit cu ACTION_START_PLAY la conectare la mașină")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare la startRadioPlayback: ${e.message}", e)
        }
    }

    fun checkBluetoothConnectionAndUpdateOrientation() {
        // Landscape permanent
    }

    fun lockScreenIfPossible() {
        runOnUiThread {
            try {
                val locked = BMWAccessibilityService.lockScreen()
                if (!locked) {
                    Log.d("MainActivity", "lockScreen via Accessibility returned false (serviciul nu este activat de utilizator)")
                } else {
                    Log.i("MainActivity", "Display locked successfully on transition to Portrait mode")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Eroare blocare ecran la comutare in portret: ${e.message}")
            }
        }
    }

    private fun refreshStations() {
        stations = StationManager.getStations(this)
    }

    private fun showStationPickerDialog() {
        refreshStations()
        listRadioStations.adapter = object : BaseAdapter() {
            override fun getCount(): Int = stations.size
            override fun getItem(position: Int): Any = stations[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_radio_station, parent, false)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                val station = stations[position]

                val frame = view.findViewById<FrameLayout>(R.id.radio_logo_frame)
                frame.outlineProvider = circleOutlineProvider
                frame.clipToOutline = true

                val logo = view.findViewById<ImageView>(R.id.radio_station_logo)
                logo.outlineProvider = circleOutlineProvider
                logo.clipToOutline = true

                val name = view.findViewById<TextView>(R.id.radio_station_name)
                val genre = view.findViewById<TextView>(R.id.radio_station_genre)
                val actionIcon = view.findViewById<ImageView>(R.id.radio_station_action_icon)

                name.text = station.name
                genre.text = station.genre.ifEmpty { "Live Radio" }

                val isCurrentlyPlaying = (controller?.isPlaying == true && controller?.currentMediaItemIndex == position)
                if (isCurrentlyPlaying) {
                    actionIcon.setImageResource(R.drawable.ic_media_pause)
                } else {
                    actionIcon.setImageResource(R.drawable.ic_media_play)
                }

                val btnEdit = view.findViewById<FrameLayout>(R.id.btn_edit_station)
                btnEdit.isFocusable = true
                btnEdit.isFocusableInTouchMode = false
                btnEdit.enableInstantClickOnTouch()
                btnEdit.setOnClickListener {
                    showEditStationDialog(position, station)
                }

                val btnDelete = view.findViewById<FrameLayout>(R.id.btn_delete_station)
                btnDelete.isFocusable = true
                btnDelete.isFocusableInTouchMode = false
                btnDelete.enableInstantClickOnTouch()
                btnDelete.setOnClickListener {
                    showDeleteStationDialog(position, station.name)
                }

                view.setOnClickListener {
                    radioOverlayContainer.visibility = View.GONE
                    focusPlayPauseButton()
                    LocalMusicPlayer.pause()
                    stopYouTubeWebPlayer()
                    activeMediaSource = ActiveMediaSource.RADIO
                    controller?.let {
                        try {
                            ExternalMediaManager.currentData.mediaController?.transportControls?.pause()
                        } catch (_: Exception) {}
                        it.seekToDefaultPosition(position)
                        it.play()
                    }
                    currentMode = CenterMode.MEDIA
                    updateCenterModeUI()
                }

                return view
            }
        }
        radioOverlayContainer.visibility = View.VISIBLE
        btnCloseRadio.isFocusable = true
        btnCloseRadio.isFocusableInTouchMode = false
        btnCloseRadio.enableInstantClickOnTouch()
    }

    private fun showEditStationDialog(position: Int, station: RadioStation) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val nameInput = EditText(this).apply {
            hint = "Nume post (ex: Rock FM)"
            setText(station.name)
        }
        val genreInput = EditText(this).apply {
            hint = "Gen (ex: Classic Rock)"
            setText(station.genre)
        }
        val urlInput = EditText(this).apply {
            hint = "URL Stream (http/https...)"
            setText(station.url)
        }
        val logoInput = EditText(this).apply {
            hint = "URL Siglă (opțional)"
            setText(station.logoUrl)
        }

        dialogLayout.addView(nameInput)
        dialogLayout.addView(genreInput)
        dialogLayout.addView(urlInput)
        dialogLayout.addView(logoInput)

        AlertDialog.Builder(this)
            .setTitle("Editează Postul Radio")
            .setView(dialogLayout)
            .setPositiveButton("Salvează") { _, _ ->
                val name = nameInput.text.toString().trim()
                val genre = genreInput.text.toString().trim().ifEmpty { "Live Radio" }
                val url = urlInput.text.toString().trim()
                val logo = logoInput.text.toString().trim()

                if (name.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    if (position in 0 until stations.size) {
                        stations[position] = RadioStation(name, genre, url, logo)
                        StationManager.saveStations(this, stations)
                        refreshStations()
                        reloadServicePlaylist()
                        (listRadioStations.adapter as? BaseAdapter)?.notifyDataSetChanged()
                        Toast.makeText(this, "Postul \"$name\" a fost actualizat", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Completează numele și un URL valid (http/https)!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showDeleteStationDialog(position: Int, stationName: String) {
        AlertDialog.Builder(this)
            .setTitle("Ștergere Post Radio")
            .setMessage("Sigur doriți să ștergeți postul \"$stationName\" din listă?")
            .setPositiveButton("Șterge") { _, _ ->
                if (position in 0 until stations.size) {
                    val wasPlayingThis = (controller?.isPlaying == true && controller?.currentMediaItemIndex == position)
                    if (wasPlayingThis) {
                        try {
                            controller?.pause()
                        } catch (_: Exception) {}
                    }
                    stations.removeAt(position)
                    StationManager.saveStations(this, stations)
                    refreshStations()
                    reloadServicePlaylist()
                    (listRadioStations.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    Toast.makeText(this, "Postul \"$stationName\" a fost șters", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showAddStationDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val nameInput = EditText(this).apply { hint = "Nume post (ex: Guerilla)" }
        val genreInput = EditText(this).apply { hint = "Gen (ex: Alternative)" }
        val urlInput = EditText(this).apply { hint = "URL Stream (http/https...)" }
        val logoInput = EditText(this).apply { hint = "URL Sigla (optional)" }

        dialogLayout.addView(nameInput)
        dialogLayout.addView(genreInput)
        dialogLayout.addView(urlInput)
        dialogLayout.addView(logoInput)

        AlertDialog.Builder(this)
            .setTitle("Adauga Post Radio")
            .setView(dialogLayout)
            .setPositiveButton("Salveaza") { _, _ ->
                val name = nameInput.text.toString().trim()
                val genre = genreInput.text.toString().trim().ifEmpty { "Live Radio" }
                val url = urlInput.text.toString().trim()
                val logo = logoInput.text.toString().trim()

                if (name.isNotEmpty() && url.startsWith("http")) {
                    stations.add(RadioStation(name, genre, url, logo))
                    StationManager.saveStations(this, stations)
                    refreshStations()
                    reloadServicePlaylist()
                    (listRadioStations.adapter as? BaseAdapter)?.notifyDataSetChanged()
                } else {
                    Toast.makeText(this, "Completeaza numele si un URL valid (http/https)!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Anuleaza", null)
            .show()
    }

    private fun reloadServicePlaylist() {
        controller?.sendCustomCommand(
            SessionCommand(RadioService.ACTION_RELOAD_STATIONS, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    // Gestiunea tastelor fizice transmise de ESP32-C3 prin BLE HID
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && youtubeFullscreenOverlay.visibility == View.VISIBLE) {
            dockYouTubeToCircle()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_HEADSETHOOK -> {
                answerPhoneCall()
                return true
            }
            KeyEvent.KEYCODE_ENDCALL -> {
                hangupPhoneCall()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                btnNextTrack.performClick()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                btnPrevTrack.performClick()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                btnPlayPause.performClick()
                return true
            }
            KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_F11 -> {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun answerPhoneCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    tm?.acceptRingingCall()
                    return
                }
            }
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            sendOrderedBroadcast(intent, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Preluare apel...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hangupPhoneCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    tm?.endCall()
                    return
                }
            }
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            sendOrderedBroadcast(intent, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Inchidere apel...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getBestLastKnownLocation(): Location? {
        val lm = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return null
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        var bestLocation: Location? = null
        try {
            val providers = lm.getProviders(true)
            for (provider in providers) {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.time > bestLocation.time) {
                    bestLocation = l
                }
            }
        } catch (_: Exception) {}
        return bestLocation
    }

    private fun startGpsUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                1001
            )
            return
        }

        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.let { lm ->
                // Preluam instantaneu ultima locatie din cache-ul sistemului pentru a avea zoom imediat la pornire
                val initialLoc = getBestLastKnownLocation()
                if (initialLoc != null) {
                    lastKnownLocation = initialLoc
                    val speedKmh = if (initialLoc.hasSpeed()) initialLoc.speed * 3.6f else 0f
                }

                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener)
                }
                registerGnssCallback()
                centerSpeedSub.text = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) "CAUTARE SEMNAL GPS..." else "ACTIVATI GPS DIN SETARI"
            }
        } catch (e: Exception) {
            centerSpeedSub.text = "EROARE GPS"
        }
    }

    private fun registerGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        locationManager?.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), gnssStatusCallback)
                    } else {
                        locationManager?.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun unregisterGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
            } catch (_: Exception) {}
        }
    }

    private fun stopGpsUpdates() {
        try {
            unregisterGnssCallback()
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startGpsUpdates()
            } else {
                centerSpeedSub.text = "PERMISIUNE REFUZATA"
            }
        } else if (requestCode == 2001) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                showRecentCallsOverlay()
            }
            // Daca permisiunea muzicii a fost acordata acum, re-scanam biblioteca audio
            val audioPermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            if (audioPermGranted) {
                Thread {
                    LocalMusicManager.scanDeviceTracks(this)
                }.start()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        enableImmersiveMode()
        clockHandler.post(clockRunnable)
        startGpsUpdates()
        
        try {
            val batteryStatusIntent = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryStatusIntent?.let { bIntent ->
                val level = bIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = bIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0 && scale > 0) {
                    val pct = (level * 100) / scale
                    val prefix = if (isCharging) "⚡ " else "🔋 "
                    bottomBatteryIcon.text = "$prefix$pct%"
                }
            }
        } catch (_: Exception) {}

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            registerReceiver(bluetoothReceiver, btFilter)
        } catch (_: Exception) {}
        initBluetoothProfiles()
        checkBluetoothConnectionAndUpdateOrientation()

        WazeNotificationService.refresh()
        ExternalMediaManager.syncActiveSessions(this)

        WazeNavManager.listener = { navData ->
            runOnUiThread {
                updateWazeUI(navData)
            }
        }

        ExternalMediaManager.listener = { data ->
            runOnUiThread {
                if (data.isPlaying) {
                    activeMediaSource = ActiveMediaSource.EXTERNAL_APP
                    if (LocalMusicPlayer.isPlaying) {
                        LocalMusicPlayer.pause()
                    }
                }
                if (activeMediaSource == ActiveMediaSource.LOCAL_PLAYER && LocalMusicPlayer.isPlaying) {
                    return@runOnUiThread
                }
                if (isYouTubeWebMode) {
                    if (data.isPlaying && data.packageName.contains("youtube.music")) {
                        stopYouTubeWebPlayer()
                    } else {
                        return@runOnUiThread
                    }
                }
                if (activeMediaSource != ActiveMediaSource.LOCAL_PLAYER && activeMediaSource != ActiveMediaSource.RADIO) {
                    centerMediaScrim.visibility = View.GONE
                    centerMediaInfoContainer.visibility = View.VISIBLE
                    if (data.title.isNotEmpty()) {
                        val croppedBmp = if (data.artBitmap != null) {
                            LocalMusicPlayer.cropSolidBorders(data.artBitmap)
                        } else {
                            null
                        }
                        applyMusicArtworkToCenterMedia(croppedBmp)
                        if (data.isPlaying) {
                            // Daca un player exterior ruleaza (YouTube sau YouTube Music), opreste radioul
                            controller?.pause()
                            if (currentMode == CenterMode.BMW) {
                                currentMode = CenterMode.MEDIA
                                updateCenterModeUI()
                            } else if (currentMode == CenterMode.MEDIA) {
                                updatePlayPauseIcon(true)
                            }
                        } else if (currentMode == CenterMode.MEDIA) {
                            updatePlayPauseIcon(false)
                        }
                    } else if (!data.isPlaying && currentMode == CenterMode.MEDIA) {
                        updatePlayPauseIcon(false)
                    }
                }
                updateNowPlayingBar()
                updateCenterMediaPlayerBadge()
            }
        }

        val sessionToken = SessionToken(this, ComponentName(this, RadioService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupController()
        }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        val ctrl = controller ?: return
        updateRadioUI(ctrl)

        val inDisconnectCooldown = lastDisconnectTimestamp > 0L &&
                (android.os.SystemClock.elapsedRealtime() - lastDisconnectTimestamp < 10000L)

        if (isCarConnectedState && !inDisconnectCooldown && !ctrl.isPlaying && currentMode != CenterMode.MEDIA && !ExternalMediaManager.currentData.isPlaying) {
            ctrl.play()
        }

        ctrl.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateRadioUI(ctrl)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateRadioUI(ctrl)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    activeMediaSource = ActiveMediaSource.RADIO
                    if (LocalMusicPlayer.isPlaying) {
                        LocalMusicPlayer.pause()
                    }
                }
                updateRadioUI(ctrl)
            }
        })
    }

    private fun updateRadioUI(ctrl: MediaController) {
        if (ctrl.isPlaying) {
            activeMediaSource = ActiveMediaSource.RADIO
        }

        // Butonul de PLAY / PAUSE din bara de jos si coperta in fereastra comuna Media
        if (activeMediaSource == ActiveMediaSource.RADIO || ctrl.isPlaying || currentMode != CenterMode.MEDIA) {
            if (activeMediaSource == ActiveMediaSource.RADIO || ctrl.isPlaying) {
                updatePlayPauseIcon(ctrl.isPlaying)
                if (currentMode == CenterMode.MEDIA) {
                    applyRadioArtworkToCenterMedia(ctrl)
                }
            }
        }

        val artBytes = ctrl.mediaMetadata.artworkData
        if (artBytes != null) {
            val bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            if (bmp != null) {
                val pad = (38 * resources.displayMetrics.density).toInt()
                centerRadioArt.setPadding(pad, pad, pad, pad)
                centerRadioArt.scaleType = ImageView.ScaleType.FIT_CENTER
                centerRadioArt.setImageBitmap(bmp)
            }
        } else {
            val pad = (42 * resources.displayMetrics.density).toInt()
            centerRadioArt.setPadding(pad, pad, pad, pad)
            centerRadioArt.scaleType = ImageView.ScaleType.FIT_CENTER
            centerRadioArt.setImageResource(R.drawable.ic_gauge_radio)
        }

        updateNowPlayingBar()
        updateCenterMediaPlayerBadge()
    }

    override fun onStop() {
        super.onStop()
                try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        WazeNavManager.listener = null
        ExternalMediaManager.listener = null
        stopGpsUpdates()
        clockHandler.removeCallbacks(clockRunnable)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
        if (intent.getBooleanExtra("EXTRA_AUTO_CLOSE", false)) {
            closeLauncher()
            return
        }
        if (intent.getBooleanExtra("EXTRA_CAR_BT_CONNECTED", false)) {
            Log.i("MainActivity", "onNewIntent: EXTRA_CAR_BT_CONNECTED primit, trecere in mod masina si pornire radio")
            isCarConnectedState = true
            lastDisconnectTimestamp = 0L
            checkBluetoothConnectionAndUpdateOrientation()
            startRadioPlayback()
        }
        if (intent.getBooleanExtra("EXTRA_F12_RETURN", false)) {
            Log.i("MainActivity", "onNewIntent: EXTRA_F12_RETURN primit! Revenire la ecranul principal BMW...")
            dismissAllOverlays()
            if (currentMode != CenterMode.BMW) {
                val prev = currentMode
                currentMode = CenterMode.BMW
                updateCenterModeUI(animateFrom = prev)
            }
        }
        if (intent.getBooleanExtra("FROM_YT_MUSIC_AUTO_RETURN", false)) {
            LocalMusicPlayer.pause()
            activeMediaSource = ActiveMediaSource.EXTERNAL_APP
            currentMode = CenterMode.MEDIA
            updateCenterModeUI()
            ExternalMediaManager.syncActiveSessions(this)
        }
        handleNotificationTestIntent(intent)
    }

    private fun handleNotificationTestIntent(intent: Intent?) {
        if (intent != null && intent.hasExtra("TEST_NOTIF_SENDER")) {
            val sender = intent.getStringExtra("TEST_NOTIF_SENDER") ?: "Contact"
            val text = intent.getStringExtra("TEST_NOTIF_TEXT") ?: "Salut! Cum merge drumul?"
            val isVoice = intent.getBooleanExtra("TEST_NOTIF_VOICE", false)
            val appName = intent.getStringExtra("TEST_NOTIF_APP") ?: "WhatsApp"
            val pkg = intent.getStringExtra("TEST_NOTIF_PKG") ?: "com.whatsapp"
            displayNotification(CarNotification(
                key = "test_${System.currentTimeMillis()}",
                packageName = pkg,
                appName = appName,
                sender = sender,
                message = text,
                timestamp = System.currentTimeMillis(),
                isVoiceMessage = isVoice,
                contentIntent = null,
                appIcon = null
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        currentTaskId = taskId
        ExternalMediaManager.cancelAutoReturn()
        ExternalMediaManager.syncActiveSessions(this)
        applyScreenBrightness(currentBrightnessMode)
                registerGnssCallback()
        focusPlayPauseButton()
        if (currentMode == CenterMode.ENERGY) {
            energyHandler.removeCallbacks(energyPollRunnable)
            energyPollRunnable.run()
        }

        // Reconectare / Abonare la notificări
        CarNotificationHub.onNotificationReceived = { notif ->
            displayNotification(notif)
        }
        CarNotificationHub.onUnreadCountChanged = { _ ->
        }
        CarNotificationHub.onNotificationsListChanged = { _ ->
            checkAndShowPendingNotifications()
        }

        // Asigură că serviciul NotificationListenerService este conectat la sistem și sincronizat
        if (CarNotificationListenerService.isNotificationAccessGranted(this)) {
            CarNotificationListenerService.rebindService(this)
            CarNotificationListenerService.instance?.syncActiveNotificationsCount()
            checkAndShowPendingNotifications()
        }
    }

    fun openNotificationShade() {
        try {
            @Suppress("WrongConstant")
            val sbservice = getSystemService("statusbar")
            val statusbarManager = Class.forName("android.app.StatusBarManager")
            val expands = statusbarManager.getMethod("expandNotificationsPanel")
            expands.invoke(sbservice)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error expanding notification panel: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        VoiceMessagePlayer.stop()
        energyHandler.removeCallbacks(energyPollRunnable)
        currentTaskId = taskId
        ExternalMediaManager.onLauncherPaused(this, taskId)
                unregisterGnssCallback()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
            }

    override fun onLowMemory() {
        super.onLowMemory()
            }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        VoiceMessagePlayer.stop()
        VoiceMessagePlayer.listener = null
        energyHandler.removeCallbacksAndMessages(null)
                try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        CarNotificationHub.onNotificationReceived = null
        CarNotificationHub.onUnreadCountChanged = null
        notifDismissHandler.removeCallbacksAndMessages(null)
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            a2dpProxy?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { btAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (_: Exception) {}
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val ro = Locale("ro", "RO")
                    val res = textToSpeech?.setLanguage(ro)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        textToSpeech?.language = Locale.getDefault()
                    }
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                    textToSpeech?.setAudioAttributes(audioAttributes)
                    textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            runOnUiThread {
                                notifDismissHandler.removeCallbacks(notifDismissRunnable)
                            }
                        }
                        override fun onDone(utteranceId: String?) {
                            runOnUiThread {
                                resumeOtherPlayersAfterVoiceMessage()
                                resetNotifDismissTimer()
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            runOnUiThread {
                                resumeOtherPlayersAfterVoiceMessage()
                                resetNotifDismissTimer()
                            }
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "TTS init exception: ${e.message}")
        }
    }

    private fun speakNotification(notif: CarNotification) {
        try {
            val textToSpeak = if (notif.isVoiceMessage) {
                "Mesaj vocal primit de la ${notif.sender}"
            } else if (notif.packageName.contains("gm", ignoreCase = true) || notif.appName.contains("Gmail", ignoreCase = true)) {
                "Email de la ${notif.sender}: ${notif.message}"
            } else if (notif.packageName.contains("homeassistant", ignoreCase = true) || notif.appName.contains("Home Assistant", ignoreCase = true)) {
                if (notif.sender.equals("Home Assistant", ignoreCase = true)) {
                    "Notificare Home Assistant: ${notif.message}"
                } else {
                    "Notificare Home Assistant, ${notif.sender}: ${notif.message}"
                }
            } else {
                "Mesaj de la ${notif.sender}: ${notif.message}"
            }
            pauseOtherPlayersForVoiceMessage()
            textToSpeech?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "CarNotifUtterance")
        } catch (e: Exception) {
            Log.e("MainActivity", "TTS speak exception: ${e.message}")
            resumeOtherPlayersAfterVoiceMessage()
        }
    }

    private fun updateNotificationCenterView() {
        runOnUiThread {
            viewCenterNotification.translationX = 0f
            viewCenterNotification.translationY = 0f
            viewCenterNotification.scaleX = 1f
            viewCenterNotification.scaleY = 1f
            viewCenterNotification.alpha = 1f
            viewCenterNotification.visibility = View.VISIBLE

            val unreadList = CarNotificationHub.unreadNotifications
            if (unreadList.isEmpty()) {
                activeNotification = null
                layoutNotifEmptyState.visibility = View.VISIBLE
                notifHeaderRow.visibility = View.GONE
                notifCenterContent.visibility = View.GONE
                notifBottomActions.visibility = View.GONE
                btnNotifPrev.visibility = View.GONE
                btnNotifNext.visibility = View.GONE
                tvNotifCounter?.visibility = View.GONE

                centerCircle.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                centerCircle.isFocusable = true
                centerCircle.isFocusableInTouchMode = true
                centerCircle.post {
                    centerCircle.requestFocus()
                }
            } else {
                layoutNotifEmptyState.visibility = View.GONE
                notifHeaderRow.visibility = View.VISIBLE
                notifCenterContent.visibility = View.VISIBLE
                notifBottomActions.visibility = View.VISIBLE
                btnNotifClose.visibility = View.VISIBLE

                val currentKey = activeNotification?.key
                val notif = unreadList.firstOrNull { it.key == currentKey } ?: unreadList.first()
                activeNotification = notif

                // Săgețile sunt vizibile permanent pe ecranul de notificări
                btnNotifPrev.visibility = View.VISIBLE
                btnNotifNext.visibility = View.VISIBLE
                btnNotifPrev.alpha = 1.0f
                btnNotifNext.alpha = 1.0f
                bindNotificationData(notif)
            }
        }
    }

    private fun navigateToPreviousNotification() {
        navigateNotificationCarousel(direction = 1)
    }

    private fun navigateToNextNotification() {
        navigateNotificationCarousel(direction = -1)
    }

    private fun navigateNotificationCarousel(direction: Int) {
        val current = activeNotification ?: return
        val unreadList = CarNotificationHub.unreadNotifications
        if (unreadList.isEmpty()) return

        // Feedback vizual (nudge/bounce stânga-dreapta) dacă există o singură notificare
        if (unreadList.size <= 1) {
            val nudgeDistance = if (direction < 0) -22f else 22f
            viewCenterNotification.animate().cancel()
            viewCenterNotification.animate()
                .translationX(nudgeDistance)
                .setDuration(70)
                .withEndAction {
                    viewCenterNotification.animate()
                        .translationX(0f)
                        .setDuration(120)
                        .start()
                }
                .start()
            return
        }

        val currentIndex = unreadList.indexOfFirst { it.key == current.key }
        val targetIndex = if (direction < 0) {
            if (currentIndex >= 0 && currentIndex < unreadList.size - 1) currentIndex + 1 else 0
        } else {
            if (currentIndex > 0) currentIndex - 1 else unreadList.size - 1
        }

        val nextNotif = unreadList[targetIndex]
        activeNotification = nextNotif
        if (VoiceMessagePlayer.isPlaying() || VoiceMessagePlayer.getCurrentFile() != null) {
            VoiceMessagePlayer.stop()
            resumeOtherPlayersAfterVoiceMessage()
        }

        val focusedBefore = currentFocus
        val keepNext = ::btnNotifNext.isInitialized && (focusedBefore == btnNotifNext || btnNotifNext.hasFocus())
        val keepPrev = ::btnNotifPrev.isInitialized && (focusedBefore == btnNotifPrev || btnNotifPrev.hasFocus())

        val width = (viewCenterNotification.width.toFloat().takeIf { it > 0f } ?: 400f)
        val slideOutDistance = width * if (direction < 0) -0.35f else 0.35f

        viewCenterNotification.animate().cancel()
        viewCenterNotification.animate()
            .translationX(slideOutDistance)
            .alpha(0f)
            .setDuration(110)
            .withEndAction {
                bindNotificationData(nextNotif)
                if (keepNext && ::btnNotifNext.isInitialized && btnNotifNext.visibility == View.VISIBLE) {
                    btnNotifNext.requestFocus()
                } else if (keepPrev && ::btnNotifPrev.isInitialized && btnNotifPrev.visibility == View.VISIBLE) {
                    btnNotifPrev.requestFocus()
                }
                viewCenterNotification.translationX = -slideOutDistance
                viewCenterNotification.alpha = 0f
                viewCenterNotification.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(140)
                    .withEndAction {
                        viewCenterNotification.translationX = 0f
                        viewCenterNotification.alpha = 1f
                    }
                    .start()
            }
            .start()
    }

    private fun dismissCurrentNotificationWithAnim() {
        val current = activeNotification ?: return
        if (VoiceMessagePlayer.isPlaying() || VoiceMessagePlayer.getCurrentFile() != null) {
            VoiceMessagePlayer.stop()
            resumeOtherPlayersAfterVoiceMessage()
        }

        viewCenterNotification.animate().cancel()
        viewCenterNotification.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                viewCenterNotification.scaleX = 1f
                viewCenterNotification.scaleY = 1f
                viewCenterNotification.alpha = 1f
                viewCenterNotification.translationX = 0f
                viewCenterNotification.translationY = 0f

                try {
                    CarNotificationListenerService.instance?.cancelNotification(current.key)
                } catch (_: Exception) {}
                CarNotificationHub.removeNotification(current.key)

                updateNotificationCenterView()
            }
            .start()
    }

    private fun checkAndShowPendingNotifications() {
        runOnUiThread {
            if (currentMode == CenterMode.NOTIFICATION) {
                updateNotificationCenterView()
            }
        }
    }

    private fun displayNotification(notif: CarNotification) {
        activeNotification = notif
        VoiceMessagePlayer.stop()
        runOnUiThread {
            if (currentMode != CenterMode.NOTIFICATION) {
                preNotificationMode = currentMode
                currentMode = CenterMode.NOTIFICATION
                updateCenterModeUI(animateFrom = preNotificationMode)
            } else {
                updateNotificationCenterView()
            }
        }
    }

    private fun bindNotificationData(notif: CarNotification) {
        val unreadList = CarNotificationHub.unreadNotifications
        val total = unreadList.size
        val currentIndex = unreadList.indexOfFirst { it.key == notif.key }
        if (total >= 1 && currentIndex >= 0) {
            tvNotifCounter?.visibility = View.VISIBLE
            tvNotifCounter?.text = "${currentIndex + 1} / $total"
        } else {
            tvNotifCounter?.visibility = View.GONE
        }

        // Pictograma aplicației
        if (notif.appIcon != null) {
            ivNotifAppIcon.setImageDrawable(notif.appIcon)
        } else {
            when {
                notif.packageName.contains("whatsapp", ignoreCase = true) -> {
                    ivNotifAppIcon.setImageResource(R.drawable.ic_mask_whatsapp)
                }
                notif.packageName.contains("gm", ignoreCase = true) || notif.packageName.contains("mail", ignoreCase = true) -> {
                    ivNotifAppIcon.setImageResource(R.drawable.ic_email)
                }
                notif.packageName.contains("homeassistant", ignoreCase = true) -> {
                    ivNotifAppIcon.setImageResource(R.drawable.ic_energy_home)
                }
                else -> {
                    ivNotifAppIcon.setImageResource(R.drawable.ic_sms)
                }
            }
        }

        tvNotifAppName.text = notif.appName.uppercase(Locale.getDefault())
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(notif.timestamp))
        tvNotifTime.text = timeStr
        tvNotifSender.text = notif.sender

        if (notif.isVoiceMessage) {
            layoutNotifVoiceBadge.visibility = View.VISIBLE
            tvNotifVoiceLabel.text = "MESAJ VOCAL"
            tvNotifText.text = if (notif.message.isNotBlank()) notif.message else "Atinge Redare pentru a asculta"
        } else {
            layoutNotifVoiceBadge.visibility = View.GONE
            tvNotifText.text = notif.message
        }

        // Butonul 1: DESCHIDE
        tvNotifActionText.text = "DESCHIDE"
        ivNotifActionIcon.setImageResource(R.drawable.ic_media_play)
        btnNotifAction.visibility = View.VISIBLE

        // Butonul 2: REDĂ
        if (notif.isVoiceMessage && VoiceMessagePlayer.isPlaying()) {
            tvNotifSpeakText?.text = "PAUZĂ"
            ivNotifSpeakIcon?.setImageResource(R.drawable.ic_media_pause)
        } else {
            tvNotifSpeakText?.text = "REDĂ"
            ivNotifSpeakIcon?.setImageResource(if (notif.isVoiceMessage) R.drawable.ic_media_play else R.drawable.ic_speaker)
        }
        btnNotifSpeak.visibility = View.VISIBLE

        centerCircle.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        resetNotifDismissTimer()

        btnNotifAction.isFocusable = true
        btnNotifAction.isFocusableInTouchMode = true
        btnNotifAction.post {
            val cur = currentFocus
            if (cur == null || (cur != btnNotifNext && cur != btnNotifPrev && cur != btnNotifSpeak && cur != btnNotifClose && cur != centerCircle)) {
                btnNotifAction.requestFocus()
            }
        }
    }

    private fun resetNotifDismissTimer() {
        notifDismissHandler.removeCallbacks(notifDismissRunnable)
        if (currentMode == CenterMode.NOTIFICATION) {
            notifDismissHandler.postDelayed(notifDismissRunnable, 25000L)
        }
    }

    private fun dismissNotification() {
        notifDismissHandler.removeCallbacks(notifDismissRunnable)
        if (VoiceMessagePlayer.isPlaying() || VoiceMessagePlayer.getCurrentFile() != null) {
            VoiceMessagePlayer.stop()
            resumeOtherPlayersAfterVoiceMessage()
        }
        centerCircle.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (currentMode == CenterMode.NOTIFICATION) {
            currentMode = if (preNotificationMode == CenterMode.NOTIFICATION) CenterMode.BMW else preNotificationMode
            updateCenterModeUI(animateFrom = CenterMode.NOTIFICATION)
        }
    }

    private fun pauseOtherPlayersForVoiceMessage() {
        if (!wasRadioPlayingBeforeVoice && !wasYouTubePlayingBeforeVoice && !wasExternalMediaPlayingBeforeVoice && !wasLocalMusicPlayingBeforeVoice) {
            wasRadioPlayingBeforeVoice = controller?.isPlaying == true
            wasYouTubePlayingBeforeVoice = isYouTubeWebMode && isYouTubeWebPlaying
            wasLocalMusicPlayingBeforeVoice = LocalMusicPlayer.isPlaying
            val extCtrl = ExternalMediaManager.getActiveMusicController(this)
            wasExternalMediaPlayingBeforeVoice = ExternalMediaManager.currentData.isPlaying ||
                    (extCtrl?.playbackState?.state == PlaybackState.STATE_PLAYING)
            Log.d("MainActivity", "pauseOtherPlayers: radio=$wasRadioPlayingBeforeVoice, yt=$wasYouTubePlayingBeforeVoice, local=$wasLocalMusicPlayingBeforeVoice, ext=$wasExternalMediaPlayingBeforeVoice")
        }

        if (wasRadioPlayingBeforeVoice) {
            controller?.pause()
            Log.d("MainActivity", "Radio intern oprit temporar pentru mesaj vocal/audio")
        }

        if (wasLocalMusicPlayingBeforeVoice) {
            LocalMusicPlayer.pause()
            Log.d("MainActivity", "Media Player local oprit temporar pentru mesaj vocal/audio")
        }

        if (wasYouTubePlayingBeforeVoice) {
            youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
            isYouTubeWebPlaying = false
            updatePlayPauseIcon(false)
            Log.d("MainActivity", "YouTube oprit temporar pentru mesaj vocal/audio")
        }

        if (wasExternalMediaPlayingBeforeVoice) {
            try {
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.pause()
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Eroare oprire media externa: ${e.message}")
            }
            Log.d("MainActivity", "Media externa oprita temporar pentru mesaj vocal/audio")
        }
    }

    private fun resumeOtherPlayersAfterVoiceMessage() {
        Log.d("MainActivity", "resumeOtherPlayers: radio=$wasRadioPlayingBeforeVoice, yt=$wasYouTubePlayingBeforeVoice, local=$wasLocalMusicPlayingBeforeVoice, ext=$wasExternalMediaPlayingBeforeVoice")
        if (wasRadioPlayingBeforeVoice) {
            wasRadioPlayingBeforeVoice = false
            if (controller != null) {
                controller?.play()
            } else {
                val serviceIntent = Intent(this, RadioService::class.java).apply {
                    action = RadioService.ACTION_START_PLAY
                }
                startService(serviceIntent)
            }
            Log.d("MainActivity", "Radio intern reluat dupa mesaj vocal")
        } else if (wasLocalMusicPlayingBeforeVoice) {
            wasLocalMusicPlayingBeforeVoice = false
            LocalMusicPlayer.play()
            updatePlayPauseIcon(true)
            Log.d("MainActivity", "Media Player local reluat dupa mesaj vocal")
        } else if (wasYouTubePlayingBeforeVoice) {
            wasYouTubePlayingBeforeVoice = false
            youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = false; var v = document.querySelector('video'); if (v) v.play();", null)
            isYouTubeWebPlaying = true
            updatePlayPauseIcon(true)
            Log.d("MainActivity", "YouTube reluat dupa mesaj vocal")
        } else if (wasExternalMediaPlayingBeforeVoice) {
            wasExternalMediaPlayingBeforeVoice = false
            try {
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.play()
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    val isPlayingNow = ExternalMediaManager.currentData.isPlaying ||
                            (ExternalMediaManager.getActiveMusicController(this)?.playbackState?.state == PlaybackState.STATE_PLAYING)
                    if (!isPlayingNow) {
                        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                    }
                }, 150L)
            } catch (e: Exception) {
                Log.w("MainActivity", "Eroare reluare media externa: ${e.message}")
            }
            Log.d("MainActivity", "Media externa reluata dupa mesaj vocal")
        }
    }

    private fun playVoiceMessageOrFallback(notif: CarNotification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Permite accesul la stocare pentru redarea mesajelor vocale", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {}
        }

        val voiceFile = VoiceMessagePlayer.findLatestVoiceNote(notif.packageName, notif.timestamp)
        if (voiceFile != null && voiceFile.exists()) {
            pauseOtherPlayersForVoiceMessage()
            VoiceMessagePlayer.play(voiceFile)
        } else if (notif.playActionIntent != null) {
            try {
                val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        ActivityOptions.makeBasic().apply {
                            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        }.toBundle()
                    } catch (_: Exception) {
                        ActivityOptions.makeBasic().toBundle()
                    }
                } else {
                    ActivityOptions.makeBasic().toBundle()
                }
                pauseOtherPlayersForVoiceMessage()
                notif.playActionIntent.send(this, 0, null, null, null, null, optionsBundle)
            } catch (e: Exception) {
                Log.e("MainActivity", "Eroare playActionIntent: ${e.message}")
                openNotification(notif)
            }
        } else {
            Toast.makeText(this, "Deschidere conversație...", Toast.LENGTH_SHORT).show()
            openNotification(notif)
        }
    }

    private fun openNotification(notif: CarNotification) {
        Log.i("MainActivity", "Deschidere notificare pentru: ${notif.packageName}, app=${notif.appName}")
        dismissNotification()

        val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle()
            } catch (e: Exception) {
                Log.w("MainActivity", "Eroare setPendingIntentBackgroundActivityStartMode: ${e.message}")
                ActivityOptions.makeBasic().toBundle()
            }
        } else {
            ActivityOptions.makeBasic().toBundle()
        }

        var intentSenderOpened = false
        if (notif.contentIntent != null) {
            try {
                startIntentSender(
                    notif.contentIntent.intentSender,
                    null,
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                    0,
                    optionsBundle
                )
                intentSenderOpened = true
                Log.i("MainActivity", "startIntentSender executat cu succes pentru ${notif.packageName}")
            } catch (e: Exception) {
                Log.w("MainActivity", "startIntentSender a esuat (${e.message}), se incearca direct send()...")
                try {
                    notif.contentIntent.send(this, 0, null, null, null, null, optionsBundle)
                    intentSenderOpened = true
                    Log.i("MainActivity", "contentIntent.send executat cu succes pentru ${notif.packageName}")
                } catch (e2: Exception) {
                    Log.e("MainActivity", "contentIntent.send a esuat: ${e2.message}")
                }
            }
        }

        // Daca nu a reusit prin PendingIntent sau e null (ex. com.android.shell sau PendingIntent anulat), lansam direct pachetul aplicatiei
        if (!intentSenderOpened) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(notif.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(launchIntent)
                    Log.i("MainActivity", "Lansat cu succes prin launchIntent: ${notif.packageName}")
                } else {
                    Log.w("MainActivity", "Nu s-a gasit launch intent pentru: ${notif.packageName}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Eroare la deschiderea fallback a aplicatiei ${notif.packageName}: ${e.message}")
            }
        }
    }

    private fun fetchAndDisplayEnergyData() {
        HomeAssistantManager.fetchEnergyData(this) { data ->
            if (!isInForeground || currentMode != CenterMode.ENERGY) return@fetchEnergyData

            // 1. STÂNGA: Solar & Consum Casă
            val solarText = if (data.solarPower >= 1000f) {
                String.format(java.util.Locale.US, "%.1f kW", data.solarPower / 1000f)
            } else {
                "${data.solarPower.toInt()} W"
            }
            tvEnergySolarPower.text = solarText

            val houseText = if (data.houseLoad >= 1000f) {
                String.format(java.util.Locale.US, "%.1f kW", data.houseLoad / 1000f)
            } else {
                "${data.houseLoad.toInt()} W"
            }
            tvEnergyHouseLoad.text = houseText

            // 2. CENTRU: SOC Baterie
            tvEnergyBatterySoc.text = "${data.batterySoc}%"
            val isCharging = data.batteryPower > 20f
            val isDischarging = data.batteryPower < -20f
            tvEnergyBatteryStatus.text = when {
                isCharging -> "⚡ ÎNCĂRCARE"
                isDischarging -> "🔋 DESCĂRCARE"
                else -> "STANDBY"
            }
            tvEnergyBatteryStatus.setTextColor(when {
                isCharging -> android.graphics.Color.parseColor("#4CAF50")
                isDischarging -> android.graphics.Color.parseColor("#FFA033")
                else -> android.graphics.Color.parseColor("#8E929B")
            })

            // 3. DREAPTA: Tensiune Baterie & Putere Baterie
            tvEnergyBatteryVoltage.text = String.format(java.util.Locale.US, "%.1f V", data.batteryVoltage)

            val batPowerAbs = kotlin.math.abs(data.batteryPower)
            val batPowerStr = if (batPowerAbs >= 1000f) {
                String.format(java.util.Locale.US, "%.1f kW", data.batteryPower / 1000f)
            } else {
                "${data.batteryPower.toInt()} W"
            }
            tvEnergyBatteryPower.text = batPowerStr
        }
    }

    fun cycleBrightnessMode(showToast: Boolean = true) {
        val nextMode = when (currentBrightnessMode) {
            BrightnessMode.DAY -> BrightnessMode.NIGHT
            BrightnessMode.NIGHT -> BrightnessMode.AUTO
            BrightnessMode.AUTO -> BrightnessMode.DAY
        }
        setBrightnessMode(nextMode, showToast)
    }

    private fun setBrightnessMode(mode: BrightnessMode, showToast: Boolean = true) {
        currentBrightnessMode = mode
        isNightMode = (mode == BrightnessMode.NIGHT)

        when (mode) {
            BrightnessMode.DAY -> btnNightMode.setImageResource(R.drawable.ic_sun)
            BrightnessMode.NIGHT -> btnNightMode.setImageResource(R.drawable.ic_moon)
            BrightnessMode.AUTO -> btnNightMode.setImageResource(R.drawable.ic_brightness_auto)
        }

        applyScreenBrightness(mode)

        getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("brightness_mode", mode.name.lowercase(Locale.ROOT))
            .putBoolean("night_mode", mode == BrightnessMode.NIGHT)
            .apply()

        if (showToast) {
            val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            val msg = when (mode) {
                BrightnessMode.DAY -> {
                    val p = prefs.getInt("day_brightness", 90).coerceIn(5, 100)
                    "Mod Zi Activ • Luminozitate $p%"
                }
                BrightnessMode.NIGHT -> {
                    val p = prefs.getInt("night_brightness", 10).coerceIn(5, 100)
                    "Mod Nocturn Activ • Luminozitate $p%"
                }
                BrightnessMode.AUTO -> "Mod Automat Activ • Luminozitate Sistem Android"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyScreenBrightness(mode: BrightnessMode = currentBrightnessMode) {
        // Funcția de iluminare / luminozitate este dezactivată în E-Sixty Dash
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun setNightMode(night: Boolean, showToast: Boolean = true) {
        // Dezactivat
    }

    private fun applyScreenBrightness(night: Boolean) {
        // Dezactivat
    }


    private fun adjustVolume(direction: Int) {
        // Funcțiile de volum sunt dezactivate în E-Sixty Dash
    }

    private val focusOrderViews: List<View> by lazy {
        listOf(
            btnLeft1,        // 1. Radio (Stanga-Sus)
            btnLeft3,        // 2. Music (Stanga-Jos)
            btnPrevTrack,    // 3. Prev
            btnPlayPause,    // 4. Play
            btnNextTrack,    // 5. Next
            btnQuickSettings,// 6. Setari (Cercul Dreapta-Jos)
            btnLeft2,        // 7. Navigatie (Dreapta-Jos)
            btnRight1,       // 8. Telefon (Dreapta-Sus)
            centerCircle     // 9. Cerc Central
        )
    }

    private fun getActiveSecondaryOverlay(): ViewGroup? {
        if (::callOverlayContainer.isInitialized && callOverlayContainer.visibility == View.VISIBLE) return callOverlayContainer
        if (::favoritesOverlayContainer.isInitialized && favoritesOverlayContainer.visibility == View.VISIBLE) return favoritesOverlayContainer
        if (::radioOverlayContainer.isInitialized && radioOverlayContainer.visibility == View.VISIBLE) return radioOverlayContainer
        if (::mediaPlayerOverlayContainer.isInitialized && mediaPlayerOverlayContainer.visibility == View.VISIBLE) return mediaPlayerOverlayContainer
        if (::musicOverlayContainer.isInitialized && musicOverlayContainer.visibility == View.VISIBLE) return musicOverlayContainer
        if (::appsOverlayContainer.isInitialized && appsOverlayContainer.visibility == View.VISIBLE) return appsOverlayContainer
        if (::settingsOverlayContainer.isInitialized && settingsOverlayContainer.visibility == View.VISIBLE) return settingsOverlayContainer
        return null
    }

    private fun isViewDescendantOf(child: View, parent: View): Boolean {
        var v: ViewParent? = child.parent
        while (v != null) {
            if (v == parent) return true
            v = v.parent
        }
        return false
    }

    private fun getNotificationFocusableViews(): List<View> {
        val views = mutableListOf<View>()
        if (activeNotification != null && layoutNotifEmptyState.visibility != View.VISIBLE) {
            // 1. deschide
            if (::btnNotifAction.isInitialized && btnNotifAction.visibility == View.VISIBLE) views.add(btnNotifAction)
            // 2. reda
            if (::btnNotifSpeak.isInitialized && btnNotifSpeak.visibility == View.VISIBLE) views.add(btnNotifSpeak)
            // 3. sageata dreapta
            if (::btnNotifNext.isInitialized && btnNotifNext.visibility == View.VISIBLE) views.add(btnNotifNext)
            // 4. sageata stanga
            if (::btnNotifPrev.isInitialized && btnNotifPrev.visibility == View.VISIBLE) views.add(btnNotifPrev)
            // 5. cancel
            if (::btnNotifClose.isInitialized && btnNotifClose.visibility == View.VISIBLE) views.add(btnNotifClose)
        }
        // 6. cercul central
        if (::centerCircle.isInitialized && centerCircle.visibility == View.VISIBLE) views.add(centerCircle)
        return views
    }

    private fun getOverlayFocusableViews(overlay: ViewGroup): List<View> {
        val views = mutableListOf<View>()
        when (overlay) {
            viewCenterNotification -> {
                views.addAll(getNotificationFocusableViews())
            }
            favoritesOverlayContainer -> {
                if (::btnCloseFavorites.isInitialized && btnCloseFavorites.visibility == View.VISIBLE) views.add(btnCloseFavorites)
                if (::btnOpenDialer.isInitialized && btnOpenDialer.visibility == View.VISIBLE) views.add(btnOpenDialer)
                if (::listFavoriteContacts.isInitialized && listFavoriteContacts.visibility == View.VISIBLE) {
                    for (i in 0 until listFavoriteContacts.childCount) {
                        val child = listFavoriteContacts.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            radioOverlayContainer -> {
                if (::btnCloseRadio.isInitialized && btnCloseRadio.visibility == View.VISIBLE) views.add(btnCloseRadio)
                if (::btnAddStation.isInitialized && btnAddStation.visibility == View.VISIBLE) views.add(btnAddStation)
                if (::listRadioStations.isInitialized && listRadioStations.visibility == View.VISIBLE) {
                    for (i in 0 until listRadioStations.childCount) {
                        val child = listRadioStations.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            mediaPlayerOverlayContainer -> {
                if (::listMediaTracks.isInitialized && listMediaTracks.visibility == View.VISIBLE) {
                    if (::btnMediaBack.isInitialized && btnMediaBack.visibility == View.VISIBLE) views.add(btnMediaBack)
                    if (::btnMediaShuffle.isInitialized && btnMediaShuffle.visibility == View.VISIBLE) views.add(btnMediaShuffle)
                    if (::btnMediaRepeat.isInitialized && btnMediaRepeat.visibility == View.VISIBLE) views.add(btnMediaRepeat)
                    if (::btnCloseMediaPlayer.isInitialized && btnCloseMediaPlayer.visibility == View.VISIBLE) views.add(btnCloseMediaPlayer)
                    for (i in 0 until listMediaTracks.childCount) {
                        val child = listMediaTracks.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                } else if (::listMediaPlaylists.isInitialized) {
                    if (::btnAddPlaylist.isInitialized && btnAddPlaylist.visibility == View.VISIBLE) views.add(btnAddPlaylist)
                    if (::btnScanMusic.isInitialized && btnScanMusic.visibility == View.VISIBLE) views.add(btnScanMusic)
                    if (::btnCloseMediaPlayer.isInitialized && btnCloseMediaPlayer.visibility == View.VISIBLE) views.add(btnCloseMediaPlayer)
                    for (i in 0 until listMediaPlaylists.childCount) {
                        val child = listMediaPlaylists.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            musicOverlayContainer -> {
                if (::btnCloseMusic.isInitialized && btnCloseMusic.visibility == View.VISIBLE) views.add(btnCloseMusic)
                if (::listMusicApps.isInitialized && listMusicApps.visibility == View.VISIBLE) {
                    for (i in 0 until listMusicApps.childCount) {
                        val child = listMusicApps.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            appsOverlayContainer -> {
                if (::btnCloseApps.isInitialized && btnCloseApps.visibility == View.VISIBLE) views.add(btnCloseApps)
                if (::gridAllApps.isInitialized && gridAllApps.visibility == View.VISIBLE) {
                    for (i in 0 until gridAllApps.childCount) {
                        val child = gridAllApps.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            settingsOverlayContainer -> {
                if (::btnCloseSettings.isInitialized && btnCloseSettings.visibility == View.VISIBLE) views.add(btnCloseSettings)
                if (::seekDayBrightness.isInitialized && seekDayBrightness.visibility == View.VISIBLE) views.add(seekDayBrightness)
                if (::seekNightBrightness.isInitialized && seekNightBrightness.visibility == View.VISIBLE) views.add(seekNightBrightness)
                if (::btnOpenNotifSettings.isInitialized && btnOpenNotifSettings.visibility == View.VISIBLE) views.add(btnOpenNotifSettings)
                if (::btnCarBtSelect.isInitialized && btnCarBtSelect.visibility == View.VISIBLE) views.add(btnCarBtSelect)
            }
            callOverlayContainer -> {
                val ringing = findViewById<View>(R.id.call_ringing_actions)
                val deny = findViewById<View>(R.id.btn_call_deny)
                val answer = findViewById<View>(R.id.btn_call_answer)
                val hangup = findViewById<View>(R.id.btn_call_hangup)
                if (ringing?.visibility == View.VISIBLE) {
                    if (deny?.visibility == View.VISIBLE) views.add(deny)
                    if (answer?.visibility == View.VISIBLE) views.add(answer)
                } else if (hangup?.visibility == View.VISIBLE) {
                    views.add(hangup)
                }
            }
        }
        return views
    }

    private fun focusAndCenterListItem(listView: AdapterView<*>, targetPos: Int) {
        val listHeight = if (listView.height > 0) listView.height else (260 * resources.displayMetrics.density).toInt()
        val sampleChild = (0 until listView.childCount).map { listView.getChildAt(it) }.firstOrNull { it != null && it.height > 0 }
        val itemHeight = sampleChild?.height ?: (62 * resources.displayMetrics.density).toInt()
        val centerOffset = ((listHeight - itemHeight) / 2).coerceAtLeast(0)

        if (listView is ListView) {
            listView.setSelectionFromTop(targetPos, centerOffset)
        } else if (listView is GridView) {
            listView.setSelection(targetPos)
            listView.smoothScrollToPositionFromTop(targetPos, centerOffset, 120)
        }

        val requestFocusAction = Runnable {
            val childIndex = targetPos - listView.firstVisiblePosition
            val rowView = if (childIndex in 0 until listView.childCount) listView.getChildAt(childIndex) else null
            if (rowView != null) {
                rowView.isFocusable = true
                rowView.isFocusableInTouchMode = true
                rowView.requestFocus()
            }
        }

        listView.post(requestFocusAction)
        listView.postDelayed(requestFocusAction, 35L)
    }

    private fun handleOverlayTabNavigation(overlay: ViewGroup, isShift: Boolean): Boolean {
        val listView: AdapterView<*>? = when (overlay) {
            favoritesOverlayContainer -> if (::listFavoriteContacts.isInitialized && listFavoriteContacts.visibility == View.VISIBLE) listFavoriteContacts else null
            radioOverlayContainer -> if (::listRadioStations.isInitialized && listRadioStations.visibility == View.VISIBLE) listRadioStations else null
            mediaPlayerOverlayContainer -> if (::listMediaTracks.isInitialized && listMediaTracks.visibility == View.VISIBLE) listMediaTracks else (if (::listMediaPlaylists.isInitialized && listMediaPlaylists.visibility == View.VISIBLE) listMediaPlaylists else null)
            musicOverlayContainer -> if (::listMusicApps.isInitialized && listMusicApps.visibility == View.VISIBLE) listMusicApps else null
            appsOverlayContainer -> if (::gridAllApps.isInitialized && gridAllApps.visibility == View.VISIBLE) gridAllApps else null
            else -> null
        }

        val current = currentFocus

        if (listView != null && listView.count > 0) {
            val headerViews = when (overlay) {
                favoritesOverlayContainer -> listOfNotNull(
                    if (::btnCloseFavorites.isInitialized && btnCloseFavorites.visibility == View.VISIBLE) btnCloseFavorites else null,
                    if (::btnOpenDialer.isInitialized && btnOpenDialer.visibility == View.VISIBLE) btnOpenDialer else null
                )
                radioOverlayContainer -> listOfNotNull(
                    if (::btnCloseRadio.isInitialized && btnCloseRadio.visibility == View.VISIBLE) btnCloseRadio else null,
                    if (::btnAddStation.isInitialized && btnAddStation.visibility == View.VISIBLE) btnAddStation else null
                )
                mediaPlayerOverlayContainer -> {
                    if (::listMediaTracks.isInitialized && listMediaTracks.visibility == View.VISIBLE) {
                        listOfNotNull(
                            if (::btnMediaBack.isInitialized && btnMediaBack.visibility == View.VISIBLE) btnMediaBack else null,
                            if (::btnMediaShuffle.isInitialized && btnMediaShuffle.visibility == View.VISIBLE) btnMediaShuffle else null,
                            if (::btnMediaRepeat.isInitialized && btnMediaRepeat.visibility == View.VISIBLE) btnMediaRepeat else null,
                            if (::btnCloseMediaPlayer.isInitialized && btnCloseMediaPlayer.visibility == View.VISIBLE) btnCloseMediaPlayer else null
                        )
                    } else {
                        listOfNotNull(
                            if (::btnAddPlaylist.isInitialized && btnAddPlaylist.visibility == View.VISIBLE) btnAddPlaylist else null,
                            if (::btnScanMusic.isInitialized && btnScanMusic.visibility == View.VISIBLE) btnScanMusic else null,
                            if (::btnCloseMediaPlayer.isInitialized && btnCloseMediaPlayer.visibility == View.VISIBLE) btnCloseMediaPlayer else null
                        )
                    }
                }
                musicOverlayContainer -> listOfNotNull(
                    if (::btnCloseMusic.isInitialized && btnCloseMusic.visibility == View.VISIBLE) btnCloseMusic else null
                )
                appsOverlayContainer -> listOfNotNull(
                    if (::btnCloseApps.isInitialized && btnCloseApps.visibility == View.VISIBLE) btnCloseApps else null
                )
                else -> emptyList()
            }

            val headerIndex = headerViews.indexOfFirst { it == current || (current != null && isViewDescendantOf(current, it)) }
            val currentPos = if (current != null) listView.getPositionForView(current) else AdapterView.INVALID_POSITION

            if (!isShift) {
                // Navigare înainte (TAB)
                when {
                    headerIndex in 0 until headerViews.size - 1 -> {
                        headerViews[headerIndex + 1].requestFocus()
                        return true
                    }
                    headerIndex == headerViews.size - 1 -> {
                        focusAndCenterListItem(listView, 0)
                        return true
                    }
                    currentPos in 0 until listView.count - 1 -> {
                        focusAndCenterListItem(listView, currentPos + 1)
                        return true
                    }
                    currentPos == listView.count - 1 -> {
                        if (headerViews.isNotEmpty()) {
                            headerViews[0].requestFocus()
                        } else {
                            focusAndCenterListItem(listView, 0)
                        }
                        return true
                    }
                    else -> {
                        if (headerViews.isNotEmpty()) {
                            headerViews[0].requestFocus()
                        } else {
                            focusAndCenterListItem(listView, 0)
                        }
                        return true
                    }
                }
            } else {
                // Navigare înapoi (SHIFT + TAB)
                when {
                    headerIndex > 0 -> {
                        headerViews[headerIndex - 1].requestFocus()
                        return true
                    }
                    headerIndex == 0 -> {
                        focusAndCenterListItem(listView, listView.count - 1)
                        return true
                    }
                    currentPos > 0 -> {
                        focusAndCenterListItem(listView, currentPos - 1)
                        return true
                    }
                    currentPos == 0 -> {
                        if (headerViews.isNotEmpty()) {
                            headerViews.last().requestFocus()
                        } else {
                            focusAndCenterListItem(listView, listView.count - 1)
                        }
                        return true
                    }
                    else -> {
                        focusAndCenterListItem(listView, listView.count - 1)
                        return true
                    }
                }
            }
        }

        // Pentru ferestre fără liste (Settings, Apel activ etc.)
        val focusableViews = getOverlayFocusableViews(overlay)
        Log.d("MainActivity", "handleOverlayTabNavigation: overlay=$overlay, focusableCount=${focusableViews.size}, current=$current")
        if (focusableViews.isEmpty()) return true

        val currentIndex = focusableViews.indexOfFirst { it == current || (current != null && isViewDescendantOf(current, it)) }

        val nextIndex = if (currentIndex < 0) {
            if (isShift) focusableViews.size - 1 else 0
        } else if (isShift) {
            if (currentIndex <= 0) focusableViews.size - 1 else currentIndex - 1
        } else {
            if (currentIndex >= focusableViews.size - 1) 0 else currentIndex + 1
        }

        val target = focusableViews[nextIndex]
        Log.d("MainActivity", "handleOverlayTabNavigation: currentIndex=$currentIndex, nextIndex=$nextIndex, target=$target")
        target.isFocusable = true
        target.isFocusableInTouchMode = true
        val reqRes = target.requestFocus()
        Log.d("MainActivity", "handleOverlayTabNavigation: requestFocus result=$reqRes, hasFocus=${target.hasFocus()}")
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.action == MotionEvent.ACTION_DOWN) {
            resetNotifDismissTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (::mediaPlayerOverlayContainer.isInitialized && mediaPlayerOverlayContainer.visibility == View.VISIBLE) {
            if (::listMediaTracks.isInitialized && listMediaTracks.visibility == View.VISIBLE) {
                showMediaPlaylistsView()
            } else {
                mediaPlayerOverlayContainer.visibility = View.GONE
            }
            return
        }
        if (getActiveSecondaryOverlay() != null) {
            dismissAllOverlays()
            return
        }
        if (currentMode != CenterMode.BMW) {
            val prev = currentMode
            currentMode = CenterMode.BMW
            updateCenterModeUI(animateFrom = prev)
            return
        }
        super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetNotifDismissTimer()
        }
        // Tasta F12 de pe tastatura Bluetooth revine direct la launcher și închide ferestrele secundare
        if (event.keyCode == KeyEvent.KEYCODE_F12) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.i("MainActivity", "F12 apasat! Revenire la ecranul principal BMW...")
                dismissAllOverlays()
                if (currentMode != CenterMode.BMW) {
                    val prev = currentMode
                    currentMode = CenterMode.BMW
                    updateCenterModeUI(animateFrom = prev)
                }
            }
            return true
        }

        // Tasta TAB sau Săgeți Stânga/Dreapta pentru navigare notificări în cercul central
        if (event.action == KeyEvent.ACTION_DOWN && currentMode == CenterMode.NOTIFICATION) {
            val unreadList = CarNotificationHub.unreadNotifications
            if (unreadList.isNotEmpty()) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    btnNotifPrev.isPressed = true
                    btnNotifPrev.postDelayed({ btnNotifPrev.isPressed = false }, 180)
                    navigateToPreviousNotification()
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    btnNotifNext.isPressed = true
                    btnNotifNext.postDelayed({ btnNotifNext.isPressed = false }, 180)
                    navigateToNextNotification()
                    return true
                }
            }
        }

        val activeOverlay = getActiveSecondaryOverlay()

        if (event.keyCode == KeyEvent.KEYCODE_TAB && event.action == KeyEvent.ACTION_DOWN) {
            val isCallActiveOrOutgoing = ::callOverlayContainer.isInitialized &&
                    callOverlayContainer.visibility == View.VISIBLE &&
                    ::btnCallHangup.isInitialized &&
                    btnCallHangup.visibility == View.VISIBLE &&
                    (!::callRingingActions.isInitialized || callRingingActions.visibility != View.VISIBLE)

            if (isCallActiveOrOutgoing) {
                val isShift = event.isShiftPressed
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val direction = if (isShift) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                try {
                    am?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, AudioManager.FLAG_SHOW_UI)
                } catch (e: Exception) {
                    try {
                        am?.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                    } catch (_: Exception) {}
                }
                btnCallHangup.isFocusable = true
                btnCallHangup.isFocusableInTouchMode = true
                btnCallHangup.requestFocus()
                return true
            }

            if (activeOverlay != null) {
                return handleOverlayTabNavigation(activeOverlay, event.isShiftPressed)
            }

            if (currentMode == CenterMode.NOTIFICATION) {
                val notifViews = getNotificationFocusableViews()
                if (notifViews.isNotEmpty()) {
                    val isShift = event.isShiftPressed
                    val current = currentFocus
                    val currentIndex = if (current == centerCircle) {
                        notifViews.indexOf(centerCircle)
                    } else {
                        val matched = notifViews.filter { it != centerCircle }.firstOrNull { it == current || it.hasFocus() || isViewDescendantOf(current ?: it, it) }
                        if (matched != null) notifViews.indexOf(matched) else -1
                    }
                    val nextIndex = if (currentIndex < 0) {
                        if (isShift) notifViews.size - 1 else {
                            val actionIdx = notifViews.indexOf(btnNotifAction)
                            if (actionIdx >= 0) actionIdx else 0
                        }
                    } else if (isShift) {
                        if (currentIndex <= 0) notifViews.size - 1 else currentIndex - 1
                    } else {
                        if (currentIndex >= notifViews.size - 1) 0 else currentIndex + 1
                    }
                    val target = notifViews[nextIndex]
                    if (target == centerCircle) {
                        centerCircle.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                        centerCircle.isFocusable = true
                        centerCircle.isFocusableInTouchMode = true
                        centerCircle.requestFocus()
                    } else {
                        centerCircle.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        target.isFocusable = true
                        target.isFocusableInTouchMode = true
                        target.requestFocus()
                    }
                    return true
                }
            }

            val isShift = event.isShiftPressed
            val currentFocusView = currentFocus
            val currentIndex = focusOrderViews.indexOfFirst { it == currentFocusView || it.hasFocus() }

            val nextIndex = if (currentIndex < 0) {
                // Dacă nu e focalizat niciun buton, pornim de la cercul central (dacă suntem în mod notificare fără notificări) sau de la 0 / ultimul
                if (currentMode == CenterMode.NOTIFICATION) {
                    val centerIdx = focusOrderViews.indexOf(centerCircle)
                    if (centerIdx >= 0) centerIdx else if (isShift) focusOrderViews.size - 1 else 0
                } else if (isShift) {
                    focusOrderViews.size - 1
                } else {
                    0
                }
            } else if (isShift) {
                if (currentIndex <= 0) focusOrderViews.size - 1 else currentIndex - 1
            } else {
                if (currentIndex >= focusOrderViews.size - 1) 0 else currentIndex + 1
            }

            val target = focusOrderViews[nextIndex]
            target.isFocusable = true
            target.isFocusableInTouchMode = true
            target.requestFocus()
            return true
        }

        // Actionare cu Enter / DPad Center pentru oricare element focalizat din launcher
        if ((event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            && event.action == KeyEvent.ACTION_DOWN
        ) {
            if (activeOverlay != null) {
                val focused = currentFocus
                if (focused != null && isViewDescendantOf(focused, activeOverlay)) {
                    val clickTarget = if (focused.hasOnClickListeners()) focused else {
                        var p: ViewParent? = focused.parent
                        var target: View? = null
                        while (p != null && p != activeOverlay) {
                            if (p is View && p.hasOnClickListeners()) {
                                target = p
                                break
                            }
                            p = p.parent
                        }
                        target ?: focused
                    }
                    clickTarget.performClick()
                    return true
                }
            }

            if (currentMode == CenterMode.NOTIFICATION) {
                val focused = currentFocus
                if (focused != null) {
                    val notifViews = getNotificationFocusableViews()
                    val target = if (focused == centerCircle) {
                        centerCircle
                    } else {
                        notifViews.filter { it != centerCircle }.firstOrNull { it == focused || it.hasFocus() || isViewDescendantOf(focused, it) } ?: if (centerCircle in notifViews) centerCircle else null
                    }
                    if (target != null) {
                        target.isPressed = true
                        target.postDelayed({ target.isPressed = false }, 180)
                        target.performClick()
                        return true
                    }
                }
            }

            val focused = currentFocus
            if (focused != null && (focused in focusOrderViews || focusOrderViews.any { it.hasFocus() })) {
                val target = focusOrderViews.firstOrNull { it == focused || it.hasFocus() } ?: focused
                target.performClick()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    fun dismissAllOverlays() {
        if (::favoritesOverlayContainer.isInitialized && favoritesOverlayContainer.visibility == View.VISIBLE) {
            favoritesOverlayContainer.visibility = View.GONE
        }
        if (::radioOverlayContainer.isInitialized && radioOverlayContainer.visibility == View.VISIBLE) {
            radioOverlayContainer.visibility = View.GONE
        }
        if (::mediaPlayerOverlayContainer.isInitialized && mediaPlayerOverlayContainer.visibility == View.VISIBLE) {
            mediaPlayerOverlayContainer.visibility = View.GONE
        }
        if (::musicOverlayContainer.isInitialized && musicOverlayContainer.visibility == View.VISIBLE) {
            musicOverlayContainer.visibility = View.GONE
        }
        if (::appsOverlayContainer.isInitialized && appsOverlayContainer.visibility == View.VISIBLE) {
            appsOverlayContainer.visibility = View.GONE
        }
        if (::settingsOverlayContainer.isInitialized && settingsOverlayContainer.visibility == View.VISIBLE) {
            settingsOverlayContainer.visibility = View.GONE
        }
    }

    private fun focusPlayPauseButton() {
        // Dezactivat conform solicitării utilizatorului: focusul nu se mai mută automat pe butonul play/pause
    }

    private fun View.enableInstantClickOnTouch() {
        var isLongClicked = false
        var downX = 0f
        var downY = 0f
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (isPressed) {
                isLongClicked = performLongClick()
            }
        }

        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongClicked = false
                    downX = event.x
                    downY = event.y
                    v.isPressed = true
                    longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    if (!isLongClicked && event.x >= -slop && event.x <= v.width + slop &&
                        event.y >= -slop && event.y <= v.height + slop) {
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    isLongClicked = false
                    false
                }
                else -> false
            }
        }
    }

    fun showIncomingCallUI(number: String?, name: String?, customBitmap: Bitmap? = null) {
        val contactInfo = if (customBitmap != null) {
            Pair(name, customBitmap)
        } else if (number != null) {
            getContactInfo(this, number)
        } else {
            Pair(name, null)
        }
        val displayName = name ?: contactInfo.first ?: number ?: "Număr Necunoscut"
        val displayPhone = number ?: ""

        callContactName.text = displayName
        callPhoneNumber.text = displayPhone

        if (contactInfo.second != null) {
            callContactPhoto.setPadding(0, 0, 0, 0)
            try {
                val circularBitmap = getCircularBitmap(contactInfo.second!!)
                callContactPhoto.setImageBitmap(circularBitmap)
            } catch (e: Exception) {
                callContactPhoto.setImageBitmap(contactInfo.second)
            }
            callContactPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val pad = (14 * resources.displayMetrics.density).toInt()
            callContactPhoto.setPadding(pad, pad, pad, pad)
            callContactPhoto.setImageResource(R.drawable.ic_call_avatar)
            callContactPhoto.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        callStatusTitle.text = "⚡ APEL DE INTRARE"
        callDurationTimer.visibility = View.GONE
        callRingingActions.visibility = View.VISIBLE
        btnCallHangup.visibility = View.GONE
        callOverlayContainer.visibility = View.VISIBLE

        btnCallAnswer.requestFocus()
    }

    fun showActiveCallUI() {
        callStatusTitle.text = "● APEL ÎN DESFĂȘURARE"
        callRingingActions.visibility = View.GONE
        btnCallHangup.visibility = View.VISIBLE
        callDurationTimer.visibility = View.VISIBLE

        callDurationSeconds = 0
        callDurationTimer.text = "00:00"
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.postDelayed(callTimerRunnable, 1000)

        callOverlayContainer.visibility = View.VISIBLE
        btnCallHangup.isFocusable = true
        btnCallHangup.isFocusableInTouchMode = true
        btnCallHangup.requestFocus()
        btnCallHangup.post {
            btnCallHangup.requestFocus()
        }
    }

    fun dismissCallUI() {
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callOverlayContainer.visibility = View.GONE
        focusPlayPauseButton()
    }

    fun showOutgoingCallUI(number: String, name: String, photo: Bitmap?) {
        callContactName.text = name
        callPhoneNumber.text = number

        if (photo != null) {
            callContactPhoto.setPadding(0, 0, 0, 0)
            try {
                val circularBitmap = getCircularBitmap(photo)
                callContactPhoto.setImageBitmap(circularBitmap)
            } catch (e: Exception) {
                callContactPhoto.setImageBitmap(photo)
            }
            callContactPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val pad = (14 * resources.displayMetrics.density).toInt()
            callContactPhoto.setPadding(pad, pad, pad, pad)
            callContactPhoto.setImageResource(R.drawable.ic_call_avatar)
            callContactPhoto.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        callStatusTitle.text = "● APELARE ÎN CURS..."
        callDurationTimer.visibility = View.GONE
        callRingingActions.visibility = View.GONE
        btnCallHangup.visibility = View.VISIBLE
        callOverlayContainer.visibility = View.VISIBLE

        btnCallHangup.isFocusable = true
        btnCallHangup.isFocusableInTouchMode = true
        btnCallHangup.requestFocus()
        btnCallHangup.post {
            btnCallHangup.requestFocus()
        }
    }

    private fun showRecentCallsOverlay() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_CALL_LOG,
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.CALL_PHONE
                ),
                2001
            )
            return
        }

        // Sugeram setarea ca Default Phone App daca nu este deja setat, pentru a elimina complet dialerul nativ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(roleIntent, 2002)
                } catch (_: Exception) {}
            }
        }

        val recentCalls = loadRecentCalls()
        if (recentCalls.isEmpty()) {
            tvEmptyFavorites.visibility = View.VISIBLE
            listFavoriteContacts.visibility = View.GONE
        } else {
            tvEmptyFavorites.visibility = View.GONE
            listFavoriteContacts.visibility = View.VISIBLE

            listFavoriteContacts.adapter = object : BaseAdapter() {
                override fun getCount(): Int = recentCalls.size
                override fun getItem(position: Int): Any = recentCalls[position]
                override fun getItemId(position: Int): Long = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.item_favorite_contact, parent, false)
                    view.isFocusable = true
                    view.isFocusableInTouchMode = false
                    view.enableInstantClickOnTouch()
                    val call = recentCalls[position]

                    val photoFrame = view.findViewById<FrameLayout>(R.id.fav_contact_photo_frame)
                    photoFrame.outlineProvider = circleOutlineProvider
                    photoFrame.clipToOutline = true

                    val photoView = view.findViewById<ImageView>(R.id.fav_contact_photo)
                    photoView.outlineProvider = circleOutlineProvider
                    photoView.clipToOutline = true

                    val nameView = view.findViewById<TextView>(R.id.fav_contact_name)
                    val phoneView = view.findViewById<TextView>(R.id.fav_contact_phone)

                    nameView.text = call.name
                    phoneView.text = formatCallDetails(call)

                    val isMissed = (call.type == CallLog.Calls.MISSED_TYPE || call.type == CallLog.Calls.REJECTED_TYPE)
                    if (isMissed) {
                        nameView.setTextColor(Color.parseColor("#FF5252"))
                        phoneView.setTextColor(Color.parseColor("#FF8A80"))
                    } else {
                        nameView.setTextColor(Color.WHITE)
                        phoneView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bmw_amber))
                    }

                    if (call.photo != null) {
                        photoView.setPadding(0, 0, 0, 0)
                        try {
                            photoView.setImageBitmap(getCircularBitmap(call.photo))
                        } catch (_: Exception) {
                            photoView.setImageBitmap(call.photo)
                        }
                        photoView.scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        val pad = (10 * resources.displayMetrics.density).toInt()
                        photoView.setPadding(pad, pad, pad, pad)
                        photoView.setImageResource(R.drawable.ic_call_avatar)
                        photoView.scaleType = ImageView.ScaleType.FIT_CENTER
                    }

                    view.setOnClickListener {
                        startCallToRecent(call)
                    }

                    return view
                }
            }
        }

        favoritesOverlayContainer.visibility = View.VISIBLE
        btnCloseFavorites.isFocusable = true
        btnCloseFavorites.isFocusableInTouchMode = false
        btnCloseFavorites.enableInstantClickOnTouch()
    }

    private fun showFavoritesOverlay() {
        showRecentCallsOverlay()
    }

    private fun formatCallDetails(call: RecentCall): String {
        val calCall = Calendar.getInstance().apply { timeInMillis = call.date }
        val calNow = Calendar.getInstance()

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(call.date))

        val dateStr = when {
            calCall.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
            calCall.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR) -> "Azi $timeStr"

            calCall.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
            calCall.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR) - 1 -> "Ieri $timeStr"

            else -> {
                val fullFormat = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                fullFormat.format(Date(call.date))
            }
        }

        val typeStr = when (call.type) {
            CallLog.Calls.INCOMING_TYPE -> "↙ Primit"
            CallLog.Calls.OUTGOING_TYPE -> "↗ Inițiat"
            CallLog.Calls.MISSED_TYPE -> "✕ Pierdut"
            CallLog.Calls.REJECTED_TYPE -> "✕ Respins"
            else -> "• Apel"
        }

        return "$typeStr  •  $dateStr  •  ${call.number}"
    }

    private fun loadRecentCalls(): List<RecentCall> {
        val callsList = mutableListOf<RecentCall>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return callsList
        }

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE
        )

        try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            val infoCache = mutableMapOf<String, Pair<String?, Bitmap?>>()

            cursor?.use { c ->
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)

                while (c.moveToNext() && callsList.size < 50) {
                    val rawNum = if (numIdx != -1) c.getString(numIdx) ?: "" else ""
                    if (rawNum.isEmpty()) continue

                    var cachedName = if (nameIdx != -1) c.getString(nameIdx) else null
                    val type = if (typeIdx != -1) c.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val date = if (dateIdx != -1) c.getLong(dateIdx) else 0L

                    val cleanNum = rawNum.replace("\\s+".toRegex(), "")
                    val contactInfo = infoCache.getOrPut(cleanNum) {
                        getContactInfo(this, cleanNum)
                    }

                    val finalName = when {
                        !cachedName.isNullOrBlank() -> cachedName
                        !contactInfo.first.isNullOrBlank() -> contactInfo.first!!
                        else -> rawNum
                    }

                    callsList.add(RecentCall(finalName, rawNum, type, date, contactInfo.second))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return callsList
    }

    private fun startCallToRecent(call: RecentCall) {
        favoritesOverlayContainer.visibility = View.GONE
        showOutgoingCallUI(call.number, call.name, call.photo)

        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null) {
                    val uri = Uri.fromParts("tel", call.number, null)
                    telecomManager.placeCall(uri, Bundle())
                } else {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(call.number)}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(callIntent)
                }
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(call.number)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(dialIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Eroare la inițierea apelului: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        keepLauncherOnTop()
    }

    private fun keepLauncherOnTop() {
        val handler = Handler(Looper.getMainLooper())
        val delays = longArrayOf(80L, 220L, 480L, 850L, 1400L, 2200L)
        for (delay in delays) {
            handler.postDelayed({
                if (callOverlayContainer.visibility == View.VISIBLE) {
                    try {
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        am?.moveTaskToFront(taskId, 0)
                    } catch (_: Exception) {}
                    try {
                        val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }, delay)
        }
    }

    private fun loadFavoriteContacts(): List<FavoriteContact> {
        val contactsList = mutableListOf<FavoriteContact>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return contactsList
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )

        try {
            // 1. Mai întâi căutăm contactele favorite (starred = 1)
            val cursorStarred = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1",
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            val seenNumbers = HashSet<String>()
            cursorStarred?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (cursor.moveToNext()) {
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else "Contact"
                    val rawNumber = if (numberIdx != -1) cursor.getString(numberIdx) else ""
                    val cleanNumber = rawNumber.replace("\\s+".toRegex(), "")
                    if (cleanNumber.isEmpty() || seenNumbers.contains(cleanNumber)) continue
                    seenNumbers.add(cleanNumber)

                    var photoBitmap: Bitmap? = null
                    if (photoIdx != -1) {
                        val photoUriStr = cursor.getString(photoIdx)
                        if (!photoUriStr.isNullOrEmpty()) {
                            try {
                                contentResolver.openInputStream(Uri.parse(photoUriStr))?.use { stream ->
                                    photoBitmap = BitmapFactory.decodeStream(stream)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    contactsList.add(FavoriteContact(name, rawNumber, photoBitmap))
                }
            }

            // 2. Dacă nu sunt contacte cu steluță, încărcăm primele contacte din agendă pentru a fi util
            if (contactsList.isEmpty()) {
                val cursorAll = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursorAll?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                    while (cursor.moveToNext() && contactsList.size < 25) {
                        val name = if (nameIdx != -1) cursor.getString(nameIdx) else "Contact"
                        val rawNumber = if (numberIdx != -1) cursor.getString(numberIdx) else ""
                        val cleanNumber = rawNumber.replace("\\s+".toRegex(), "")
                        if (cleanNumber.isEmpty() || seenNumbers.contains(cleanNumber)) continue
                        seenNumbers.add(cleanNumber)

                        var photoBitmap: Bitmap? = null
                        if (photoIdx != -1) {
                            val photoUriStr = cursor.getString(photoIdx)
                            if (!photoUriStr.isNullOrEmpty()) {
                                try {
                                    contentResolver.openInputStream(Uri.parse(photoUriStr))?.use { stream ->
                                        photoBitmap = BitmapFactory.decodeStream(stream)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        contactsList.add(FavoriteContact(name, rawNumber, photoBitmap))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return contactsList
    }

    private fun getContactInfo(context: Context, phoneNumber: String): Pair<String?, Bitmap?> {
        var name: String? = null
        var photo: Bitmap? = null
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
                val projection = arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                        val photoUriIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                        if (photoUriIndex != -1) {
                            val photoUriStr = cursor.getString(photoUriIndex)
                            if (!photoUriStr.isNullOrEmpty()) {
                                val photoUri = Uri.parse(photoUriStr)
                                context.contentResolver.openInputStream(photoUri)?.use { stream ->
                                    photo = BitmapFactory.decodeStream(stream)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(name, photo)
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
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

    private fun checkAndRequestPhonePermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CALL_PHONE)
        }
        // Permisiune muzica locala: READ_MEDIA_AUDIO (Android 13+) sau READ_EXTERNAL_STORAGE (Android <= 12)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 2001)
        }

        // Cerere Default Phone Role daca este pe Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(roleIntent, 2002)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Verificare permisiune overlay (pentru revenire automata YT Music si suprimare ecran nativ dialer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
            } catch (_: Exception) {}
        }
    }

    private fun setupCallServiceListener() {
        BMWInCallService.listener = object : BMWInCallService.Companion.CallListener {
            override fun onIncomingCall(number: String?, name: String?) {
                runOnUiThread {
                    showIncomingCallUI(number, name)
                }
            }

            override fun onOutgoingCall(number: String?, name: String?) {
                runOnUiThread {
                    val contactInfo = if (number != null) getContactInfo(this@MainActivity, number) else Pair(name, null)
                    showOutgoingCallUI(number ?: "", name ?: contactInfo.first ?: number ?: "Număr Necunoscut", contactInfo.second)
                }
            }

            override fun onCallStateChanged(state: Int) {
                runOnUiThread {
                    when (state) {
                        android.telecom.Call.STATE_ACTIVE -> showActiveCallUI()
                        android.telecom.Call.STATE_DISCONNECTED -> dismissCallUI()
                    }
                }
            }

            override fun onCallEnded() {
                runOnUiThread {
                    dismissCallUI()
                }
            }
        }
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_INCOMING_CALL", false) == true) {
            val number = intent.getStringExtra("EXTRA_CALL_NUMBER")
            val name = intent.getStringExtra("EXTRA_CALL_NAME")
            val testPhoto = intent.getBooleanExtra("EXTRA_TEST_PHOTO", false)
            val customBitmap = if (testPhoto) {
                BitmapFactory.decodeResource(resources, R.drawable.bmw_logo)
            } else null
            showIncomingCallUI(number, name, customBitmap)
        } else if (intent?.getBooleanExtra("EXTRA_OUTGOING_CALL", false) == true) {
            val number = intent.getStringExtra("EXTRA_CALL_NUMBER")
            val name = intent.getStringExtra("EXTRA_CALL_NAME")
            val contactInfo = if (number != null) getContactInfo(this, number) else Pair(name, null)
            showOutgoingCallUI(number ?: "", name ?: contactInfo.first ?: number ?: "Număr Necunoscut", contactInfo.second)
        }
    }
}
