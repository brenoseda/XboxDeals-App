package com.xboxdeals

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Consulta a loja a cada 60 s com o app fechado (serviço em primeiro plano). */
class LiveService : Service() {
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("live", "Monitoramento ao vivo", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "live")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Xbox Deals")
            .setContentText("Monitorando preços ao vivo")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job == null) {
            // No Android 15 o sistema limita este tipo de serviço a ~6 h; paramos antes por conta própria.
            val end = if (Build.VERSION.SDK_INT >= 35) System.currentTimeMillis() + 5 * 3_600_000L else Long.MAX_VALUE
            job = CoroutineScope(Dispatchers.IO).launch {
                while (isActive && System.currentTimeMillis() < end) {
                    try { Deals.check(applicationContext) } catch (e: Exception) { }
                    delay(60_000)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
