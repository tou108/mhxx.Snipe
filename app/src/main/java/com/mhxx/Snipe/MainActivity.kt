package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import android.util.Rational
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * MHXX お守りスナイプツール - 統合版
 * 🔧 修正版: クラッシュ修正 + ランタイム権限 + PiP対応 + フォアグラウンドサービス
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bluetoothHIDController: BluetoothHIDController
    private lateinit var arduinoManager: ArduinoAutomationManager

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_RC = 1001
    private val PROGRAM_IMPORT_RC = 1002
    private val PERMISSION_REQUEST_CODE = 2001

    // 🔧 追加: 必要な権限リスト
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

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
                    initializeUI()
                }
            }

            loadUrl("file:///android_asset/snipe_integrated.html")
        }

        setContentView(webView)
        setupBluetoothListener()
        setupArduinoListener()

        // 🔧 追加: 権限チェック → フォアグラウンドサービス開始
        if (hasAllPermissions()) {
            SnipeForegroundService.start(this)
        } else {
            requestBluetoothPermissions()
        }
    }

    // ============ 🔧 追加: ランタイム権限処理 ============

    private fun hasAllPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            REQUIRED_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            true  // Android 11以下はマニフェスト権限のみでOK
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val deniedPermissions = REQUIRED_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (deniedPermissions.isNotEmpty()) {
                // 権限の説明ダイアログ
                val needsRationale = deniedPermissions.any {
                    ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                }

                if (needsRationale) {
                    AlertDialog.Builder(this)
                        .setTitle("Bluetooth権限が必要です")
                        .setMessage("Switch接続にBluetooth権限が必要です。\n権限を許可してください。")
                        .setPositiveButton("許可する") { _, _ ->
                            ActivityCompat.requestPermissions(
                                this, deniedPermissions.toTypedArray(), PERMISSION_REQUEST_CODE
                            )
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                } else {
                    ActivityCompat.requestPermissions(
                        this, deniedPermissions.toTypedArray(), PERMISSION_REQUEST_CODE
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.d("MainActivity", "All permissions granted")
                SnipeForegroundService.start(this)
                Toast.makeText(this, "Bluetooth権限が許可されました", Toast.LENGTH_SHORT).show()
            } else {
                Log.w("MainActivity", "Some permissions denied")
                Toast.makeText(
                    this,
                    "一部の権限が拒否されました。Switch接続に問題が発生する可能性があります。",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ============ 🔧 追加: PictureInPicture (PiP) 対応 ============

    /**
     * ホームボタン押下など、他アプリ起動時に自動でPiPモードに入る
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .apply {
                        // Android 12以上: 自動PiP有効化
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setAutoEnterEnabled(true)
                        }
                    }
                    .build()
                enterPictureInPictureMode(params)
                Log.d("MainActivity", "Entered PiP mode")
            } catch (e: Exception) {
                Log.e("MainActivity", "PiP failed: ${e.message}", e)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            Log.d("MainActivity", "PiP mode ON")
        } else {
            Log.d("MainActivity", "PiP mode OFF - restored to full screen")
        }
    }

    // ============ Bluetooth / Arduino セットアップ ============

    private fun setupBluetoothListener() {
        bluetoothHIDController.setListener(object : BluetoothHIDController.ControllerListener {
            override fun onConnected(device: BluetoothDevice) {
                sendToJS("bluetoothStatus", mapOf(
                    "status" to "connected",
                    "device" to device.address,
                    "name" to (runCatching { device.name }.getOrDefault("Unknown"))
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
                // エラーをUIにも表示
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            override fun onStateChanged(state: String) {
                sendToJS("bluetoothState", mapOf("state" to state))
                Log.d("BT", "State: $state")
            }
        })
    }

    private fun setupArduinoListener() {
        arduinoManager.setListener(object : ArduinoAutomationManager.ExecutionListener {
            override fun onImportSuccess(fileName: String) {
                sendToJS("arduinoEvent", mapOf("type" to "importSuccess", "fileName" to fileName))
            }
            override fun onImportError(fileName: String, message: String) {
                sendToJS("arduinoEvent", mapOf("type" to "importError", "fileName" to fileName, "message" to message))
            }
            override fun onExecutionStart(programName: String) {
                sendToJS("arduinoEvent", mapOf("type" to "executionStart", "programName" to programName))
            }
            override fun onExecutionStop(programName: String, exitCode: Int) {
                sendToJS("arduinoEvent", mapOf("type" to "executionStop", "programName" to programName, "exitCode" to exitCode))
            }
            override fun onExecutionError(programName: String, message: String) {
                sendToJS("arduinoEvent", mapOf("type" to "executionError", "programName" to programName, "message" to message))
            }
            override fun onOutputReceived(programName: String, output: String) {
                sendToJS("arduinoOutput", mapOf("programName" to programName, "output" to output))
            }
        })
    }

    private fun initializeUI() {
        val js = """
            if (typeof initializeControlPanel === 'function') {
                initializeControlPanel();
            }
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 🔧 修正: JSONを安全にJavaScriptへ送信
     */
    private fun sendToJS(functionName: String, data: Map<String, Any?>) {
        try {
            val json = JSONObject(data as Map<String, *>)
            val b64 = Base64.encodeToString(
                json.toString().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val js = """
                (function(){
                    try {
                        var b='$b64';
                        var bin=atob(b);
                        var u8=new Uint8Array(bin.length);
                        for(var i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i);
                        var s=new TextDecoder('utf-8').decode(u8);
                        var obj=JSON.parse(s);
                        if(typeof $functionName==='function') $functionName(s);
                    } catch(e) { console.error('sendToJS error:', e); }
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        } catch (e: Exception) {
            Log.e("MainActivity", "sendToJS error: ${e.message}", e)
        }
    }

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
                    data.data?.let { uri -> importArduinoProgram(uri) }
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun importArduinoProgram(uri: Uri) {
        try {
            val contentResolver = contentResolver
            val displayName = DocumentsContract.getDocumentId(uri).substringAfterLast(":")
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fileName = displayName ?: "program_${System.currentTimeMillis()}.txt"
            val tempFile = File(cacheDir, fileName)
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            arduinoManager.importProgram(tempFile, fileName)
        } catch (e: Exception) {
            Log.e("ArduinoImport", "Error: ${e.message}", e)
            sendToJS("arduinoEvent", mapOf("type" to "importError", "message" to (e.message ?: "Unknown error")))
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        bluetoothHIDController.cleanup()
        arduinoManager.cleanup()
        // アプリ終了時のみサービス停止（PiPやバックグラウンドでは維持）
        if (!isChangingConfigurations) {
            SnipeForegroundService.stop(this)
        }
        super.onDestroy()
    }

    // ============ JavaScript ブリッジ ============

    inner class IntegratedBridge {

        // ---------- ML Kit OCR ----------

        @JavascriptInterface
        fun runMlKit(base64Image: String) {
            try {
                val bytes = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return returnError("画像デコード失敗")
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
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
            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
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

        // ---------- Bluetooth HID ----------

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
                val json = JSONObject(buttonData)
                val data = json.getString("data")
                val bytes = data.split(",").map { it.trim().toInt().toByte() }.toByteArray()
                bluetoothHIDController.sendControllerInput(bytes)
            } catch (e: Exception) {
                Log.e("Bridge", "Input error: ${e.message}", e)
            }
        }

        // ---------- Arduino ----------

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
                    "text/plain", "text/x-python", "application/json", "text/x-shellscript"
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
