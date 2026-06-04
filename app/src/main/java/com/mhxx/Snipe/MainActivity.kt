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
 * 🔧 修正版v2:
 *   - AndroidBridge 名前修正 (Android → AndroidBridge + 両方登録)
 *   - pressButton / pressButtons / tiltStick メソッド追加
 *   - HIDレポート構築ヘルパー追加
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bluetoothHIDController: BluetoothHIDController
    private lateinit var arduinoManager: ArduinoAutomationManager

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_RC = 1001
    private val PROGRAM_IMPORT_RC = 1002
    private val PERMISSION_REQUEST_CODE = 2001

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // =====================================================================
    // 🔧 修正: ボタン名 → HIDビット番号マッピング
    // HIDレポート構造 (7バイト):
    //   Byte 0 : ボタン 0- 7 (B, A, Y, X, L, R, ZL, ZR)
    //   Byte 1 : ボタン 8-15 (MINUS, PLUS, L_STICK, R_STICK, HOME, CAPTURE, DPAD_UP, DPAD_DOWN)
    //   Byte 2 : ボタン16-17 + パディング (DPAD_LEFT, DPAD_RIGHT)
    //   Byte 3 : 左スティック X (-127~127)
    //   Byte 4 : 左スティック Y (-127~127)
    //   Byte 5 : 右スティック X (-127~127)
    //   Byte 6 : 右スティック Y (-127~127)
    // =====================================================================
    private val BUTTON_MAP = mapOf(
        // フェースボタン
        "B"          to 0,  "A"       to 1,  "Y"       to 2,  "X"       to 3,
        // ショルダー
        "L"          to 4,  "R"       to 5,  "ZL"      to 6,  "ZR"      to 7,
        // システム
        "MINUS"      to 8,  "PLUS"    to 9,
        "L_STICK"    to 10, "R_STICK" to 11,
        "HOME"       to 12, "CAPTURE" to 13,
        // 十字キー
        "DPAD_UP"    to 14, "DPAD_DOWN"  to 15,
        "DPAD_LEFT"  to 16, "DPAD_RIGHT" to 17
    )

    /**
     * HIDレポートバイト列を生成
     *
     * スティック引数は -127〜127 の範囲で受け取り、
     * HIDディスクリプタに合わせた 0〜255 (中立=128) に変換する。
     * ✅ Fix2: signed(-127〜127) → unsigned(0〜255, center=128) に変換
     */
    private fun buildHidReport(
        buttons: Set<String> = emptySet(),
        lx: Int = 0, ly: Int = 0,
        rx: Int = 0, ry: Int = 0
    ): ByteArray {
        var b0 = 0; var b1 = 0; var b2 = 0
        for (btn in buttons) {
            val bit = BUTTON_MAP[btn.uppercase().trim()] ?: continue
            when {
                bit < 8  -> b0 = b0 or (1 shl bit)
                bit < 16 -> b1 = b1 or (1 shl (bit - 8))
                else     -> b2 = b2 or (1 shl (bit - 16))
            }
        }
        // -127〜127 → 0〜255 (center=128) に変換
        fun toUnsigned(v: Int): Byte =
            (v.coerceIn(-127, 127) + 128).coerceIn(0, 255).toByte()

        return byteArrayOf(
            b0.toByte(), b1.toByte(), b2.toByte(),
            toUnsigned(lx),
            toUnsigned(ly),
            toUnsigned(rx),
            toUnsigned(ry)
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

            val bridge = IntegratedBridge()
            // 🔧 修正: "Android" と "AndroidBridge" の両方に登録
            // - snipe_integrated.html の直接呼び出し (Android.xxx) に対応
            // - _parentAb() 経由の呼び出し (AndroidBridge.xxx) に対応
            addJavascriptInterface(bridge, "Android")
            addJavascriptInterface(bridge, "AndroidBridge")

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

        if (hasAllPermissions()) {
            SnipeForegroundService.start(this)
        } else {
            requestBluetoothPermissions()
        }
    }

    // ============ ランタイム権限 ============

    private fun hasAllPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            REQUIRED_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            true
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val deniedPermissions = REQUIRED_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (deniedPermissions.isNotEmpty()) {
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
                SnipeForegroundService.start(this)
                Toast.makeText(this, "Bluetooth権限が許可されました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "一部の権限が拒否されました。Switch接続に問題が発生する可能性があります。",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ============ PiP対応 ============

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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setAutoEnterEnabled(true)
                        }
                    }
                    .build()
                enterPictureInPictureMode(params)
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
            }

            override fun onDisconnected() {
                sendToJS("bluetoothStatus", mapOf("status" to "disconnected"))
            }

            override fun onError(message: String) {
                sendToJS("bluetoothError", mapOf("message" to message))
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            override fun onStateChanged(state: String) {
                sendToJS("bluetoothState", mapOf("state" to state))
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
            sendToJS("arduinoEvent", mapOf("type" to "importError", "message" to (e.message ?: "Unknown error")))
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        bluetoothHIDController.cleanup()
        arduinoManager.cleanup()
        if (!isChangingConfigurations) {
            SnipeForegroundService.stop(this)
        }
        super.onDestroy()
    }

    // =====================================================================
    // JavaScript ブリッジ
    // =====================================================================

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

        /**
         * 生バイト列で送信 (既存の手動HEX入力フォームから)
         * buttonData: JSON文字列 {"data":"0,0,0,0,0,0,0"}
         */
        @JavascriptInterface
        fun sendControllerInput(buttonData: String) {
            try {
                val json = JSONObject(buttonData)
                val data = json.getString("data")
                val bytes = data.split(",").map { it.trim().toInt().toByte() }.toByteArray()
                bluetoothHIDController.sendControllerInput(bytes)
            } catch (e: Exception) {
                Log.e("Bridge", "sendControllerInput error: ${e.message}", e)
            }
        }

        /**
         * ボタン名指定でプレス (pressCtrlBtn から呼ばれる)
         * @param button  ボタン名 (例: "A", "B", "DPAD_UP", "HOME" ...)
         * @param duration プレス保持時間 [ms]
         *
         * ✅ Fix3: release をメインスレッドではなく hidExecutor で送ることで
         *         press→release の順序が保証される（スレッド競合回避）
         */
        @JavascriptInterface
        fun pressButton(button: String, duration: Int) {
            Log.d("Bridge", "pressButton: $button for ${duration}ms")
            val pressReport   = buildHidReport(setOf(button))
            val releaseReport = buildHidReport()
            val holdMs = duration.toLong().coerceAtLeast(16L)

            bluetoothHIDController.sendControllerInput(pressReport)
            bluetoothHIDController.scheduleRelease(releaseReport, holdMs)
        }

        /**
         * 複数ボタン同時プレス (pressMultiBtn から呼ばれる)
         * @param buttonsJson JSON配列文字列 (例: '["A","B"]')
         * @param duration    プレス保持時間 [ms]
         */
        @JavascriptInterface
        fun pressButtons(buttonsJson: String, duration: Int) {
            try {
                val arr = JSONArray(buttonsJson)
                val buttons = (0 until arr.length()).map { arr.getString(it) }.toSet()
                Log.d("Bridge", "pressButtons: $buttons for ${duration}ms")

                val pressReport   = buildHidReport(buttons)
                val releaseReport = buildHidReport()
                val holdMs = duration.toLong().coerceAtLeast(16L)

                bluetoothHIDController.sendControllerInput(pressReport)
                bluetoothHIDController.scheduleRelease(releaseReport, holdMs)
            } catch (e: Exception) {
                Log.e("Bridge", "pressButtons error: ${e.message}", e)
            }
        }

        /**
         * スティック傾け (tiltStick / アナログパッド から呼ばれる)
         * @param side     "L" または "R"
         * @param x        X軸 (-1.0〜1.0)
         * @param y        Y軸 (-1.0〜1.0)
         * @param duration 保持時間 [ms]
         */
        @JavascriptInterface
        fun tiltStick(side: String, x: Double, y: Double, duration: Int) {
            Log.d("Bridge", "tiltStick: side=$side x=$x y=$y for ${duration}ms")

            val lx = if (side.uppercase() == "L") (x * 127).toInt() else 0
            val ly = if (side.uppercase() == "L") (y * 127).toInt() else 0
            val rx = if (side.uppercase() == "R") (x * 127).toInt() else 0
            val ry = if (side.uppercase() == "R") (y * 127).toInt() else 0

            val tiltReport    = buildHidReport(lx = lx, ly = ly, rx = rx, ry = ry)
            val releaseReport = buildHidReport()
            val holdMs = duration.toLong().coerceAtLeast(16L)

            bluetoothHIDController.sendControllerInput(tiltReport)
            bluetoothHIDController.scheduleRelease(releaseReport, holdMs)
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
