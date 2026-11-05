package kr.lastmile.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kr.lastmile.app.domain.TripEngine

class LastMileApp : Application() {
    lateinit var engine: TripEngine
        private set

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        engine = TripEngine.create(this)
    }

    companion object {
        const val CHANNEL_ID = "lastmile.driving"
    }
}
