package com.newzura.erebus

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Erebus"
        var isConnected = false
            private set
        var instance: NotificationListener? = null
            private set

        fun isNotificationListenerEnabled(context: Context): Boolean {
            val cn = ComponentName(context, NotificationListener::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(cn.flattenToString())
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        Log.i(TAG, "M3: NotificationListener connecté avec succès")
        ErebusMediaService.onNotificationListenerConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        instance = null
        Log.i(TAG, "M3: NotificationListener déconnecté")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Optionnel : détection dynamique de notifications média
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optionnel
    }
}
