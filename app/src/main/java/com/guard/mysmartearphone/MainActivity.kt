package com.guard.mysmartearphone
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
        // 🌟 在聽之前，確保藍牙管線切換到麥克風模式
        setupBluetoothAudio()
        // 如果正在說話，就不啟動監聽
        if (tts.isSpeaking) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
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
                    // 🌟 換成這行：去資料庫查
                    queryVehicle(text)
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
            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB 是分貝值，通常在 -2 到 10 之間跳動
                if (rmsdB > 0) {
                    runOnUiThread {
                        // 在狀態列顯示音量感應，如果有在跳，代表收音管線是通的
                        findViewById<TextView>(R.id.tv_source_status).text = "🎙 藍牙收音中... (感應強度: ${rmsdB.toInt()})"
                    }
                }
            }
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
    private fun queryVehicle(plateText: String) {
        val db = Firebase.firestore
        // 根據你提供的截圖，路徑是 licensePlates
        val collectionRef = db.collection("licensePlates")

        // 🌟 先試著用完整車牌 (Document ID) 找
        collectionRef.document(plateText).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val houseCode = document.getString("householdCode") ?: "未知"
                    val notes = document.getString("notes") ?: ""

                    // 🌟 關鍵：必須在這裡更新 TextView
                    runOnUiThread {
                        tvResult.text = "✅ 查詢成功\n戶號：$houseCode\n車牌：$plateText\n備註：$notes"
                    }

                    speakOut("找到了，這是 $houseCode 的住戶。$notes")
                } else {
                    // 2. 進入模糊查詢的區塊
                    collectionRef.whereArrayContains("searchKeywords", plateText).get()
                        .addOnSuccessListener { documents ->
                            if (!documents.isEmpty) {
                                val firstDoc = documents.documents[0]
                                val hCode = firstDoc.getString("householdCode") ?: ""
                                val realPlate = firstDoc.id
                                val nts = firstDoc.getString("notes") ?: ""

                                // 🌟 關鍵：模糊查詢成功也要更新 UI
                                runOnUiThread {
                                    tvResult.text = "🔍 模糊比對成功\n戶號：$hCode\n完整車牌：$realPlate\n備註：$nts"
                                }
                                speakOut("查到了，這是 $hCode 的車")
                            }else {
                                // 🌟 關鍵修正 2：真的完全查不到資料
                                runOnUiThread {
                                    tvResult.text = "❌ 查無資料：$plateText"
                                }
                                // 必須說話！這樣才會觸發 TTS 的 onDone，進而重啟監聽
                                speakOut("抱歉，找不到車牌 $plateText 的資料，請再說一次")
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                speakOut("查詢失敗，請檢查網路")
            }
    }
    private fun setupBluetoothAudio() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // 1. 檢查是否支援藍牙 SCO
        if (audioManager.isBluetoothScoAvailableOffCall) {
            // 2. 開啟藍牙 SCO 連線
            audioManager.startBluetoothSco()

            // 3. 設定為通訊模式（這會切換藍牙協定從 A2DP 到 SCO）
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isBluetoothScoOn = true
            Log.d("AudioDebug", "藍牙 SCO 已嘗試啟動")
        } else {
            Log.e("AudioDebug", "此裝置不支援離線藍牙 SCO")
        }
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