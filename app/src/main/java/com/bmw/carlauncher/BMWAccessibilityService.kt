package com.bmw.carlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BMWAccessibilityService : AccessibilityService() {

    private var isClearingRecents = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val btDynamicReceiver = ReturnHomeReceiver()
    private var isBtReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerBtDynamicReceiver()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerBtDynamicReceiver()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
            Log.d(TAG, "BMWAccessibilityService connected, key filter and window retrieve active.")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AccessibilityServiceInfo", e)
        }
    }

    private fun registerBtDynamicReceiver() {
        if (isBtReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(ReturnHomeReceiver.ACTION_HID_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(btDynamicReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(btDynamicReceiver, filter)
            }
            isBtReceiverRegistered = true
            Log.i(TAG, "Dynamic Bluetooth receiver registered in BMWAccessibilityService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register dynamic Bluetooth receiver in BMWAccessibilityService", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "GLOBAL onKeyEvent: keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}), action=${event.action}")
        when (event.keyCode) {
            KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_F11,
            KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Log.i(TAG, "Return key ${event.keyCode} detected! Returning to BMW Car Launcher...")
                    returnToLauncher()
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    fun returnToLauncher() {
        Log.i(TAG, "returnToLauncher() called, bringing MainActivity to foreground...")
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("EXTRA_F12_RETURN", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity directly, attempting launcher fallback", e)
            try {
                val fallback = packageManager.getLaunchIntentForPackage(packageName)
                fallback?.apply {
                    putExtra("EXTRA_F12_RETURN", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                if (fallback != null) {
                    startActivity(fallback)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback launch also failed", e2)
            }
        }
    }

    fun startClearRecentsAndLock() {
        Log.i(TAG, "startClearRecentsAndLock() called")
        isClearingRecents = true

        // 1. Deschide ecranul Recents
        val recentsSuccess = performGlobalAction(GLOBAL_ACTION_RECENTS)
        Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_RECENTS) returned: $recentsSuccess")

        // 2. Prima încercare de apăsare a butonului de curățare din Recents
        mainHandler.postDelayed({
            val clicked = tryClickClearButton()
            Log.i(TAG, "Attempt 1 to click clear button in Recents: $clicked")
        }, 350L)

        // 3. A doua încercare și revenire pe ecranul Home
        mainHandler.postDelayed({
            if (isClearingRecents) {
                val clicked = tryClickClearButton()
                Log.i(TAG, "Attempt 2 to click clear button in Recents: $clicked")
            }
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 750L)

        // 4. Blochează ecranul după ce Home s-a așezat
        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val locked = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                Log.i(TAG, "clearRecentsAndLockScreen: lockScreen executed ($locked)")
            }
            isClearingRecents = false
        }, 1200L)
    }

    private fun tryClickClearButton(): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            // Căutare după ID-ul specific Xiaomi MIUI / HyperOS
            val nodes = root.findAccessibilityNodeInfosByViewId("com.miui.home:id/clearAnimView")
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "clearAnimView clicked successfully: $clicked")
                        isClearingRecents = false
                        return true
                    }
                }
            }

            // Căutare alternativă după text sau descriere
            val textCandidates = listOf("Clear all Recents", "Ștergeți", "Șterge", "Curăță", "Clear all", "Clear")
            for (text in textCandidates) {
                val found = root.findAccessibilityNodeInfosByText(text)
                for (node in found) {
                    if (node.isClickable) {
                        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "Clear button found by text '$text' and clicked: $clicked")
                        isClearingRecents = false
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in tryClickClearButton: ${e.message}")
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isClearingRecents && event != null) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.contains("miui.home") || pkg.contains("launcher") || pkg.contains("systemui")) {
                mainHandler.postDelayed({
                    if (isClearingRecents) {
                        tryClickClearButton()
                    }
                }, 150L)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "BMWAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (isBtReceiverRegistered) {
            try {
                unregisterReceiver(btDynamicReceiver)
            } catch (_: Exception) {}
            isBtReceiverRegistered = false
        }
        if (instance == this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BMWAccessibility"
        var instance: BMWAccessibilityService? = null
            private set

        fun bringToForeground(context: Context) {
            val s = instance
            if (s != null) {
                s.returnToLauncher()
            } else {
                try {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        putExtra("EXTRA_F12_RETURN", true)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                    try {
                        val pi = PendingIntent.getActivity(
                            context,
                            777,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        if (Build.VERSION.SDK_INT >= 34) {
                            val opts = ActivityOptions.makeBasic().apply {
                                pendingIntentBackgroundActivityStartMode =
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            }
                            pi.send(context, 0, null, null, null, null, opts.toBundle())
                        } else {
                            pi.send()
                        }
                    } catch (_: Exception) {}
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "bringToForeground fallback error", e)
                }
            }
        }

        fun goToHomeScreen(): Boolean {
            val s = instance ?: return false
            return try {
                val res = s.performGlobalAction(GLOBAL_ACTION_HOME)
                Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_HOME) returned: $res")
                res
            } catch (e: Exception) {
                Log.e(TAG, "Error performing GLOBAL_ACTION_HOME", e)
                false
            }
        }

        fun lockScreen(): Boolean {
            val s = instance ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return try {
                    val result = s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) returned: $result")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "Error locking screen via AccessibilityService: ${e.message}")
                    false
                }
            }
            return false
        }

        fun clearRecentsAndLockScreen() {
            val s = instance
            if (s != null) {
                s.startClearRecentsAndLock()
            } else {
                Log.w(TAG, "clearRecentsAndLockScreen: instance is null, falling back to home intent")
                MainActivity.instance?.let { act ->
                    try {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        act.startActivity(homeIntent)
                    } catch (_: Exception) {}
                }
            }
        }

        fun goHomeAndLockScreen() {
            val s = instance
            if (s != null) {
                try {
                    s.performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (e: Exception) {
                    Log.e(TAG, "Error performing home", e)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val locked = s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                            Log.i(TAG, "goHomeAndLockScreen: lockScreen returned $locked")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error performing lock screen", e)
                    }
                }, 450L)
            } else {
                Log.w(TAG, "goHomeAndLockScreen: BMWAccessibilityService instance is null! Accessibility service not active.")
            }
        }

        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, BMWAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledService = ComponentName.unflattenFromString(componentNameString)
                if (enabledService != null && enabledService == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
