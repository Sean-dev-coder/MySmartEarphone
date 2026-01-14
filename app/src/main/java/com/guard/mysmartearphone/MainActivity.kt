package com.guard.mysmartearphone

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Button
import android.widget.TextView
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tvResult: TextView
    private lateinit var tts: TextToSpeech
    private var selectedCommunity = "請選擇"
    private var isFirstLoad = true
    private var isKeepListening = false // 預設先不啟動持續監聽

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化 UI
        val spinner = findViewById<Spinner>(R.id.spinner_community)
        val btnListen = findViewById<Button>(R.id.btn_listen)
        val tvStatus = findViewById<TextView>(R.id.tv_source_status)
        tvResult = findViewById<TextView>(R.id.tv_speech_result)

        // 2. 初始化選單
        val communities = arrayOf("大陸麗格", "大陸豐蒔", "大陸寶格")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, communities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCommunity = communities[position]
                if (isFirstLoad) {
                    isFirstLoad = false
                    return
                }
                // 切換社區時，先徹底停止辨識再說話
                stopSpeechLogic()
                speakOut("已切換至 $selectedCommunity")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 3. 初始化 TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.TAIWAN)
                // 設定監聽器：唸完才准聽
                setupTTSListener()
            }
        }

        // 4. 初始化語音辨識器
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // 5. 啟動前景服務與權限
        val serviceIntent = Intent(this, VoiceService::class.java)
        startForegroundService(serviceIntent)
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)

        btnListen.setOnClickListener {
            checkAudioSource(tvStatus)
            isKeepListening = true // 按下按鈕才開啟持續模式
            startListening()
        }
    }

    private fun startListening() {
        // 如果正在說話，就不啟動監聽
        if (tts.isSpeaking) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                findViewById<TextView>(R.id.tv_source_status).text = "🎙 正在聽取 $selectedCommunity..."
            }

            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = data?.get(0) ?: ""
                tvResult.text = "你說：$text"

                if (text.contains("結束查詢") || text.contains("停止") || text.contains("結束")) {
                    isKeepListening = false
                    speakOut("好的，已為您結束查詢服務")
                } else {
                    // 🌟 重點：這裡「只管說話」，不要在這裡寫 startListening()
                    // 讓 speakOut 的 onDone 去負責重啟，才不會衝突
                    speakOut("您剛剛說的是：$text")
                }
            }

            override fun onError(error: Int) {
                // 如果是持續模式且沒有在說話，才重啟
                if (isKeepListening && !tts.isSpeaking) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startListening()
                    }, 500)
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

    private fun speakOut(text: String) {
        // 1. 說話前先關閉麥克風
        speechRecognizer.stopListening()

        // 2. 開始唸
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "EndID")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "EndID")
    }

    private fun setupTTSListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                // 3. 唸完後，緩衝一下再開耳，避免錄到殘響
                runOnUiThread {
                    if (isKeepListening) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            startListening()
                        }, 600) // 延遲 0.6 秒確保安靜
                    }
                }
            }
            override fun onError(utteranceId: String?) {}
        })
    }

    private fun stopSpeechLogic() {
        speechRecognizer.stopListening()
        tts.stop()
    }

    private fun checkAudioSource(statusView: TextView) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        var sourceName = "手機內建麥克風"
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                sourceName = "JLab 藍牙耳機 (已連線)"
                break
            }
        }
        statusView.text = "目前收音路徑：$sourceName"
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        super.onDestroy()
    }
}