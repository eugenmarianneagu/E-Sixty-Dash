package com.bmw.carlauncher.notification

import android.app.PendingIntent
import android.graphics.drawable.Drawable

data class CarNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val isVoiceMessage: Boolean,
    val contentIntent: PendingIntent?,
    val playActionIntent: PendingIntent? = null,
    val appIcon: Drawable? = null
)
