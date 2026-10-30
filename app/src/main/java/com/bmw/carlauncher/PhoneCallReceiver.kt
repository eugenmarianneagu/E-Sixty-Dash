package com.bmw.carlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    MainActivity.onPhoneRinging(context, incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    MainActivity.onPhoneOffhook(context)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    MainActivity.onPhoneIdle(context)
                }
            }
        }
    }
}
