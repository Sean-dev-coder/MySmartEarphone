package com.guard.mysmartearphone

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.widget.Button
import android.widget.TextView
import android.util.Log // 打印LOG
import androidx.appcompat.app.AppCompatActivity
import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tvResult: TextView
    private lateinit var tts: TextToSpeech    // 定義「嘴巴」

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 在啟動監聽的同時，啟動前景服務
        val serviceIntent = Intent(this, VoiceService::class.java)
        startForegroundService(serviceIntent)
        // 請求權限
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)

        val btnListen = findViewById<Button>(R.id.btn_listen)
        val tvStatus = findViewById<TextView>(R.id.tv_source_status)
        tvResult = findViewById<TextView>(R.id.tv_speech_result)

        // 初始化語音辨識器
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        btnListen.setOnClickListener {
            checkAudioSource(tvStatus) // 檢查來源
            startListening()           // 開始聽說話
        }

        // 初始化 TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 設定語言為台灣中文
                val result = tts.setLanguage(Locale.TAIWAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "這台手機不支援台灣中文語音")
                }
            } else {
                Log.e("TTS", "TTS 初始化失敗")
            }
        }
    }

    // 1. 定義一個變數來控制是否要繼續聽（像是開關）
    private var isKeepListening = true

    private fun startListening() {
        isKeepListening = true // 每次按按鈕啟動時，確保開關是打開的

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // 不要每次都覆蓋掉 tvResult，我們改用「狀態顯示」
                findViewById<TextView>(R.id.tv_source_status).text = "🎙 正在聽取中（說「結束查詢」可停止）"
            }

            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = data?.get(0) ?: ""

                // 將辨識到的字印在白框區域
                tvResult.text = "你說：$text"

                // 🌟 核心邏輯：檢查關鍵字
                if (text.contains("結束查詢") || text.contains("停止") || text.contains("結束")) {
                    tvResult.append("\n✅ 已收到停止指令。")
                    isKeepListening = false // 關閉開關
                    speakOut("好的，已為您結束查詢服務。")
                    speechRecognizer.stopListening()
                } else {
                    // 如果不是停止指令，稍微等 0.5 秒後再次啟動，避免系統太累
                    if (isKeepListening) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startListening()
                        }, 500)
                    }
                    speakOut("您剛剛說的是：$text")
                }
            }
            override fun onError(error: Int) {
                // 當因為「太久沒說話」導致自動中斷 (Error 7) 時，自動重啟
                if (isKeepListening) {
                    startListening()
                } else {
                    findViewById<TextView>(R.id.tv_source_status).text = "🛑 錄音已停止"
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
    }

    class MainActivity : AppCompatActivity() {

        private lateinit var speechRecognizer: SpeechRecognizer
        private lateinit var tvResult: TextView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            // 請求權限
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)

            val btnListen = findViewById<Button>(R.id.btn_listen)
            val tvStatus = findViewById<TextView>(R.id.tv_source_status)
            tvResult = findViewById<TextView>(R.id.tv_speech_result)

            // 初始化語音辨識器
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            btnListen.setOnClickListener {
                checkAudioSource(tvStatus) // 檢查來源
                startListening()           // 開始聽說話
            }
        }

        private fun startListening() {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW") // 設定為台灣中文
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { tvResult.text = "請開始說話..." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { tvResult.text = "辨識錯誤：$error" }

                // 這是最重要的部分：得到結果
                override fun onResults(results: Bundle?) {
                    val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    tvResult.text = data?.get(0) ?: "聽不清楚，請再說一次"
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer.startListening(intent)
        }

        // 判斷聲音來源的專家級函式
        private fun checkAudioSource(statusView: TextView) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            var sourceName = "手機內建麥克風"

            for (device in devices) {
                // 判斷是否為藍牙耳機通訊格式 (SCO)
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    sourceName = "JLab 藍牙耳機 (已連線)"
                    break
                }
            }

            statusView.text = "目前收音路徑：$sourceName"
        }
    }

    // 判斷聲音來源的專家級函式
    private fun checkAudioSource(statusView: TextView) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        var sourceName = "手機內建麥克風"

        for (device in devices) {
            // 判斷是否為藍牙耳機通訊格式 (SCO)
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                sourceName = "JLab 藍牙耳機 (已連線)"
                break
            }
        }
        statusView.text = "目前收音路徑：$sourceName"
    }

    private fun speakOut(text: String) {
        // 讓 App 把話唸出來
        // QUEUE_FLUSH 代表：如果現在正在唸別的，就把它中斷，改唸這句新的
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UtteranceID")

        // 專家級邏輯：監測什麼時候「唸完了」，唸完再重新啟動錄音，避免「自言自語」
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                // 當 App 唸完資料後，自動啟動下一輪監聽
                runOnUiThread {
                    if (isKeepListening) {
                        startListening()
                    }
                }
            }
            override fun onError(utteranceId: String?) {}
        })
    }

    // 當這個 Activity (畫面) 被銷毀時會執行這裡
    override fun onDestroy() {
        // 1. 先停止說話
        if (::tts.isInitialized) {
            tts.stop()
            // 2. 釋放語音引擎資源
            tts.shutdown()
        }

        // 3. 同時也把語音辨識器關掉，省電並釋放麥克風
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        Log.d("MY_APP_DEBUG", "destroy")

        super.onDestroy() // 這行一定要在最後面
    }
}