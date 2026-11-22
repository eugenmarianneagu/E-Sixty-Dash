package com.bmw.carlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class PhoneCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            @Suppress("DEPRECATION")
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.i("PhoneCallReceiver", "PHONE_STATE changed: state=$stateStr, hasNumber=${!incomingNumber.isNullOrBlank()}")

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    CallOverlayManager.onIncomingCall(context, number = incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    CallOverlayManager.onOffhook(context)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    CallOverlayManager.onCallEnded(context)
                }
            }
        }
    }
}
