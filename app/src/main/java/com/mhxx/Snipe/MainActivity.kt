package com.mhxx.snipe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * MHXX お守りスナイプツール - 統合版
 * 機能:
 * - ML Kit による日本語OCR認識
 * - JoyConDroid の Bluetooth HID コントローラー統合
 * - Arduino 自動化プログラムのインポート・実行
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bluetoothHIDController: BluetoothHIDController
    private lateinit var arduinoManager: ArduinoAutomationManager

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_RC = 1001
    private val PROGRAM_IMPORT_RC = 1002

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初期化
        bluetoothHIDController = BluetoothHIDController(this)
        arduinoManager = ArduinoAutomationManager(this)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                defaultTextEncodingName = "UTF-8"
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }

            // JavaScript ブリッジ登録
            addJavascriptInterface(IntegratedBridge(), "Android")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }

                override fun onShowFileChooser(
                    wv: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileChooserCallback = callback
                    val intent = params.createIntent()
                    runCatching {
                        startActivityForResult(intent, FILE_CHOOSER_RC)
                    }.onFailure { callback.onReceiveValue(null) }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.d("MhxxSnipe", "Page loaded: $url")
                    // ページロード完了後、初期化スクリプトを実行
                    initializeUI()
                }
            }

            loadUrl("file:///android_asset/snipe_integrated.html")
        }

        setContentView(webView)

        // Bluetoothコントローラーのリスナー設定
        setupBluetoothListener()

        // Arduinoマネージャーのリスナー設定
        setupArduinoListener()
    }

    /**
     * Bluetooth コントローラーのリスナー設定
     */
    private fun setupBluetoothListener() {
        bluetoothHIDController.setListener(object : BluetoothHIDController.ControllerListener {
            override fun onConnected(device: BluetoothDevice) {
                sendToJS("bluetoothStatus", mapOf(
                    "status" to "connected",
                    "device" to device.address,
                    "name" to (device.name ?: "Unknown")
                ))
                Log.d("BT", "Connected: ${device.address}")
            }

            override fun onDisconnected() {
                sendToJS("bluetoothStatus", mapOf("status" to "disconnected"))
                Log.d("BT", "Disconnected")
            }

            override fun onError(message: String) {
                sendToJS("bluetoothError", mapOf("message" to message))
                Log.e("BT", "Error: $message")
            }

            override fun onStateChanged(state: String) {
                sendToJS("bluetoothState", mapOf("state" to state))
                Log.d("BT", "State: $state")
            }
        })
    }

    /**
     * Arduino マネージャーのリスナー設定
     */
    private fun setupArduinoListener() {
        arduinoManager.setListener(object : ArduinoAutomationManager.ExecutionListener {
            override fun onImportSuccess(fileName: String) {
                sendToJS("arduinoEvent", mapOf(
                    "type" to "importSuccess",
                    "fileName" to fileName
                ))
            }

            override fun onImportError(fileName: String, message: String) {
                sendToJS("arduinoEvent", mapOf(
                    "type" to "importError",
                    "fileName" to fileName,
                    "message" to message
                ))
            }

            override fun onExecutionStart(programName: String) {
                sendToJS("arduinoEvent", mapOf(
                    "type" to "executionStart",
                    "programName" to programName
                ))
            }

            override fun onExecutionStop(programName: String, exitCode: Int) {
                sendToJS("arduinoEvent", mapOf(
                    "type" to "executionStop",
                    "programName" to programName,
                    "exitCode" to exitCode
                ))
            }

            override fun onExecutionError(programName: String, message: String) {
                sendToJS("arduinoEvent", mapOf(
                    "type" to "executionError",
                    "programName" to programName,
                    "message" to message
                ))
            }

            override fun onOutputReceived(programName: String, output: String) {
                sendToJS("arduinoOutput", mapOf(
                    "programName" to programName,
                    "output" to output
                ))
            }
        })
    }

    /**
     * UI初期化スクリプト実行
     */
    private fun initializeUI() {
        val js = """
            if (typeof initializeControlPanel === 'function') {
                initializeControlPanel();
            }
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * JavaScriptへデータを送信（修正版: 型安全性向上）
     */
    private fun sendToJS(functionName: String, data: Map<String, Any?>) {
        val jsonData = JSONObject(data as Map<String, *>).toString()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

        val js = "if (typeof $functionName === 'function') { $functionName('$jsonData'); }"
        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    /**
     * ファイル選択コールバック
     */
    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FILE_CHOOSER_RC -> {
                fileChooserCallback?.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                )
                fileChooserCallback = null
            }
            PROGRAM_IMPORT_RC -> {
                if (resultCode == RESULT_OK && data != null) {
                    data.data?.let { uri ->
                        importArduinoProgram(uri)
                    }
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /**
     * Arduino プログラムをインポート
     */
    private fun importArduinoProgram(uri: Uri) {
        try {
            val contentResolver = contentResolver
            val displayName = DocumentsContract.getDocumentId(uri)
                .substringAfterLast(":")
            val inputStream = contentResolver.openInputStream(uri) ?: return

            val fileName = displayName ?: "program_${System.currentTimeMillis()}.txt"
            val tempFile = File(cacheDir, fileName)

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            arduinoManager.importProgram(tempFile, fileName)
        } catch (e: Exception) {
            Log.e("ArduinoImport", "Error: ${e.message}", e)
            sendToJS("arduinoEvent", mapOf(
                "type" to "importError",
                "message" to (e.message ?: "Unknown error")
            ))
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        bluetoothHIDController.cleanup()
        arduinoManager.cleanup()
        super.onDestroy()
    }

    /**
     * 統合 JavaScript ブリッジ
     */
    inner class IntegratedBridge {

        // ============ ML Kit OCR 関連 ============

        @JavascriptInterface
        fun runMlKit(base64Image: String) {
            try {
                val bytes = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return returnError("画像デコード失敗")

                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build()
                )

                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val json = JSONObject().apply { put("text", result.text) }
                        sendMlKitResult(json.toString())
                    }
                    .addOnFailureListener { e ->
                        returnError(e.localizedMessage ?: "ML Kit 認識失敗")
                    }
            } catch (e: Exception) {
                returnError(e.localizedMessage ?: "不明なエラー")
            }
        }

        private fun returnError(msg: String) {
            sendMlKitResult(JSONObject().apply { put("error", msg) }.toString())
        }

        private fun sendMlKitResult(json: String) {
            val b64 = Base64.encodeToString(
                json.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val js = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);
                    var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    receiveMlKitResult(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }

        // ============ Bluetooth HID コントローラー関連 ============

        @JavascriptInterface
        fun connectBluetoothSwitch(macAddress: String) {
            Log.d("Bridge", "Connecting to: $macAddress")
            bluetoothHIDController.connectToSwitch(macAddress)
        }

        @JavascriptInterface
        fun disconnectBluetoothSwitch() {
            bluetoothHIDController.disconnect()
        }

        @JavascriptInterface
        fun sendControllerInput(buttonData: String) {
            try {
                // JSON形式のボタンデータをバイト配列に変換
                val json = JSONObject(buttonData)
                val data = json.getString("data")
                val bytes = data.split(",").map { it.trim().toInt().toByte() }.toByteArray()
                bluetoothHIDController.sendControllerInput(bytes)
            } catch (e: Exception) {
                Log.e("Bridge", "Input error: ${e.message}", e)
            }
        }

        // ============ Arduino 自動化関連 ============

        @JavascriptInterface
        fun getImportedPrograms(): String {
            val programs = arduinoManager.getImportedPrograms()
            return JSONArray(programs).toString()
        }

        @JavascriptInterface
        fun importArduinoProgram() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/plain",
                    "text/x-python",
                    "application/json",
                    "text/x-shellscript"
                ))
            }
            startActivityForResult(intent, PROGRAM_IMPORT_RC)
        }

        @JavascriptInterface
        fun executeArduinoProgram(programName: String, args: String = "") {
            val argList = if (args.isBlank()) emptyList() else args.split(" ")
            arduinoManager.executeProgram(programName, argList)
        }

        @JavascriptInterface
        fun stopArduinoProgram(programName: String) {
            arduinoManager.stopProgram(programName)
        }

        @JavascriptInterface
        fun deleteArduinoProgram(programName: String): Boolean {
            return arduinoManager.deleteProgram(programName)
        }

        @JavascriptInterface
        fun getProgramContent(programName: String): String {
            return arduinoManager.getProgramContent(programName) ?: "ERROR: ファイルが見つかりません"
        }

        @JavascriptInterface
        fun updateProgramContent(programName: String, content: String): Boolean {
            return arduinoManager.updateProgramContent(programName, content)
        }
    }
}
