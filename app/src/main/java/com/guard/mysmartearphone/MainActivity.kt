package com.guard.mysmartearphone

import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import android.media.AudioDeviceInfo
import com.google.firebase.firestore.DocumentSnapshot
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
    private var isKeepListening = false
    private var isTtsSpeaking = false // 💡 新增：追蹤 TTS 是否正在說話，避免 MIC 搶奪管道
    private var lastQueryDocuments: List<DocumentSnapshot> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tv_speech_result)
        val spinner = findViewById<Spinner>(R.id.spinner_community)
        val btnListen = findViewById<Button>(R.id.btn_listen)
        val tvStatus = findViewById<TextView>(R.id.tv_source_status)

        // 初始化選單
        val communities = arrayOf("大陸麗格", "大陸豐蒔", "大陸寶格")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, communities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCommunity = communities[position]
                if (isFirstLoad) { isFirstLoad = false; return }
                stopSpeechLogic()
                speakOut("已切換至 $selectedCommunity")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 初始化 TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.TAIWAN)
                setupTTSListener() // 🌟 統一在這裡設定監聽器
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        btnListen.setOnClickListener {
            checkAudioSource(tvStatus)
            isKeepListening = true
            startListening()
        }
    }

    private fun setupTTSListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isTtsSpeaking = true // 💡 鎖定狀態：正在說話
            }
            override fun onDone(utteranceId: String?) {
                isTtsSpeaking = false // 💡 解除狀態
                runOnUiThread {
                    if (isKeepListening) {
                        // 🌟 關鍵延遲：給藍牙硬體充足時間從播音切換回錄音
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!isTtsSpeaking) startListening()
                        }, 1000)
                    }
                }
            }
            override fun onError(utteranceId: String?) { isTtsSpeaking = false }
        })
    }

    private fun startListening() {
        if (isTtsSpeaking) return // 如果正在說話，禁止啟動麥克風

        setupBluetoothAudio() // 確保藍牙 SCO 開啟

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            // 調短靜音判斷，增加連貫感
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread { findViewById<TextView>(R.id.tv_source_status).text = "🎙 正在聽取 $selectedCommunity..." }
            }

            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = data?.get(0) ?: ""

                // 1. 停止指令
                if (text.contains("結束") || text.contains("停止")) {
                    isKeepListening = false
                    resetToNormalAudioMode()
                    lastQueryDocuments = listOf()
                    speakOut("已結束查詢服務")
                    return
                }

                // 2. 🌟 語音選單判斷：只有真的有複數資料才攔截
                if (lastQueryDocuments.size > 1) {
                    val index = when {
                        text.contains("第一個") || text == "1" || text == "一" -> 0
                        text.contains("第二個") || text == "2" || text == "二" -> 1
                        text.contains("第三個") || text == "3" || text == "三" -> 2
                        else -> -1
                    }

                    if (index != -1 && index < lastQueryDocuments.size) {
                        val doc = lastQueryDocuments[index]
                        lastQueryDocuments = listOf() // 🌟 立即清空暫存，防止下次誤判
                        processSelection(doc)
                        return
                    }
                }

                // 3. 一般查詢：執行前強制清空舊暫存，確保單筆查詢不被選單干擾
                lastQueryDocuments = listOf()
                queryVehicle(text)
            }

            override fun onError(error: Int) {
                if (isKeepListening) {
                    if (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        enableBluetoothMic(getSystemService(AUDIO_SERVICE) as AudioManager)
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        speechRecognizer.cancel()
                        if (!isTtsSpeaking) startListening()
                    }, 1000)
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > 0) {
                    runOnUiThread { findViewById<TextView>(R.id.tv_source_status).text = "🎙 藍牙收音中... (感應: ${rmsdB.toInt()})" }
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    private fun queryVehicle(plateText: String) {
        // 1. 文字淨化：過濾掉「加」、「+」等干擾
        val cleanPlate = plateText.replace(Regex("[^A-Za-z0-9]"), "")
        if (cleanPlate.isBlank()) return

        // 2. 🌟 動態路徑對應表：根據選單名稱對應到正確的資料表
        val collectionPath = when (selectedCommunity) {
            "大陸麗格" -> "licensePlates"
            "大陸寶格" -> "licensePlates_treasure"
            "大陸豐蒔" -> "licensePlates_epoque"
            else -> "licensePlates" // 預設路徑
        }

        val db = Firebase.firestore
        val collectionRef = db.collection(collectionPath)

        Log.d("FirebaseQuery", "正在查詢社區：$selectedCommunity，路徑：$collectionPath")

        // 3. 執行查詢
        collectionRef.document(cleanPlate).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    // 精確命中邏輯
                    processSingleResult(document, cleanPlate)
                } else {
                    // 模糊查詢邏輯
                    collectionRef.whereArrayContains("searchKeywords", cleanPlate).get()
                        .addOnSuccessListener { documents ->
                            handleMultipleResults(documents, cleanPlate)
                        }
                }
            }
            .addOnFailureListener { speakOut("查詢失敗，請檢查網路") }
    }
    // 1. 處理「單筆精確命中」的結果
    private fun processSingleResult(document: DocumentSnapshot, plateText: String) {
        val houseCode = document.getString("householdCode") ?: "未知"
        val notes = document.getString("notes") ?: ""

        runOnUiThread {
            tvResult.text = "✅ 查詢成功\n戶號：$houseCode\n車牌：$plateText\n備註：$notes"
        }
        speakOut("找到了，這是 $houseCode 的住戶。$notes")
    }

    // 2. 處理「模糊查詢」回傳的多筆結果
    private fun handleMultipleResults(documents: com.google.firebase.firestore.QuerySnapshot, plateText: String) {
        if (documents.isEmpty) {
            runOnUiThread { tvResult.text = "❌ 查無資料：$plateText" }
            speakOut("找不到車牌 $plateText 的資料")
            return
        }

        lastQueryDocuments = documents.documents // 存入暫存供語音選單使用

        if (documents.size() == 1) {
            // 只有一筆模糊命中
            val doc = documents.documents[0]
            val hCode = doc.getString("householdCode") ?: ""
            runOnUiThread { tvResult.text = "🔍 模糊比對成功\n戶號：$hCode\n車牌：${doc.id}" }
            speakOut("查到了，這是 $hCode 的車")
        } else {
            // 處理多筆資料交互邏輯
            val total = documents.size()
            if (total <= 3) {
                val houseList = documents.documents.mapIndexed { i, d ->
                    "第${i + 1}個${d.getString("householdCode") ?: "未知"}"
                }.joinToString(" ")
                speakOut("找到 $total 筆：$houseList。請問選第幾個？")
            } else {
                // 符合項過多時的引導
                speakOut("符合車牌共有 $total 筆，請補上英文字母。")
            }

            runOnUiThread {
                val display = documents.documents.joinToString("\n") {
                    "${it.id} (${it.getString("householdCode")})"
                }
                tvResult.text = "🔍 找到多筆符合：\n$display" //
            }
        }
    }
    private fun speakOut(text: String) {
        isTtsSpeaking = true // 💡 標記正在說話
        speechRecognizer.stopListening() // 🗣 說話時必須關閉錄音
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MessageID") }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "MessageID")
    }

    private fun processSelection(doc: DocumentSnapshot) {
        val hCode = doc.getString("householdCode") ?: "未知"
        runOnUiThread { tvResult.text = "✅ 語音選擇成功：$hCode (${doc.id})" }
        speakOut("好的，為您選擇 $hCode")
    }

    private fun setupBluetoothAudio() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (audioManager.isBluetoothScoAvailableOffCall) {
            audioManager.startBluetoothSco()
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun enableBluetoothMic(audioManager: AudioManager) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val bluetoothMic = devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (bluetoothMic != null) audioManager.setCommunicationDevice(bluetoothMic)
        } else {
            @Suppress("DEPRECATION") audioManager.startBluetoothSco()
            @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = true
        }
    }

    private fun resetToNormalAudioMode() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (e: Exception) { Log.e("AudioMode", "停止 SCO 失敗") }
        audioManager.mode = AudioManager.MODE_NORMAL
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
    }

    private fun stopSpeechLogic() {
        speechRecognizer.stopListening()
        tts.stop()
        isTtsSpeaking = false
    }

    private fun checkAudioSource(statusView: TextView) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        var sourceName = "手機內建麥克風"
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                sourceName = "JLab 藍牙耳機 (已連線)"
                break
            }
        }
        statusView.text = "目前收音路徑：$sourceName"
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        if (::speechRecognizer.isInitialized) { speechRecognizer.destroy() }
        super.onDestroy()
    }
}