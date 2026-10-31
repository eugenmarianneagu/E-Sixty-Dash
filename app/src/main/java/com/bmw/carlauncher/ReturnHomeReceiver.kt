package com.bmw.carlauncher

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

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
        val isHidConnect = (action == ACTION_HID_CONNECTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED)
        val isAclConnect = (action == BluetoothDevice.ACTION_ACL_CONNECTED)

        // ==============================================================
        // 1. CONECTARE BLUETOOTH LA DISPOZITIVUL SELECTAT
        // ==============================================================
        if (isAclConnect || isA2dpConnect || isHidConnect) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            var devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            val devAddress = device?.address ?: ""
            if (devName.isEmpty() && devAddress.isNotEmpty()) {
                devName = try {
                    BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(devAddress)?.name ?: ""
                } catch (_: Exception) { "" }
            }
            Log.i(TAG, "Bluetooth device connected ($action): '$devName', address=$devAddress")

            val savedCarBt = prefs.getString("car_bt_address", null)
            val isEsp32Mac = devAddress.equals("AC:A7:04:13:3D:59", ignoreCase = true)
            val isTargetDevice = if (!savedCarBt.isNullOrEmpty()) {
                devAddress.equals(savedCarBt, ignoreCase = true) ||
                isEsp32Mac ||
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            } else {
                isEsp32Mac ||
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv ignorat (nu corespunde MAC-ului mașinii): '$devName', adresa=$devAddress")
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (MainActivity.isCarConnectedState && now - lastConnectHandledTimestamp < 1500L) {
                Log.d(TAG, "Conectare $action deja procesată acum ${now - lastConnectHandledTimestamp}ms, se aduce în prim-plan.")
                BMWAccessibilityService.bringToForeground(context)
                return
            }
            lastConnectHandledTimestamp = now

            val pendingResult = try { goAsync() } catch (_: Exception) { null }

            // Menține procesorul activ temporar pentru a permite pornirea redării dacă telefonul este blocat
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BMW:ConnectWakeLock")
                wl?.acquire(3500L)
            } catch (_: Exception) {}

            Log.i(TAG, "Dispozitiv mașină detectat ($devAddress)! Pornire muzică și aducere în prim-plan...")
            MainActivity.lastDisconnectTimestamp = 0L
            prefs.edit().remove("last_disconnect_elapsed_time").apply()
            MainActivity.isCarConnectedState = true

            // A. Pornește redarea radio / muzică în funcție de ultima sursă activă
            val lastSource = prefs.getString("last_active_media_source", "RADIO") ?: "RADIO"
            Log.i(TAG, "Reluare muzică la conectare (telefon blocat/deblocat), sursa: $lastSource")

            if (lastSource == "LOCAL_PLAYER") {
                // Menține serviciul în prim-plan pentru a preveni înghețarea de către Android 14 când telefonul e blocat
                try {
                    val standbyIntent = Intent(context, RadioService::class.java).apply {
                        this.action = RadioService.ACTION_RESTART_STANDBY
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(standbyIntent)
                    } else {
                        context.startService(standbyIntent)
                    }
                } catch (_: Exception) {}

                try {
                    LocalMusicPlayer.playOrResumeLastTrack(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare la reluare LocalMusicPlayer: ${e.message}")
                }
            } else if (lastSource == "EXTERNAL_APP") {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                } catch (_: Exception) {}
            } else {
                // RadioService (implicit)
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
            }

            // B. Notifică instanța dacă e deja activă
            MainActivity.instance?.let { act ->
                act.runOnUiThread {
                    act.applyShowWhenLocked(true)
                    act.resumeMusicPlayback()
                }
            }

            // C. Aduce aplicația în prim-plan (peste ecranul de blocare)
            BMWAccessibilityService.bringToForeground(context)

            if (pendingResult != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        pendingResult.finish()
                    } catch (_: Exception) {}
                }, 2000L)
            }
            return
        }

        // ==============================================================
        // 2. DECONECTARE BLUETOOTH DE LA DISPOZITIVUL SELECTAT
        // ==============================================================
        val isAclDisconnect = (action == BluetoothDevice.ACTION_ACL_DISCONNECTED)
        val isA2dpDisconnect = (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED)
        val isHidDisconnect = (action == ACTION_HID_CONNECTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_DISCONNECTED)
        val isBtOff = (action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED &&
                (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_OFF ||
                 intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF))

        if (isAclDisconnect || isA2dpDisconnect || isHidDisconnect || isBtOff) {
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
            val isEsp32Mac = devAddress.equals("AC:A7:04:13:3D:59", ignoreCase = true)
            val isTargetDevice = if (isBtOff) {
                true
            } else if (!savedCarBt.isNullOrEmpty()) {
                devAddress.equals(savedCarBt, ignoreCase = true) ||
                isEsp32Mac ||
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            } else {
                isEsp32Mac ||
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv deconectat ignorat: '$devName', adresa=$devAddress")
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (!MainActivity.isCarConnectedState && now - MainActivity.lastDisconnectTimestamp < 3000L) {
                Log.d(TAG, "Deconectare $action deja procesată acum ${now - MainActivity.lastDisconnectTimestamp}ms, ignorare duplicat.")
                return
            }

            val pendingResult = try { goAsync() } catch (_: Exception) { null }
            Log.i(TAG, "Dispozitiv țintă deconectat ($devAddress)! Oprire muzică, curățare Recents și blocare telefon...")
            MainActivity.isCarConnectedState = false
            MainActivity.lastDisconnectTimestamp = now

            val lastSource = when {
                LocalMusicPlayer.isPlaying -> "LOCAL_PLAYER"
                MainActivity.instance?.isRadioPlaying() == true -> "RADIO"
                else -> prefs.getString("last_active_media_source", "RADIO") ?: "RADIO"
            }
            prefs.edit()
                .putLong("last_disconnect_elapsed_time", now)
                .putString("last_active_media_source", lastSource)
                .apply()

            // A. Oprește radioul / muzica la deconectare
            try {
                context.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(context.packageName))
            } catch (_: Exception) {}
            try {
                LocalMusicPlayer.pause()
                LocalMusicPlayer.stop()
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

            // B. Curăță aplicațiile din Recents și blochează ecranul
            BMWAccessibilityService.clearRecentsAndLockScreen()

            if (pendingResult != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        pendingResult.finish()
                    } catch (_: Exception) {}
                }, 2500L)
            }

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
        const val ACTION_HID_CONNECTION_STATE_CHANGED = "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED"
        @Volatile
        private var lastConnectHandledTimestamp: Long = 0L
    }
}
