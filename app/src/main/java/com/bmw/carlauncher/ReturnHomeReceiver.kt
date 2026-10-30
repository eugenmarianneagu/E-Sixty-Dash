package com.bmw.carlauncher

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class ReturnHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "ReturnHomeReceiver triggered with action: $action")

        if (action == "com.bmw.carlauncher.ACTION_LOCK_SCREEN") {
            Log.i(TAG, "Direct ACTION_LOCK_SCREEN: calling clearRecentsAndLockScreen()")
            BMWAccessibilityService.clearRecentsAndLockScreen()
            return
        }

        val prefs = context.getSharedPreferences("agama_settings", Context.MODE_PRIVATE)

        val isA2dpConnect = (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED)
        val isAclConnect = (action == BluetoothDevice.ACTION_ACL_CONNECTED)

        // ==============================================================
        // 1. CONECTARE BLUETOOTH LA DISPOZITIVUL SELECTAT
        // ==============================================================
        if (isAclConnect || isA2dpConnect) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            val devAddress = device?.address ?: ""
            Log.i(TAG, "Bluetooth device connected ($action): '$devName', address=$devAddress")

            val savedCarBt = prefs.getString("car_bt_address", null)
            val isTargetDevice = if (!savedCarBt.isNullOrEmpty()) {
                devAddress.equals(savedCarBt, ignoreCase = true)
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv ignorat (nu corespunde MAC-ului maÈ™inii): '$devName', adresa=$devAddress")
                return
            }

            val now = SystemClock.elapsedRealtime()
            val lastDisconnect = MainActivity.lastDisconnectTimestamp
            val prefsDisconnect = prefs.getLong("last_disconnect_elapsed_time", 0L)
            val effectiveDisconnect = maxOf(lastDisconnect, prefsDisconnect)
            val cooldownMs = 15000L // 15 secunde cooldown Ã®mpotriva repornirilor din condensatorii convertorului DC-DC

            if (effectiveDisconnect > 0L && now - effectiveDisconnect < cooldownMs) {
                val secLeft = (cooldownMs - (now - effectiveDisconnect)) / 1000L
                Log.w(TAG, "Conectare ignoratÄƒ (Ã®n cooldown de deconectare, $secLeft secunde rÄƒmase): '$devName', adresa=$devAddress")
                return
            }

            Log.i(TAG, "Dispozitiv maÈ™inÄƒ detectat ($devAddress)! Pornire muzicÄƒ È™i aducere Ã®n prim-plan...")
            MainActivity.lastDisconnectTimestamp = 0L
            prefs.edit().remove("last_disconnect_elapsed_time").apply()
            MainActivity.isCarConnectedState = true

            // A. PorneÈ™te redarea radio / muzicÄƒ
            try {
                val radioIntent = Intent(context, RadioService::class.java).apply {
                    this.action = RadioService.ACTION_START_PLAY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(radioIntent)
                } else {
                    context.startService(radioIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Eroare la pornirea RadioService: ${e.message}")
            }

            // B. NotificÄƒ instanÈ›a dacÄƒ e deja activÄƒ
            MainActivity.instance?.let { act ->
                act.runOnUiThread {
                    act.startRadioPlayback()
                }
            }

            // C. Aduce aplicaÈ›ia Ã®n prim-plan
            BMWAccessibilityService.bringToForeground(context)
            return
        }

        // ==============================================================
        // 2. DECONECTARE BLUETOOTH DE LA DISPOZITIVUL SELECTAT
        // ==============================================================
        val isAclDisconnect = (action == BluetoothDevice.ACTION_ACL_DISCONNECTED)
        val isA2dpDisconnect = (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED)
        val isBtOff = (action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED &&
                (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_OFF ||
                 intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF))

        if (isAclDisconnect || isA2dpDisconnect || isBtOff) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            val devAddress = device?.address ?: ""
            Log.i(TAG, "Bluetooth device disconnected ($action): '$devName', address=$devAddress")

            val savedCarBt = prefs.getString("car_bt_address", null)
            val isTargetDevice = if (isBtOff) {
                true
            } else if (!savedCarBt.isNullOrEmpty()) {
                devAddress.equals(savedCarBt, ignoreCase = true)
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv deconectat ignorat: '$devName', adresa=$devAddress")
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (!MainActivity.isCarConnectedState && now - MainActivity.lastDisconnectTimestamp < 5000L) {
                Log.d(TAG, "Deconectare $action deja procesatÄƒ acum ${now - MainActivity.lastDisconnectTimestamp}ms, ignorare duplicat.")
                return
            }

            val pendingResult = goAsync()
            Log.i(TAG, "Dispozitiv È›intÄƒ deconectat ($devAddress)! Oprire muzicÄƒ, curÄƒÈ›are Recents È™i blocare telefon...")
            MainActivity.isCarConnectedState = false
            MainActivity.lastDisconnectTimestamp = now
            prefs.edit().putLong("last_disconnect_elapsed_time", now).apply()

            // A. OpreÈ™te radioul / muzica la deconectare
            try {
                context.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(context.packageName))
            } catch (_: Exception) {}

            MainActivity.instance?.let { act ->
                act.runOnUiThread {
                    act.stopAllAudioPlayers()
                    try {
                        act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } catch (_: Exception) {}
                    try {
                        act.finishAndRemoveTask()
                    } catch (_: Exception) {
                        try { act.moveTaskToBack(true) } catch (_: Exception) {}
                    }
                }
            }

            // B. CurÄƒÈ›Äƒ aplicaÈ›iile din Recents È™i blocheazÄƒ ecranul
            BMWAccessibilityService.clearRecentsAndLockScreen()

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    pendingResult.finish()
                } catch (_: Exception) {}
            }, 2500L)

            return
        }

        // ==============================================================
        // 3. COMENZI EXPLICITE DE REVENIRE (F12 / RETURN_HOME)
        // ==============================================================
        if (action == "com.bmw.carlauncher.ACTION_F12" || action == "com.bmw.carlauncher.RETURN_HOME") {
            launchMainActivity(context)
        }
    }

    private fun launchMainActivity(context: Context) {
        BMWAccessibilityService.bringToForeground(context)
    }

    companion object {
        private const val TAG = "ReturnHomeReceiver"
    }
}