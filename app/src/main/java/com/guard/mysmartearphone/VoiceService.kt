package com.guard.mysmartearphone
import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 建立一個通知頻道（Android 8.0+ 必須）
        val channelId = "VoiceChannel"
        val channel = NotificationChannel(channelId, "語音助理執行中", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // 建立顯示在狀態列的通知
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("智能耳機助理")
            .setContentText("正在持續監聽中...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        // 啟動前景服務（關鍵：指定為麥克風類型）
        startForeground(1, notification)

        return START_STICKY // 代表如果被意外殺掉，系統會嘗試重啟
    }
}