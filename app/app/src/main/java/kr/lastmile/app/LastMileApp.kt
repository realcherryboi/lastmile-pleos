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
        runCatching { org.maplibre.android.MapLibre.getInstance(this) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        engine = TripEngine.create(this)
    }

    companion object {
        const val CHANNEL_ID = "lastmile.driving"
        /** 화면이 떠 있는지(onResume~onPause). 분할 창으로 이미 보이는 중이면 도착 때 앞으로 불러오지 않는다 — 불러오면 좌우 창 순서가 뒤집힌다. */
        @Volatile var activityResumed: Boolean = false
    }
}
