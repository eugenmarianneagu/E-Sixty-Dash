package com.bmw.carlauncher.notification

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class CarNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "CarNotifService"
        var isConnected: Boolean = false
            private set
        var instance: CarNotificationListenerService? = null
            private set

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            val myPackage = context.packageName
            return enabledListeners.contains(myPackage)
        }

        fun rebindService(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    val component = android.content.ComponentName(context, CarNotificationListenerService::class.java)
                    if (!isConnected) {
                        Log.i(TAG, "Listener neconectat - fortare rebind prin component toggle...")
                        val pm = context.packageManager
                        pm.setComponentEnabledSetting(
                            component,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                        pm.setComponentEnabledSetting(
                            component,
                            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP
                        )
                    }
                    requestRebind(component)
                    Log.i(TAG, "requestRebind executat pentru CarNotificationListenerService")
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare rebindService: ${e.message}")
                }
            }
        }
    }

    fun getActiveStatusBarNotificationCount(): Int {
        val active = try {
            activeNotifications ?: return 0
        } catch (e: Exception) {
            Log.w(TAG, "Eroare getActiveNotifications: ${e.message}")
            return 0
        }

        val dismissible = active.filter { sbn ->
            if (sbn == null) return@filter false
            if (sbn.packageName == packageName) return@filter false
            if (sbn.isOngoing) return@filter false
            val n = sbn.notification ?: return@filter false
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return@filter false
            if ((n.flags and Notification.FLAG_NO_CLEAR) != 0) return@filter false
            if (sbn.packageName == "android" && (sbn.tag?.contains("Aggregate") == true)) return@filter false
            if (sbn.packageName.contains("mi_connect") && (sbn.tag?.contains("Aggregate") == true)) return@filter false
            true
        }

        val groupKeysWithChildren = dismissible
            .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && !it.groupKey.isNullOrEmpty() }
            .map { "${it.packageName}:${it.groupKey}" }
            .toSet()

        val effectiveNotifications = dismissible.filter { sbn ->
            val isSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            val key = "${sbn.packageName}:${sbn.groupKey}"
            !(isSummary && groupKeysWithChildren.contains(key))
        }

        return effectiveNotifications.size
    }

    fun syncActiveNotificationsCount() {
        try {
            val active = activeNotifications ?: emptyArray()
            val dismissible = active.filter { sbn ->
                if (sbn == null) return@filter false
                if (sbn.packageName == packageName) return@filter false
                if (sbn.isOngoing) return@filter false
                val n = sbn.notification ?: return@filter false
                if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return@filter false
                if ((n.flags and Notification.FLAG_NO_CLEAR) != 0) return@filter false
                if (sbn.packageName == "android" && (sbn.tag?.contains("Aggregate") == true)) return@filter false
                if (sbn.packageName.contains("mi_connect") && (sbn.tag?.contains("Aggregate") == true)) return@filter false
                true
            }

            val groupKeysWithChildren = dismissible
                .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && !it.groupKey.isNullOrEmpty() }
                .map { "${it.packageName}:${it.groupKey}" }
                .toSet()

            val effectiveNotifications = dismissible.filter { sbn ->
                val isSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                val key = "${sbn.packageName}:${sbn.groupKey}"
                !(isSummary && groupKeysWithChildren.contains(key))
            }

            val currentParsedList = effectiveNotifications.mapNotNull { parseCarNotification(it) }
            CarNotificationHub.syncNotifications(currentParsedList)
            Log.d(TAG, "syncActiveNotificationsCount: parsed=${currentParsedList.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Eroare syncActiveNotificationsCount: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected = true
        Log.i(TAG, ">>> CarNotificationListenerService CONECTAT la sistem! <<<")
        syncActiveNotificationsCount()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
        isConnected = false
        Log.w(TAG, ">>> CarNotificationListenerService DECONECTAT! Se incearca reconectarea... <<<")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                rebindService(this)
            } catch (e: Exception) {
                Log.e(TAG, "Eroare requestRebind in onListenerDisconnected: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        syncActiveNotificationsCount()

        val parsed = parseCarNotification(sbn) ?: return
        val n = sbn.notification ?: return
        val isMessagingApp = isMessageSource(sbn.packageName, n)
        if (isMessagingApp) {
            Log.i(TAG, "Notificare mesaje/voce receptionata: App=${parsed.appName}, De la=${parsed.sender}")
            CarNotificationHub.postNotification(parsed)
        }
    }

    fun parseCarNotification(sbn: StatusBarNotification): CarNotification? {
        val pkg = sbn.packageName ?: return null
        if (pkg == packageName) return null
        if (sbn.isOngoing) return null

        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        if ((n.flags and Notification.FLAG_NO_CLEAR) != 0) return null

        val extras = n.extras ?: return null

        // 1. Extragere text și expeditor
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }

        var rawText = when {
            bigText.isNotEmpty() && text.isNotEmpty() && !bigText.contains(text, ignoreCase = true) -> "$text - $bigText"
            bigText.isNotEmpty() -> bigText
            text.isNotEmpty() -> text
            !textLines.isNullOrEmpty() -> textLines.joinToString("\n")
            else -> ""
        }

        var sender = rawTitle
        var message = rawText
        var hasAudioMime = false

        // Verificare NotificationCompat.MessagingStyle pentru detalii bogate (WhatsApp, SMS etc.)
        try {
            val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            if (messagingStyle != null) {
                val messages = messagingStyle.messages
                if (messages.isNotEmpty()) {
                    val lastMsg = messages.last()
                    val msgText = lastMsg.text?.toString()?.trim()
                    if (!msgText.isNullOrEmpty()) {
                        message = msgText
                    }
                    val mime = lastMsg.dataMimeType
                    if (mime != null && mime.startsWith("audio/")) {
                        hasAudioMime = true
                    }
                    val personName = lastMsg.person?.name?.toString()?.trim()
                    if (!personName.isNullOrEmpty()) {
                        sender = personName
                    } else {
                        val convTitle = messagingStyle.conversationTitle?.toString()?.trim()
                        if (!convTitle.isNullOrEmpty()) {
                            sender = convTitle
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eroare parsare MessagingStyle: ${e.message}")
        }

        if (sender.isEmpty() && message.isEmpty()) {
            return null
        }

        // Ignorăm texte de rezumat generic dacă există mesaje individuale
        if (message.matches(Regex("^\\d+\\s+(new messages|mesaje noi|conversations|conversații|unread messages|unread emails|e-mailuri noi|mesaje necitite).*", RegexOption.IGNORE_CASE))) {
            return null
        }

        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            val isCountOnly = message.contains("messages", ignoreCase = true) ||
                    message.contains("mesaje", ignoreCase = true) ||
                    message.isEmpty()
            val hasDirectMessage = extras.containsKey(Notification.EXTRA_MESSAGES) ||
                    extras.containsKey("android.messagingStyleUser")
            if (isCountOnly && !hasDirectMessage) {
                return null
            }
        }

        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            when {
                pkg.contains("facebook", ignoreCase = true) -> "Facebook"
                pkg.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
                pkg.contains("gmail", ignoreCase = true) || pkg == "com.google.android.gm" -> "Gmail"
                pkg.contains("homeassistant", ignoreCase = true) -> "Home Assistant"
                else -> "Notificare"
            }
        }

        if (message.isEmpty() && sender.isNotEmpty()) {
            message = sender
            sender = appName
        } else if (sender.isEmpty()) {
            sender = appName
        }

        val isVoice = hasAudioMime || isVoiceMessage(message, n)

        val appIcon = try {
            pm.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }

        var playAction: android.app.PendingIntent? = null
        val actions = n.actions
        if (actions != null) {
            for (action in actions) {
                val actionTitle = action.title?.toString()?.lowercase() ?: ""
                if (actionTitle.contains("play") ||
                    actionTitle.contains("redare") ||
                    actionTitle.contains("ascult") ||
                    actionTitle.contains("listen")) {
                    playAction = action.actionIntent
                    break
                }
            }
        }

        return CarNotification(
            key = sbn.key,
            packageName = pkg,
            appName = appName,
            sender = sender,
            message = message,
            timestamp = sbn.postTime,
            isVoiceMessage = isVoice,
            contentIntent = n.contentIntent,
            playActionIntent = playAction,
            appIcon = appIcon
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null) {
            CarNotificationHub.removeNotification(sbn.key)
        }
        syncActiveNotificationsCount()
    }

    private fun isMessageSource(pkg: String, n: Notification): Boolean {
        // 1. Daca notificarea are stil MessagingStyle (WhatsApp, SMS, Telegram, Messenger etc.)
        try {
            if (NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null) {
                return true
            }
        } catch (_: Exception) {}

        // 2. Pentru teste adb / shell
        if (pkg == "com.android.shell") return true

        // 3. WhatsApp si WhatsApp Business
        if (pkg.contains("whatsapp", ignoreCase = true)) return true

        // 4. SMS Apps
        if (pkg.contains("messaging", ignoreCase = true) ||
            pkg.contains("mms", ignoreCase = true) ||
            pkg == "com.google.android.apps.messaging" ||
            pkg == "com.samsung.android.messaging") return true

        // 5. Gmail si aplicatii de email
        if (pkg == "com.google.android.gm" ||
            pkg.contains("gmail", ignoreCase = true) ||
            pkg.contains("email", ignoreCase = true) ||
            pkg.contains("mail", ignoreCase = true)) return true

        // 6. Home Assistant
        if (pkg.contains("homeassistant", ignoreCase = true) ||
            pkg == "io.homeassistant.companion.android" ||
            pkg == "io.homeassistant.companion.android.minimal") return true

        // 7. Alte aplicatii populare de chat
        if (pkg.contains("telegram", ignoreCase = true) ||
            pkg.contains("securesms", ignoreCase = true) || // Signal
            pkg.contains("viber", ignoreCase = true) ||
            pkg.contains("orca", ignoreCase = true) ||
            pkg.contains("discord", ignoreCase = true) ||
            pkg.contains("skype", ignoreCase = true)) return true

        // 8. Categorie Android directa
        if (n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_EMAIL ||
            n.category == Notification.CATEGORY_EVENT ||
            n.category == Notification.CATEGORY_REMINDER ||
            n.category == Notification.CATEGORY_ALARM) return true

        return false
    }

    private fun isVoiceMessage(text: String, n: Notification): Boolean {
        val lower = text.lowercase()
        if (lower.contains("mesaj vocal") ||
            lower.contains("voice message") ||
            lower.contains("audio message") ||
            lower.contains("mesaj audio") ||
            lower.startsWith("🎤") ||
            lower.contains("🎤")) {
            return true
        }
        return false
    }
}
