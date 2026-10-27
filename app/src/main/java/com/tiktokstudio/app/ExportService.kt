package com.tiktokstudio.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** خدمة أمامية: بتخلّي المعالجة تكمل لو قفلت الشاشة أو خرجت من التطبيق. الطابور نفسه في QueueManager. */
class ExportService : Service() {
    companion object {
        private const val CH = "export"; private const val ID = 1
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ExportService::class.java))
    }
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        QueueManager.init(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "المعالجة", NotificationManager.IMPORTANCE_LOW))
        val n = notif("جاري التحضير...", 0, true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (wl == null) wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tiktokstudio:export").apply { acquire(6 * 60 * 60 * 1000L) }
        QueueManager.notify = { text, p -> nm.notify(ID, notif(text, p, true)) }
        QueueManager.onFinished = {
            QueueManager.notify = null; QueueManager.onFinished = null
            wl?.let { if (it.isHeld) it.release() }; wl = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(ID + 1, notif("انتهت المعالجة. افتح «المعالجة والسجل» لمشاهدة النتائج.", -1, false))
            stopSelf()
        }
        QueueManager.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { wl?.let { if (it.isHeld) it.release() } }

    private fun notif(text: String, p: Int, ongoing: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TikTok Video Studio").setContentText(text).setContentIntent(pi)
            .setOngoing(ongoing).setAutoCancel(!ongoing)
            .apply { if (p >= 0) setProgress(100, p, false) }.build()
    }
}
