package com.guard.mysmartearphone

import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
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
    private var isKeepListening = false
    private var isTtsSpeaking = false // 💡 新增：追蹤 TTS 是否正在說話，避免 MIC 搶奪管道
    private var lastQueryDocuments: List<DocumentSnapshot> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupFirestoreOffline()
        syncAllDataForOffline("licensePlates")
        val intent = Intent(this, VoiceService::class.java)
        startForegroundService(intent)
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
                syncAllDataForOffline(selectedCommunity)
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
        // 🌟 呼叫重構後的轉換工具
        val cleanPlate = convertSpokenPlate(plateText)
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
    /**
     * 處理語音簡化邏輯：將「4個8」或「四個零」轉換為「8888」或「0000」
     */
    private fun convertSpokenPlate(text: String): String {
        var result = text
        val digitMap = mapOf(
            "零" to "0", "0" to "0", "一" to "1", "1" to "1", "二" to "2", "2" to "2",
            "三" to "3", "3" to "3", "四" to "4", "4" to "4", "五" to "5", "5" to "5",
            "六" to "6", "6" to "6", "七" to "7", "7" to "7", "八" to "8", "8" to "8",
            "九" to "9", "9" to "9"
        )

        // 正則表達式：尋找 (數字/國字) + "個" + (數字/國字/零)
        val regex = Regex("([0-9一二三四五六七八九])個([0-9一二三四五六七八九零])")
        val matches = regex.findAll(result)

        for (match in matches) {
            val countStr = match.groupValues[1]
            val digitStr = match.groupValues[2]

            val count = digitMap[countStr]?.toIntOrNull() ?: 0
            val digit = digitMap[digitStr] ?: ""

            if (count > 0 && digit.isNotEmpty()) {
                val repeatedDigits = digit.repeat(count)
                result = result.replace(match.value, repeatedDigits)
                Log.d("VoiceConvert", "轉換成功: ${match.value} -> $repeatedDigits")
            }
        }

        // 最後進行標準化淨化：移除所有非英數字元
        return result.replace(Regex("[^A-Za-z0-9]"), "")
    }
    /**
     * 設定資料快取在本地端,離線服務
     */
    private fun setupFirestoreOffline() {
        val db = FirebaseFirestore.getInstance()

        // 🌟 核心設定：啟動持久化快取
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder()
                // 設定快取大小（例如 100MB），確保能存下所有社區的車牌資料
                .setSizeBytes(100 * 1024 * 1024)
                .build())
            .build()

        db.firestoreSettings = settings
    }
    /**
     * 設定全量資料快取在本地端,離線服務
     */
    private fun syncAllDataForOffline(collectionPath: String) {
        val db = Firebase.firestore
        // 取得該社區的所有車牌資料,取得該集合的所有文件，這會強制將資料寫入本地快取女
        db.collection(collectionPath).get().addOnSuccessListener { documents ->
            // 同步成功後，Logcat 會記錄筆數，讓開發者確認資料有進手機
            Log.d("OfflineSync", "社區 ${collectionPath} 同步成功，共 ${documents.size()} 筆資料已存入離線快取")
        }.addOnFailureListener { e ->
            Log.e("OfflineSync", "同步失敗: ${e.message}")
        }
    }
    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            // 🌟 核心修正：使用 Android 16 推薦的 OnDevice 辨識器
            val recognizer = if (android.os.Build.VERSION.SDK_INT >= 31) {
                // 直接建立「純裝置端」辨識器，這會強制繞過網路檢查
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")

                // 🌟 強制設定 A：只允許離線辨識
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

                // 🌟 強制設定 B：指定由 Google 引擎負責（避免系統亂跳）
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                // 🌟 這些是必須補齊的 8 個方法，補齊後紅字 object 就會消失
                override fun onReadyForSpeech(params: Bundle?) { Log.d("STT", "可以開始說話了") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    // 這裡會抓到斷網時最關鍵的 error 13
                    Log.e("STT", "辨識錯誤代碼: $error")
                    speechRecognizer.destroy()
                }

                override fun onResults(results: Bundle?) {
                    // 這裡拿到辨識出的車牌文字
                    val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val resultText = data?.get(0) ?: ""
                    Log.d("STT", "辨識結果: $resultText")
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }
    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        if (::speechRecognizer.isInitialized) { speechRecognizer.destroy() }
        super.onDestroy()
    }
}