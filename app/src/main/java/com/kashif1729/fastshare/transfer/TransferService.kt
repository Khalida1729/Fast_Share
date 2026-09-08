package com.kashif1729.fastshare.transfer

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TransferService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("transfer", "Fast Share transfers", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, "transfer")
            .setContentTitle("Fast Share")
            .setContentText("Nearby sharing is ready")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true).build()
                //startForeground(1001, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
