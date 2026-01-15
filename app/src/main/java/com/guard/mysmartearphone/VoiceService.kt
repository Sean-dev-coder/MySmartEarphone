package com.guard.mysmartearphone
import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo

class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "VoiceChannel"
        val channel = NotificationChannel(channelId, "語音助理執行中", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("金牌守衛：守護中") // 配合你的新名稱
            .setContentText("正在持續監聽中...")
            .setSmallIcon(R.mipmap.ic_launcher) // 建議用你對準的那張專業圖示
            .setOngoing(true) // 確保通知不會被滑掉
            .build()

        // 🌟 關鍵修正：Android 14+ 必須指定 FOREGROUND_SERVICE_TYPE_MICROPHONE
        // 否則螢幕一關，系統會立刻切斷麥克風讀取權限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        return START_STICKY
    }
}