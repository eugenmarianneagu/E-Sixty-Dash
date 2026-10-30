package com.bmw.carlauncher.notification

import android.os.Handler
import android.os.Looper

object CarNotificationHub {
    private val mainHandler = Handler(Looper.getMainLooper())
    var onNotificationReceived: ((CarNotification) -> Unit)? = null
    var onUnreadCountChanged: ((Int) -> Unit)? = null
    var onNotificationsListChanged: ((List<CarNotification>) -> Unit)? = null
    val unreadNotifications = java.util.concurrent.CopyOnWriteArrayList<CarNotification>()

    @Volatile
    var systemNotificationCount: Int = 0
        set(value) {
            field = value
            mainHandler.post {
                onUnreadCountChanged?.invoke(getUnreadCount())
            }
        }

    var currentNotification: CarNotification? = null
        private set

    fun syncNotifications(newList: List<CarNotification>) {
        val newKeys = newList.map { it.key }.toSet()
        unreadNotifications.removeAll { !newKeys.contains(it.key) }
        for (item in newList) {
            val existingIndex = unreadNotifications.indexOfFirst { it.key == item.key }
            if (existingIndex >= 0) {
                unreadNotifications[existingIndex] = item
            } else {
                unreadNotifications.add(item)
            }
        }
        systemNotificationCount = unreadNotifications.size
        mainHandler.post {
            onUnreadCountChanged?.invoke(unreadNotifications.size)
            onNotificationsListChanged?.invoke(unreadNotifications.toList())
        }
    }

    fun postNotification(notification: CarNotification) {
        currentNotification = notification
        // Pastreaza sau actualizeaza notificarea dupa cheie
        unreadNotifications.removeAll { it.key == notification.key }
        unreadNotifications.add(0, notification)
        systemNotificationCount = unreadNotifications.size
        mainHandler.post {
            onNotificationReceived?.invoke(notification)
            onUnreadCountChanged?.invoke(getUnreadCount())
            onNotificationsListChanged?.invoke(unreadNotifications.toList())
        }
    }

    fun removeNotification(key: String) {
        val removed = unreadNotifications.removeAll { it.key == key }
        if (removed) {
            systemNotificationCount = unreadNotifications.size
            mainHandler.post {
                onUnreadCountChanged?.invoke(getUnreadCount())
                onNotificationsListChanged?.invoke(unreadNotifications.toList())
            }
        }
    }

    fun markAllAsRead() {
        unreadNotifications.clear()
        systemNotificationCount = 0
        mainHandler.post {
            onUnreadCountChanged?.invoke(0)
            onNotificationsListChanged?.invoke(emptyList())
        }
    }

    fun getUnreadCount(): Int {
        val active = CarNotificationListenerService.instance?.getActiveStatusBarNotificationCount()
        return active ?: if (systemNotificationCount > 0) systemNotificationCount else unreadNotifications.size
    }

    fun clear() {
        currentNotification = null
    }
}
