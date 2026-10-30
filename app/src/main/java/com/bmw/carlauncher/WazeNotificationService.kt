package com.bmw.carlauncher

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale

data class WazeNavData(
    val distance: String,
    val road: String,
    val eta: String,
    val isNavigating: Boolean = true,
    val iconBitmap: Bitmap? = null,
    val sourceApp: String = "GOOGLE MAPS",
    val directionSymbol: String? = null
)

object WazeNavManager {
    var currentData: WazeNavData = WazeNavData(
        distance = "-- m",
        road = "Porniți o rută în Google Maps",
        eta = "Așteptare indicații...",
        isNavigating = false,
        iconBitmap = null,
        sourceApp = "GOOGLE MAPS",
        directionSymbol = "↑"
    )

    var listener: ((WazeNavData) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentData)
        }

    fun update(data: WazeNavData) {
        currentData = data
        listener?.invoke(data)
    }
}

data class ExternalMediaData(
    val packageName: String = "",
    val sourceName: String = "",
    val title: String = "",
    val artist: String = "",
    val artBitmap: Bitmap? = null,
    val isPlaying: Boolean = false,
    val mediaController: android.media.session.MediaController? = null
)

object ExternalMediaManager {
    var currentData: ExternalMediaData = ExternalMediaData()
    var preferredPackage: String = ""

    var listener: ((ExternalMediaData) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentData)
        }

    private var registeredController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var launcherTaskId: Int = -1
    private var autoReturnArmed: Boolean = false
    private var autoReturnArmTime: Long = 0L
    private var autoReturnInitialTitle: String = ""
    private var autoReturnInitialPlaying: Boolean = false
    private var userSawPausedWhileArmed: Boolean = false
    private var bgSawYtMusicNonPlaying: Boolean = false
    private var lastAutoReturnTriggerTime: Long = 0L

    private val autoReturnPollRunnable = object : Runnable {
        override fun run() {
            if (!autoReturnArmed || MainActivity.isInForeground) {
                cancelAutoReturn()
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - autoReturnArmTime
            if (elapsed > 180_000L) {
                cancelAutoReturn()
                return
            }
            val ctx = appContext ?: WazeNotificationService.instance
            if (ctx != null) {
                val ytCtrl = findYtMusicController(ctx)
                if (ytCtrl != null) {
                    attachCallback(ytCtrl)
                } else {
                    syncActiveSessions(ctx)
                }
            }
            if (autoReturnArmed && !MainActivity.isInForeground) {
                mainHandler.postDelayed(this, 350L)
            }
        }
    }

    fun armAutoReturnFromYtMusic(context: Context, taskId: Int) {
        appContext = context.applicationContext
        launcherTaskId = taskId
        val ytCtrl = findYtMusicController(context)
        val pbState = ytCtrl?.playbackState?.state ?: PlaybackState.STATE_NONE
        autoReturnInitialPlaying = (pbState == PlaybackState.STATE_PLAYING)
        userSawPausedWhileArmed = false
        val meta = ytCtrl?.metadata
        autoReturnInitialTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: if (currentData.packageName.contains("youtube.music", ignoreCase = true)) currentData.title else ""
        autoReturnArmTime = SystemClock.elapsedRealtime()
        autoReturnArmed = true
        mainHandler.removeCallbacks(autoReturnPollRunnable)
        mainHandler.postDelayed(autoReturnPollRunnable, 400L)
    }

    fun onLauncherPaused(context: Context, taskId: Int) {
        appContext = context.applicationContext
        if (taskId != -1) launcherTaskId = taskId
        // Nu facem absolut nimic daca nu a fost lansat YT Music din launcher
        if (!autoReturnArmed) {
            return
        }
        val ytCtrl = findYtMusicController(context)
        val pbState = ytCtrl?.playbackState?.state ?: PlaybackState.STATE_NONE
        val isPlayingNow = (pbState == PlaybackState.STATE_PLAYING)
        if (!isPlayingNow) {
            userSawPausedWhileArmed = true
        }
        mainHandler.removeCallbacks(autoReturnPollRunnable)
        mainHandler.postDelayed(autoReturnPollRunnable, 400L)
    }

    fun cancelAutoReturn() {
        autoReturnArmed = false
        userSawPausedWhileArmed = false
        bgSawYtMusicNonPlaying = false
        mainHandler.removeCallbacks(autoReturnPollRunnable)
    }

    private fun findYtMusicController(context: Context): MediaController? {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val cn = ComponentName(context, WazeNotificationService::class.java)
            val sessions = msm?.getActiveSessions(cn) ?: emptyList()
            return sessions.firstOrNull { it.packageName.contains("youtube.music", ignoreCase = true) }
        } catch (_: Exception) {}
        return null
    }

    private fun checkAutoReturn(pkg: String, title: String, pbState: Int?, isPlaying: Boolean) {
        // Numai daca a fost inarmat explicit la lansarea YouTube Music din launcher
        if (!autoReturnArmed) return
        if (!pkg.contains("youtube.music", ignoreCase = true)) return

        val isNowPlaying = (pbState == PlaybackState.STATE_PLAYING) || (pbState == null && isPlaying)
        if (!isNowPlaying) {
            // Melodia este oprita/pe pauza in timp ce utilizatorul e in YT Music
            userSawPausedWhileArmed = true
            return
        }

        // Daca BMW Car Launcher este deja in prim-plan, nu facem revenire
        if (MainActivity.isInForeground) {
            cancelAutoReturn()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoReturnTriggerTime < 1500L) return

        // Nu declanșăm în primele 800ms de la lansare (pentru a evita captarea stării vechi)
        if (now - autoReturnArmTime < 800L) return

        // Verificam daca utilizatorul a apasat EXPLICIT Play in YT Music:
        val explicitPlayPressed = if (!autoReturnInitialPlaying) {
            // Nu canta cand a deschis aplicatia, iar acum a dat Play pe o piesa
            true
        } else {
            // Canta deja cand a plecat din launcher:
            // Revine DOAR daca utilizatorul a pus pauza si a apasat Play din nou,
            // sau a selectat o melodie diferita (titlul s-a schimbat):
            val titleChanged = title.isNotEmpty() && autoReturnInitialTitle.isNotEmpty() && !title.equals(autoReturnInitialTitle, ignoreCase = true)
            userSawPausedWhileArmed || titleChanged
        }

        // Declanșează revenirea STRICT dacă a fost detectată acțiunea explicită de Play
        if (explicitPlayPressed) {
            autoReturnArmed = false
            userSawPausedWhileArmed = false
            bgSawYtMusicNonPlaying = false
            lastAutoReturnTriggerTime = now
            mainHandler.removeCallbacks(autoReturnPollRunnable)
            mainHandler.postDelayed({
                bringLauncherToFront()
            }, 300L)
        }
    }

    private fun bringLauncherToFront() {
        val ctx = appContext ?: WazeNotificationService.instance ?: return
        try {
            syncActiveSessions(ctx)
        } catch (_: Exception) {}

        // 1. Overlay invizibil 1x1 pentru acordare statut vizibil pe Xiaomi HyperOS / MIUI
        var tempOverlayView: View? = null
        var wm: WindowManager? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(ctx)) {
            try {
                wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                tempOverlayView = View(ctx)
                val lp = WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                wm?.addView(tempOverlayView, lp)
            } catch (_: Exception) {
                tempOverlayView = null
            }
        }

        val targetTask = if (launcherTaskId != -1) launcherTaskId else MainActivity.currentTaskId
        try {
            if (targetTask != -1) {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.moveTaskToFront(targetTask, 0)
            }
        } catch (_: Exception) {}

        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra("FROM_YT_MUSIC_AUTO_RETURN", true)
        }

        // 2. PendingIntent cu background activity start explicit permis (Android 14/15/16)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                val pi = PendingIntent.getActivity(
                    ctx, 999, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    options.toBundle()
                )
                pi.send(ctx, 0, null, null, null, null, options.toBundle())
            } catch (_: Exception) {}
        }

        // 3. Direct startActivity
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {}

        // 4. Fallback FullScreenIntent notification (garanteaza revenirea rapida)
        // Se executa doar ca rezerva daca activitatea nu a revenit deja in prim-plan
        mainHandler.postDelayed({
            if (!MainActivity.isInForeground) {
                try {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (nm != null) {
                        val channelId = "bmw_return_channel"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val channel = NotificationChannel(
                                channelId,
                                "BMW Auto Return",
                                NotificationManager.IMPORTANCE_HIGH
                            ).apply {
                                setSound(null, null)
                                enableVibration(false)
                            }
                            nm.createNotificationChannel(channel)
                        }
                        val pi = PendingIntent.getActivity(
                            ctx, 998, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val notif = NotificationCompat.Builder(ctx, channelId)
                            .setSmallIcon(R.drawable.ic_launcher)
                            .setContentTitle("BMW Car Launcher")
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setCategory(NotificationCompat.CATEGORY_SERVICE)
                            .setFullScreenIntent(pi, true)
                            .setAutoCancel(true)
                            .setSilent(true)
                            .setTimeoutAfter(1500L)
                            .build()
                        nm.notify(998, notif)
                        mainHandler.postDelayed({
                            try { nm.cancel(998) } catch (_: Exception) {}
                        }, 1200L)
                    }
                } catch (_: Exception) {}
            }
        }, 350L)

        if (tempOverlayView != null && wm != null) {
            val viewToRemove = tempOverlayView
            val windowMgr = wm
            mainHandler.postDelayed({
                try {
                    windowMgr.removeView(viewToRemove)
                } catch (_: Exception) {}
            }, 600L)
        }

        mainHandler.postDelayed({
            try { syncActiveSessions(ctx) } catch (_: Exception) {}
        }, 300L)
        mainHandler.postDelayed({
            try { syncActiveSessions(ctx) } catch (_: Exception) {}
        }, 800L)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncFromController(registeredController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncFromController(registeredController)
        }

        override fun onSessionDestroyed() {
            registeredController = null
        }
    }

    fun sourceNameForPackage(pkg: String): String {
        val p = pkg.lowercase(Locale.getDefault())
        return when {
            p.contains("youtube.music") -> "YOUTUBE MUSIC"
            p.contains("youtube") || p.contains("smarttube") || p.contains("newpipe") -> "YOUTUBE"
            p.contains("spotify") -> "SPOTIFY"
            else -> "MEDIA PLAYER"
        }
    }

    private fun isControllerPlaying(mc: MediaController?): Boolean {
        val pbState = mc?.playbackState?.state ?: return false
        return (pbState == PlaybackState.STATE_PLAYING ||
                pbState == PlaybackState.STATE_BUFFERING ||
                pbState == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                pbState == PlaybackState.STATE_SKIPPING_TO_PREVIOUS)
    }

    fun attachCallback(mc: MediaController?) {
        if (mc == null) return
        if (registeredController != mc) {
            try {
                registeredController?.unregisterCallback(mediaCallback)
            } catch (_: Exception) {}
            registeredController = mc
            try {
                mc.registerCallback(mediaCallback)
            } catch (_: Exception) {}
        }
        syncFromController(mc)
    }

    fun syncFromController(mc: MediaController?) {
        val ctrl = mc ?: registeredController ?: return
        try {
            val pkg = ctrl.packageName
            val samePackage = (currentData.packageName == pkg)
            val meta = ctrl.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: if (samePackage) currentData.title else ""
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_WRITER)
                ?: if (samePackage) currentData.artist else ""
            val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: if (samePackage) currentData.artBitmap else null

            val pbState = ctrl.playbackState?.state
            val isPlaying = isControllerPlaying(ctrl)
            val source = sourceNameForPackage(pkg)

            checkAutoReturn(pkg, title, pbState, isPlaying)

            val errorMsg = ctrl.playbackState?.errorMessage?.toString() ?: ""
            if (pkg.contains("youtube.music", ignoreCase = true)) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAutoReturnTriggerTime < 5000L &&
                    (pbState == PlaybackState.STATE_ERROR || errorMsg.contains("upgrade", ignoreCase = true))
                ) {
                    val app = appContext ?: WazeNotificationService.instance
                    if (app != null) {
                        mainHandler.post {
                            Toast.makeText(
                                app,
                                "YouTube Music gratuit nu permite redarea în fundal (necesită Premium sau ReVanced).",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            if (title.isNotEmpty()) {
                update(
                    ExternalMediaData(
                        packageName = pkg,
                        sourceName = source,
                        title = title,
                        artist = artist,
                        artBitmap = art,
                        isPlaying = isPlaying,
                        mediaController = ctrl
                    )
                )
            }
        } catch (_: Exception) {}
    }

    fun update(data: ExternalMediaData) {
        currentData = data
        checkAutoReturn(data.packageName, data.title, data.mediaController?.playbackState?.state, data.isPlaying)
        listener?.invoke(data)
    }

    private fun isKnownMediaPackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.getDefault())
        return p.contains("youtube.music") ||
                p.contains("youtube") ||
                p.contains("smarttube") ||
                p.contains("newpipe") ||
                p.contains("spotify") ||
                p.contains("music")
    }

    fun selectBestController(sessions: List<MediaController>, appPackageName: String): MediaController? {
        val externalSessions = sessions.filter { it.packageName != appPackageName }
        if (externalSessions.isEmpty()) return null

        val pref = preferredPackage.lowercase(Locale.getDefault())

        // 1. Sesiune care ruleaza activ si corespunde aplicatiei preferate (YouTube sau YT Music)
        if (pref.isNotEmpty()) {
            externalSessions.firstOrNull {
                it.packageName.lowercase(Locale.getDefault()) == pref && isControllerPlaying(it)
            }?.let { return it }
        }

        // 2. Orice sesiune media cunoscuta (YouTube, YT Music, Spotify etc.) care ruleaza activ
        externalSessions.firstOrNull {
            isKnownMediaPackage(it.packageName) && isControllerPlaying(it)
        }?.let { return it }

        // 3. Orice sesiune externa care ruleaza activ
        externalSessions.firstOrNull { isControllerPlaying(it) }?.let { return it }

        // 4. Sesiune (chiar si pe pauza) a aplicatiei selectate de utilizator
        if (pref.isNotEmpty()) {
            externalSessions.firstOrNull {
                it.packageName.lowercase(Locale.getDefault()) == pref
            }?.let { return it }
        }

        // 5. Sesiunea curenta daca mai este activa
        currentData.mediaController?.let { currentCtrl ->
            externalSessions.firstOrNull { it.packageName == currentCtrl.packageName }?.let { return it }
        }

        // 6. Orice sesiune YouTube / YouTube Music / Spotify / Media
        return externalSessions.firstOrNull { isKnownMediaPackage(it.packageName) }
            ?: externalSessions.firstOrNull()
    }

    fun syncActiveSessions(context: Context): MediaController? {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val cn = ComponentName(context, WazeNotificationService::class.java)
            val sessions = msm?.getActiveSessions(cn) ?: emptyList()
            val best = selectBestController(sessions, context.packageName)
            if (best != null) {
                attachCallback(best)
                return best
            }
        } catch (_: Exception) {}
        return null
    }

    fun getActiveMusicController(context: Context): MediaController? {
        // 1. Cauta intai sesiunile active din MediaSessionManager (pentru a detecta comutarea intre YouTube si YT Music)
        syncActiveSessions(context)?.let { return it }

        // 2. Fallback pe controllerul salvat din notificare daca este disponibil
        currentData.mediaController?.let {
            attachCallback(it)
            return it
        }

        return null
    }
}

class WazeNotificationService : NotificationListenerService() {

    companion object {
        var instance: WazeNotificationService? = null

        fun refresh() {
            try {
                instance?.let { srv ->
                    ExternalMediaManager.syncActiveSessions(srv)
                    srv.activeNotifications?.forEach { sbn ->
                        srv.onNotificationPosted(sbn)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        if (controllers != null) {
            val best = ExternalMediaManager.selectBestController(controllers, packageName)
            if (best != null) {
                ExternalMediaManager.attachCallback(best)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val cn = ComponentName(this, WazeNotificationService::class.java)
            msm?.addOnActiveSessionsChangedListener(sessionsChangedListener, cn)
        } catch (_: Exception) {}
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            msm?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
        if (instance == this) instance = null
    }

    private fun determineDirectionSymbol(textToAnalyze: String): String? {
        val t = textToAnalyze.lowercase(Locale.getDefault())
        return when {
            // Intoarcere (U-turn)
            t.contains("u-turn") || t.contains("intoarcere") || t.contains("întoarcere") ||
            t.contains("intoarceti") || t.contains("întoarceți") -> "⤺"

            // Sens giratoriu
            t.contains("giratoriu") || t.contains("roundabout") -> "⟳"

            // Viraj strans
            t.contains("strans la stanga") || t.contains("strâns la stânga") || t.contains("sharp left") -> "⮢"
            t.contains("strans la dreapta") || t.contains("strâns la dreapta") || t.contains("sharp right") -> "⮣"

            // Usor / Pastrati banda / Rampa / Iesire
            t.contains("usor la stanga") || t.contains("ușor la stânga") || t.contains("usor stanga") || t.contains("ușor stânga") ||
            t.contains("pastrati stanga") || t.contains("păstrați stânga") || t.contains("tineti stanga") || t.contains("țineți stânga") ||
            t.contains("banda din stanga") || t.contains("banda din stânga") || t.contains("rampa din stanga") || t.contains("rampa din stânga") ||
            t.contains("slight left") || t.contains("keep left") || t.contains("bear left") -> "↖"

            t.contains("usor la dreapta") || t.contains("ușor la dreapta") || t.contains("usor dreapta") || t.contains("ușor dreapta") ||
            t.contains("pastrati dreapta") || t.contains("păstrați dreapta") || t.contains("tineti dreapta") || t.contains("țineți dreapta") ||
            t.contains("banda din dreapta") || t.contains("rampa din dreapta") || t.contains("iesiti") || t.contains("ieșiți") ||
            t.contains("iesirea") || t.contains("ieșirea") || t.contains("slight right") || t.contains("keep right") || t.contains("bear right") || t.contains("exit") -> "↗"

            // Viraj standard stanga / dreapta
            t.contains("stanga") || t.contains("stânga") || t.contains("left") -> "↰"
            t.contains("dreapta") || t.contains("right") -> "↱"

            // Inainte / Continuati
            t.contains("inainte") || t.contains("înainte") || t.contains("continuati") || t.contains("continuați") ||
            t.contains("mergeti") || t.contains("mergeți") || t.contains("urmati") || t.contains("urmați") ||
            t.contains("straight") || t.contains("continue") || t.contains("head") -> "↑"

            // Destinatie
            t.contains("ati ajuns") || t.contains("ați ajuns") || t.contains("ati sosit") || t.contains("ați sosit") ||
            t.contains("destinatie") || t.contains("destinație") || t.contains("arrived") || t.contains("destination") -> "🏁"

            else -> null
        }
    }

    private fun extractNavIconBitmap(notification: Notification, extras: android.os.Bundle): Bitmap? {
        val mapsContext = try {
            createPackageContext("com.google.android.apps.maps", Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Exception) {
            this
        }

        // 1. notification.largeIcon (Bitmap) & notification.getLargeIcon() (Icon)
        @Suppress("DEPRECATION")
        val largeBmp = notification.largeIcon
        if (largeBmp != null) {
            return largeBmp
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val largeIcon = notification.getLargeIcon()
            if (largeIcon != null) {
                val bmp = renderIconToBitmap(mapsContext, largeIcon)
                if (bmp != null) return bmp
            }
        }

        // 2. extras EXTRA_LARGE_ICON
        try {
            val iconObj = extras.get(Notification.EXTRA_LARGE_ICON)
            if (iconObj is Bitmap) return iconObj
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && iconObj is Icon) {
                val bmp = renderIconToBitmap(mapsContext, iconObj)
                if (bmp != null) return bmp
            }
        } catch (_: Exception) {}

        // 3. notification.smallIcon (in Google Maps, smallIcon contine sageata manevrei curente)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val smallIcon = notification.smallIcon
            if (smallIcon != null) {
                val bmp = renderIconToBitmap(mapsContext, smallIcon)
                if (bmp != null) return bmp
            }
        }

        // 4. extras EXTRA_SMALL_ICON
        try {
            val iconObj = extras.get("android.smallIcon") ?: extras.get(Notification.EXTRA_SMALL_ICON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && iconObj is Icon) {
                val bmp = renderIconToBitmap(mapsContext, iconObj)
                if (bmp != null) return bmp
            }
        } catch (_: Exception) {}

        // 5. extras EXTRA_PICTURE
        try {
            val pic = extras.get(Notification.EXTRA_PICTURE)
            if (pic is Bitmap) return pic
        } catch (_: Exception) {}

        // 6. Resursa directa notification.icon din mapsContext
        @Suppress("DEPRECATION")
        val iconRes = notification.icon
        if (iconRes != 0) {
            try {
                val d = androidx.core.content.ContextCompat.getDrawable(mapsContext, iconRes)
                if (d != null) {
                    return drawableToBitmap(d)
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun renderIconToBitmap(mapsContext: Context, icon: Icon): Bitmap? {
        return try {
            val drawable = icon.loadDrawable(mapsContext) ?: icon.loadDrawable(this) ?: return null
            drawableToBitmap(drawable)
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val size = 96
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else size
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else size
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // 1. Asculta notificarile Google Maps
        if (pkg == "com.google.android.apps.maps") {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
            val ticker = notification.tickerText?.toString()?.trim() ?: ""

            val combinedText = "$title $text $subText $bigText $ticker"
            val directionSymbol = determineDirectionSymbol(combinedText) ?: "↑"
            val navIconBitmap = extractNavIconBitmap(notification, extras)

            val sourceName = "GOOGLE MAPS"

            if (title.isNotEmpty() || text.isNotEmpty()) {
                val navData = WazeNavData(
                    distance = if (title.isNotEmpty()) title else "Înainte",
                    road = if (text.isNotEmpty()) text else "Traseu activ",
                    eta = if (subText.isNotEmpty()) subText else "Navigare activă",
                    isNavigating = true,
                    iconBitmap = navIconBitmap,
                    sourceApp = sourceName,
                    directionSymbol = directionSymbol
                )
                WazeNavManager.update(navData)
            }
        }

        // 2. Asculta notificarile Media de la YouTube Music, YouTube, Spotify sau playere video/audio externe
        if (pkg != packageName) {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            val isMediaCategory = notification.category == Notification.CATEGORY_TRANSPORT
            val pLower = pkg.lowercase(Locale.getDefault())
            val isKnownMediaApp = pLower.contains("youtube") || pLower.contains("smarttube") ||
                    pLower.contains("newpipe") || pLower.contains("music") || pLower.contains("spotify")

            if (isMediaCategory || isKnownMediaApp || hasMediaSession) {
                var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

                var isPlaying = false
                var mediaCtrl: android.media.session.MediaController? = null
                try {
                    val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                    }
                    if (token != null) {
                        val mc = android.media.session.MediaController(this, token)
                        mediaCtrl = mc
                        ExternalMediaManager.attachCallback(mc)
                        val state = mc.playbackState?.state
                        isPlaying = (state == PlaybackState.STATE_PLAYING ||
                                state == PlaybackState.STATE_BUFFERING ||
                                state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                                state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS)

                        val meta = mc.metadata
                        if (title.isEmpty()) {
                            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                                ?: ""
                        }
                        if (text.isEmpty()) {
                            text = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                                ?: meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                                ?: ""
                        }
                    } else {
                        val actions = notification.actions
                        if (actions != null) {
                            isPlaying = actions.any { action ->
                                val actTitle = action.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
                                actTitle.contains("pause") || actTitle.contains("pauză")
                            }
                        }
                    }
                } catch (_: Exception) {}

                if (title.isNotEmpty()) {
                    // Daca alta aplicatie media ruleaza deja si aceasta notificare este doar pe pauza in fundal, nu o lasam sa suprascrie playerul activ
                    if (!isPlaying && ExternalMediaManager.currentData.isPlaying &&
                        ExternalMediaManager.currentData.packageName.isNotEmpty() &&
                        ExternalMediaManager.currentData.packageName != pkg) {
                        return
                    }

                    var art: Bitmap? = null
                    if (mediaCtrl != null) {
                        try {
                            val meta = mediaCtrl.metadata
                            art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                        } catch (_: Exception) {}
                    }

                    if (art == null) {
                        try {
                            @Suppress("DEPRECATION")
                            val picObj = extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_PICTURE)
                            if (picObj is Bitmap) {
                                art = picObj
                            }
                        } catch (_: Exception) {}
                    }

                    if (art == null) {
                        try {
                            @Suppress("DEPRECATION")
                            val iconObj = extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON)
                            if (iconObj is Bitmap) {
                                art = iconObj
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && iconObj is Icon) {
                                val drawable = iconObj.loadDrawable(this)
                                if (drawable != null) {
                                    val bmp = Bitmap.createBitmap(
                                        drawable.intrinsicWidth.coerceAtLeast(100),
                                        drawable.intrinsicHeight.coerceAtLeast(100),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    val c = android.graphics.Canvas(bmp)
                                    drawable.setBounds(0, 0, c.width, c.height)
                                    drawable.draw(c)
                                    art = bmp
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (art == null && ExternalMediaManager.currentData.packageName == pkg) {
                        art = ExternalMediaManager.currentData.artBitmap
                    }

                    val source = ExternalMediaManager.sourceNameForPackage(pkg)

                    ExternalMediaManager.update(
                        ExternalMediaData(
                            packageName = pkg,
                            sourceName = source,
                            title = title,
                            artist = text,
                            artBitmap = art,
                            isPlaying = isPlaying,
                            mediaController = mediaCtrl
                        )
                    )

                    // Daca un player exterior ruleaza activ, opreste radioul intern
                    if (isPlaying) {
                        val stopRadioIntent = Intent("com.bmw.carlauncher.ACTION_STOP_RADIO").apply {
                            setPackage(packageName)
                        }
                        sendBroadcast(stopRadioIntent)
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        if (pkg == "com.google.android.apps.maps") {
            val navData = WazeNavData(
                distance = "--",
                road = "Navigație oprită",
                eta = "Porniți Google Maps pentru indicații",
                isNavigating = false,
                iconBitmap = null,
                sourceApp = "GOOGLE MAPS",
                directionSymbol = "↑"
            )
            WazeNavManager.update(navData)
        }

        if (pkg == ExternalMediaManager.currentData.packageName) {
            ExternalMediaManager.update(
                ExternalMediaData(
                    packageName = "",
                    sourceName = "",
                    title = "",
                    artist = "",
                    artBitmap = null,
                    isPlaying = false
                )
            )
            // Daca mai exista o alta sesiune media activa (ex. YouTube sau YT Music), sincronizeaza cu aceasta
            ExternalMediaManager.syncActiveSessions(this)
        }
    }
}

