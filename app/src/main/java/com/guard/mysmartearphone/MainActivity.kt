package com.guard.mysmartearphone
import com.google.firebase.firestore.Source // 🌟 記得加這行 import
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
import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tvResult: TextView
    private lateinit var tvDebugLog: TextView
    private lateinit var tts: TextToSpeech
    private var selectedCommunity = "請選擇"
    private var isFirstLoad = true
    private var isKeepListening = false
    private var isTtsSpeaking = false
    private var lastQueryDocuments: List<DocumentSnapshot> = listOf()
    private val handler = Handler(Looper.getMainLooper())
    private val communityPathMap = mapOf(
        "大陸麗格" to "licensePlates",
        "大陸豐蒔" to "licensePlates_epoque",
        "大陸寶格" to "licensePlates_treasure"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ 修復：Use property access syntax
        tvResult = findViewById(R.id.tv_speech_result)
        tvDebugLog = findViewById(R.id.tv_debug_log)
        val spinner = findViewById<Spinner>(R.id.spinner_community)
        val btnListen = findViewById<Button>(R.id.btn_listen)
        val tvStatus = findViewById<TextView>(R.id.tv_source_status)

        setupModernAudio()
        setupFirestoreOffline()

        // 🌟 確保初始同步參數正確
        syncAllDataForOffline("licensePlates")

        val serviceIntent = Intent(this, VoiceService::class.java)
        startForegroundService(serviceIntent)

        val communities = arrayOf("大陸麗格", "大陸豐蒔", "大陸寶格")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, communities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCommunity = communities[position]

                // 1. Get the English path from the map (default to licensePlates if not found)
                val collectionPath = communityPathMap[selectedCommunity] ?: "licensePlates"

                if (isFirstLoad) {
                    isFirstLoad = false
                    return
                }

                stopSpeechLogic()
                speakOut("已切換至 $selectedCommunity")

                // 2. Pass the correct English path to sync
                syncAllDataForOffline(collectionPath)

                // 3. Updated log to show both names for debugging
                addLog("📍 切換社區: $selectedCommunity (路徑: $collectionPath)")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.TAIWAN)
                setupTTSListener()
                addLog("📢 TTS 語音引擎就緒")
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        btnListen.setOnClickListener {
            checkAudioSource(tvStatus)
            isKeepListening = true
            setupModernAudio()
            startListening()
            addLog("🚀 開始執勤監聽模式")
        }
    }

    private fun setupTTSListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isTtsSpeaking = true }
            override fun onDone(utteranceId: String?) {
                isTtsSpeaking = false
                runOnUiThread {
                    if (isKeepListening) {
                        handler.postDelayed({ if (!isTtsSpeaking) startListening() }, 1000)
                    }
                }
            }
            override fun onError(utteranceId: String?) { isTtsSpeaking = false }
        })
    }

    private fun startListening() {
        // 1. 核心守則：TTS 說話時絕對不准啟動監聽，這是避免 Error 11 的關鍵
        if (isTtsSpeaking) return
        handler.removeCallbacksAndMessages(null)

        // 2. 確保音訊路徑 (藍牙/耳機) 鎖定
        setupModernAudio()

        try {
            // 3. 如果引擎沒初始化，才建立它
            if (!::speechRecognizer.isInitialized) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            } else {
                // 🌟 治本關鍵：不要 destroy，而是用 cancel() 強制將狀態機歸零
                speechRecognizer.cancel()
            }

            // 4. 每次啟動前重新綁定監聽器，確保 Callback 鏈條完整
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    runOnUiThread { findViewById<TextView>(R.id.tv_source_status).text = "🎙 正在聽取 $selectedCommunity..." }
                }

                override fun onResults(results: Bundle?) {
                    val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = data?.get(0) ?: ""
                    addLog("👂 聽取到: $text")

                    if (text.contains("結束") || text.contains("停止")) {
                        isKeepListening = false
                        resetToNormalAudioMode()
                        lastQueryDocuments = listOf()
                        speakOut("已結束查詢服務")
                        return
                    }

                    // 處理多筆選擇邏輯
                    if (lastQueryDocuments.size > 1) {
                        val index = when {
                            text.contains("第一個") || text == "1" || text == "一" -> 0
                            text.contains("第二個") || text == "2" || text == "二" -> 1
                            text.contains("第三個") || text == "3" || text == "三" -> 2
                            else -> -1
                        }
                        if (index != -1 && index < lastQueryDocuments.size) {
                            val doc = lastQueryDocuments[index]
                            lastQueryDocuments = listOf()
                            processSelection(doc)
                            return
                        }
                    }

                    lastQueryDocuments = listOf()
                    queryVehicle(text)

                    // 💡 註：這裡不手動重啟，由 queryVehicle 內的 TTS 完成後觸發 startListening
                }

                override fun onError(error: Int) {
                    if (isKeepListening) {
                        val errorMsg = when(error) {
                            11 -> "系統暫時鎖定 (11)"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路逾時"
                            SpeechRecognizer.ERROR_NETWORK -> "網路連線失敗"
                            SpeechRecognizer.ERROR_AUDIO -> "音訊錄製錯誤 (請檢查麥克風)"
                            SpeechRecognizer.ERROR_SERVER -> "Google 伺服器異常"
                            SpeechRecognizer.ERROR_CLIENT -> "手機端邏輯錯誤"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "聽取超時 (沒人說話)" //
                            SpeechRecognizer.ERROR_NO_MATCH -> "未聽清/找不到匹配結果"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識引擎忙碌中 (請重啟)"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺乏錄音權限"
                            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "連線請求過於頻繁"
                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "與伺服器斷開連線"
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "不支援此語言"
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "語言包暫時不可用"
                            else -> "錯誤 $error"
                        }
                        addLog("🔴 $errorMsg，1.5秒後自動重試")

                        // 出錯時給一點緩衝時間再重啟，避免進入連環報錯
                        handler.postDelayed({
                            if (isKeepListening && !isTtsSpeaking) startListening()
                        }, 1500)
                    }
                }

                // 必要空實作
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // 5. 設定啟動參數
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                // 減少系統負擔，只拿一筆結果
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            speechRecognizer.startListening(intent)

        } catch (e: Exception) {
            addLog("❌ 啟動失敗: ${e.message}")
            handler.postDelayed({ if (isKeepListening) startListening() }, 2000)
        }
    }

    private fun queryVehicle(plateText: String) {
        val cleanPlate = convertSpokenPlate(plateText)
        if (cleanPlate.isBlank()) return// Use the mapping logic for consistency
        val collectionPath = communityPathMap[selectedCommunity] ?: "licensePlates"

        val db = Firebase.firestore
        val isOnline = isNetworkAvailable()
        val source = if (isOnline) Source.DEFAULT else Source.CACHE

        addLog("🔍 檢索 [$selectedCommunity] -> $cleanPlate (集合: $collectionPath, 來源: ${if(source == Source.CACHE) "離線" else "雲端"})")

        db.collection(collectionPath).document(cleanPlate).get(source)
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    processSingleResult(document, cleanPlate)
                } else {
                    runFuzzyQuery(collectionPath, cleanPlate, source)
                }
            }
            .addOnFailureListener { e ->
                if (source == Source.CACHE && isNetworkAvailable()) {
                    addLog("⚠️ 本地無紀錄，嘗試雲端...")
                    db.collection(collectionPath).document(cleanPlate).get(Source.SERVER)
                        .addOnSuccessListener { processSingleResult(it, cleanPlate) }
                        .addOnFailureListener { addLog("❌ 雲端亦無資料"); speakOut("查無此車") }
                } else {
                    addLog("❌ 查詢失敗: ${e.message}")
                    speakOut("目前離線且查無本地紀錄")
                }
            }
    }
    // 🏥 模糊查詢專用備援函式
    private fun runFuzzyQuery(collectionPath: String, cleanPlate: String, source: Source) {
        val db = Firebase.firestore
        db.collection(collectionPath).whereArrayContains("searchKeywords", cleanPlate).get(source)
            .addOnSuccessListener { documents ->
                handleMultipleResults(documents, cleanPlate)
            }
            .addOnFailureListener { e ->
                // 模糊查詢也同樣做一次雲端降級備援
                if (source == Source.CACHE && isNetworkAvailable()) {
                    db.collection(collectionPath).whereArrayContains("searchKeywords", cleanPlate).get(Source.SERVER)
                        .addOnSuccessListener { handleMultipleResults(it, cleanPlate) }
                } else {
                    addLog("❌ 模糊查詢報錯: ${e.message}")
                }
            }
    }

    // 🏥 網路狀態檢查小工具 (請確保這段也在 MainActivity 內)
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting
    }
    private fun processSingleResult(document: DocumentSnapshot, plateText: String) {
        val houseCode = document.getString("householdCode") ?: "未知"
        val notes = document.getString("notes") ?: ""
        val source = if (document.metadata.isFromCache) "本地" else "雲端"
        runOnUiThread {
            tvResult.text = "✅ 成功：$houseCode\n車牌：$plateText\n來源：$source"
        }
        addLog("✅ [$source] 匹配成功: $houseCode") // 👈 這裡會顯示來源
        speakOut("找到了，這是 $houseCode 的住戶。$notes")
    }
    private fun handleMultipleResults(documents: com.google.firebase.firestore.QuerySnapshot, plateText: String) {
        if (documents.isEmpty) {
            runOnUiThread { tvResult.text = "❌ 查無資料：$plateText" }
            addLog("❓ 無匹配紀錄")
            speakOut("找不到車牌 $plateText 的資料")
            return
        }
        val source = if (documents.metadata.isFromCache) "本地" else "雲端"
        lastQueryDocuments = documents.documents
        if (documents.size() == 1) {
            val doc = documents.documents[0]
            val hCode = doc.getString("householdCode") ?: ""
            runOnUiThread { tvResult.text = "🔍 模糊命中：$hCode\n車牌：${doc.id}" }
            addLog("✅ [$source] 模糊比對成功: $hCode")
            speakOut("查到了，這是 $hCode 的車")
        } else {
            val total = documents.size()
            addLog("⚠️ [$source] 發現 ${documents.size()} 筆相似資料")
            val houseList = documents.documents.take(3).mapIndexed { i, d ->
                "第${i + 1}個${d.getString("householdCode") ?: "未知"}"
            }.joinToString(" ")
            speakOut("找到 $total 筆針對 $plateText 的結果，請問選第幾個？ $houseList")
        }
    }

    private fun speakOut(text: String) {
        isTtsSpeaking = true
        speechRecognizer.stopListening()
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MessageID") }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "MessageID")
        addLog("📢 TTS: $text")
    }

    private fun processSelection(doc: DocumentSnapshot) {
        val hCode = doc.getString("householdCode") ?: "未知"
        addLog("✅ 語音選擇: $hCode")
        speakOut("好的，為您選擇 $hCode")
    }

    private fun setupModernAudio() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val btDevice = devices.find {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (btDevice != null) {
                audioManager.setCommunicationDevice(btDevice)
                addLog("✅ 藍牙通訊鎖定 (S+)")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            addLog("ℹ️ 啟動舊版 SCO")
        }
    }

    private fun resetToNormalAudioMode() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        addLog("♻️ 音訊模式已還原")
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
                sourceName = "JLab 藍牙耳機"
                break
            }
        }
        statusView.text = "收音路徑：$sourceName"
    }

    private fun convertSpokenPlate(text: String): String {
        var result = text
        val digitMap = mapOf(
            "零" to "0", "一" to "1", "二" to "2", "三" to "3", "四" to "4",
            "五" to "5", "六" to "6", "七" to "7", "八" to "8", "九" to "9"
        )
        val regex = Regex("([0-9一二三四五六七八九])個([0-9一二三四五六七八九零])")
        val matches = regex.findAll(result)
        for (match in matches) {
            val count = digitMap[match.groupValues[1]]?.toIntOrNull() ?: match.groupValues[1].toIntOrNull() ?: 0
            val digit = digitMap[match.groupValues[2]] ?: match.groupValues[2]
            if (count > 0) result = result.replace(match.value, digit.repeat(count))
        }
        return result.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
    }

    private fun setupFirestoreOffline() {
        val db = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder()
                .setSizeBytes(100 * 1024 * 1024) // 100MB
                .build())
            .build()
        db.firestoreSettings = settings
        addLog("📦 [保險箱] 強化初始化完成")
    }

    private fun syncAllDataForOffline(collectionPath: String) {
        val db = Firebase.firestore
        db.collection(collectionPath).get().addOnSuccessListener { documents ->
            addLog("🔄 $collectionPath 同步完成 (${documents.size()} 筆)")
        }.addOnFailureListener {
            addLog("❌ 同步失敗: ${it.message}")
        }
    }

    private fun addLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            tvDebugLog.append("\n[$time] $text")
            val scrollAmount = tvDebugLog.layout?.let {
                it.getLineTop(tvDebugLog.lineCount) - tvDebugLog.height
            } ?: 0
            if (scrollAmount > 0) tvDebugLog.scrollTo(0, scrollAmount)
        }
        Log.d("SmartGuard", "[$time] $text")
    }

    override fun onDestroy() {
        stopSpeechLogic()
        if (::tts.isInitialized) tts.shutdown()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        super.onDestroy()
    }
}