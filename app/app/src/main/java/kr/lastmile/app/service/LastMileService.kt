package kr.lastmile.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kr.lastmile.app.LastMileApp
import kr.lastmile.app.R
import kr.lastmile.app.ui.MainActivity

/** 주행 중 콜백을 계속 받기 위한 포그라운드 서비스. 엔진은 Application 이 소유한다. */
class LastMileService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, LastMileApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("도착 전 브리핑과 마중 알림을 준비 중이에요")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        (application as LastMileApp).engine.start()
        return START_STICKY
    }
}
