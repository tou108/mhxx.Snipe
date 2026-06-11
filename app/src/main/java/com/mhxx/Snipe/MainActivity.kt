package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.bluetooth.BluetoothAdapter
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
 *
 * v3 変更点:
 *   - startDiscoverableMode() ブリッジ追加 (Switch「持ち方・順番を変える」画面でのペアリング対応)
 *   - connectBluetoothSwitch() を BluetoothHIDController v3 API に合わせて整理
 *   - onConnected でデバイス名も JS に通知
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bluetoothHIDController: BluetoothHIDController
    private lateinit var arduinoManager: ArduinoAutomationManager

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_RC    = 1001
    private val PROGRAM_IMPORT_RC  = 1002
    private val PERMISSION_REQUEST_CODE = 2001
    private val DISCOVERABLE_RC    = 3001

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // =====================================================================
    // Nintendo Pro Controller ボタン → (バイトインデックス, ビットマスク)
    //
    // HID レポート 9 バイト構造:
    //   Byte 0 (右ボタン): Y=0x01 X=0x02 B=0x04 A=0x08 R=0x40 ZR=0x80
    //   Byte 1 (中ボタン): MINUS=0x01 PLUS=0x02 R_STICK=0x04 L_STICK=0x08
    //                      HOME=0x10 CAPTURE=0x20
    //   Byte 2 (左ボタン): DPAD_DOWN=0x01 DPAD_UP=0x02 DPAD_RIGHT=0x04
    //                      DPAD_LEFT=0x08 L=0x40 ZL=0x80
    //   Byte 3-5: 左スティック 12bit LE (中立=0x800)
    //   Byte 6-8: 右スティック 12bit LE (中立=0x800)
    // =====================================================================
    private val BUTTON_MAP = mapOf(
        "Y" to Pair(0, 0x01), "X"       to Pair(0, 0x02),
        "B" to Pair(0, 0x04), "A"       to Pair(0, 0x08),
        "R" to Pair(0, 0x40), "ZR"      to Pair(0, 0x80),
        "MINUS"      to Pair(1, 0x01),  "PLUS"    to Pair(1, 0x02),
        "R_STICK"    to Pair(1, 0x04),  "L_STICK" to Pair(1, 0x08),
        "HOME"       to Pair(1, 0x10),  "CAPTURE" to Pair(1, 0x20),
        "DPAD_DOWN"  to Pair(2, 0x01),  "DPAD_UP"    to Pair(2, 0x02),
        "DPAD_RIGHT" to Pair(2, 0x04),  "DPAD_LEFT"  to Pair(2, 0x08),
        "L"          to Pair(2, 0x40),  "ZL"         to Pair(2, 0x80)
    )

    /**
     * Nintendo Pro Controller 用 HID レポート (9 バイト) を生成する。
     * BluetoothHIDController.sendControllerInput() に渡す。
     */
    private fun buildHidReport(
        buttons: Set<String> = emptySet(),
        lx: Int = 0, ly: Int = 0,
        rx: Int = 0, ry: Int = 0
    ): ByteArray {
        var b0 = 0; var b1 = 0; var b2 = 0
        for (btn in buttons) {
            val (byteIdx, mask) = BUTTON_MAP[btn.uppercase().trim()] ?: continue
            when (byteIdx) { 0 -> b0 = b0 or mask; 1 -> b1 = b1 or mask; 2 -> b2 = b2 or mask }
        }
        // -127〜127 → 0〜4095 (12bit, 中立=2048)
        fun to12bit(v: Int): Int =
            ((v.coerceIn(-127, 127) + 127) * 4096 / 254).coerceIn(0, 4095)

        val lxV = to12bit(lx); val lyV = to12bit(ly)
        val rxV = to12bit(rx); val ryV = to12bit(ry)

        // 12bit X + 12bit Y → 3 bytes LE
        return byteArrayOf(
            b0.toByte(), b1.toByte(), b2.toByte(),
            (lxV and 0xFF).toByte(),
            (((lxV shr 8) and 0x0F) or ((lyV and 0x0F) shl 4)).toByte(),
            ((lyV shr 4) and 0xFF).toByte(),
            (rxV and 0xFF).toByte(),
            (((rxV shr 8) and 0x0F) or ((ryV and 0x0F) shl 4)).toByte(),
            ((ryV shr 4) and 0xFF).toByte()
        )
    }

    // =====================================================================
    // ライフサイクル
    // =====================================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothHIDController = BluetoothHIDController(this)
        arduinoManager         = ArduinoAutomationManager(this)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled        = true
                domStorageEnabled        = true
                allowFileAccess          = true
                allowContentAccess       = true
                mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                defaultTextEncodingName  = "UTF-8"
                useWideViewPort          = true
                loadWithOverviewMode     = true
                setSupportZoom(true)
                builtInZoomControls      = true
                displayZoomControls      = false
            }

            val bridge = IntegratedBridge()
            addJavascriptInterface(bridge, "Android")
            addJavascriptInterface(bridge, "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val grantable = request.resources.filter {
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        }.toTypedArray()
                        if (grantable.isNotEmpty()) request.grant(grantable) else request.deny()
                    }
                }
                override fun onShowFileChooser(
                    wv: WebView, callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileChooserCallback = callback
                    runCatching { startActivityForResult(params.createIntent(), FILE_CHOOSER_RC) }
                        .onFailure { callback.onReceiveValue(null) }
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

        if (hasAllPermissions()) SnipeForegroundService.start(this)
        else requestBluetoothPermissions()
    }

    // =====================================================================
    // パーミッション
    // =====================================================================

    private fun hasAllPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            REQUIRED_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        else true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val denied = REQUIRED_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isEmpty()) return
            if (denied.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }) {
                AlertDialog.Builder(this)
                    .setTitle("Bluetooth権限が必要です")
                    .setMessage("Switch接続にBluetooth権限が必要です。")
                    .setPositiveButton("許可する") { _, _ ->
                        ActivityCompat.requestPermissions(this, denied.toTypedArray(), PERMISSION_REQUEST_CODE)
                    }
                    .setNegativeButton("キャンセル", null).show()
            } else {
                ActivityCompat.requestPermissions(this, denied.toTypedArray(), PERMISSION_REQUEST_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                SnipeForegroundService.start(this)
                Toast.makeText(this, "Bluetooth権限が許可されました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this,
                    "一部の権限が拒否されました。Switch接続に問題が発生する可能性があります。",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // =====================================================================
    // PiP
    // =====================================================================

    override fun onUserLeaveHint() { super.onUserLeaveHint(); enterPipMode() }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(true) }
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) { Log.e("MainActivity", "PiP failed", e) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig) }

    // =====================================================================
    // Bluetooth / Arduino セットアップ
    // =====================================================================

    private fun setupBluetoothListener() {
        bluetoothHIDController.listener = object : BluetoothHIDController.ControllerListener {
            override fun onConnected(device: BluetoothDevice) {
                sendToJS("bluetoothStatus", mapOf(
                    "status" to "connected",
                    "device" to device.address,
                    "name"   to runCatching { device.name }.getOrDefault("Unknown")
                ))
            }
            override fun onDisconnected() {
                sendToJS("bluetoothStatus", mapOf("status" to "disconnected"))
            }
            override fun onError(message: String) {
                sendToJS("bluetoothError", mapOf("message" to message))
                runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
            }
            override fun onStateChanged(state: String) {
                sendToJS("bluetoothState", mapOf("state" to state))
            }
        }
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
        webView.evaluateJavascript(
            "if(typeof initializeControlPanel==='function') initializeControlPanel();", null)
    }

    private fun sendToJS(functionName: String, data: Map<String, Any?>) {
        try {
            val b64 = Base64.encodeToString(
                JSONObject(data as Map<String, *>).toString().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val js = """
                (function(){
                    try{
                        var b='$b64';
                        var bin=atob(b);
                        var u8=new Uint8Array(bin.length);
                        for(var i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i);
                        var s=new TextDecoder('utf-8').decode(u8);
                        var obj=JSON.parse(s);
                        if(typeof $functionName==='function') $functionName(s);
                    }catch(e){console.error('sendToJS error:',e);}
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        } catch (e: Exception) {
            Log.e("MainActivity", "sendToJS error: ${e.message}", e)
        }
    }

    // =====================================================================
    // Activity 結果
    // =====================================================================

    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FILE_CHOOSER_RC -> {
                fileChooserCallback?.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileChooserCallback = null
            }
            PROGRAM_IMPORT_RC -> {
                if (resultCode == RESULT_OK && data != null)
                    data.data?.let { importArduinoProgram(it) }
            }
            DISCOVERABLE_RC -> {
                // 発見可能ダイアログの結果 (resultCode = 発見可能秒数 or RESULT_CANCELED)
                if (resultCode > 0) {
                    Log.d("MainActivity", "Discoverable for ${resultCode}s — calling registerAsController()")
                    bluetoothHIDController.registerAsController()
                } else {
                    sendToJS("bluetoothState", mapOf("state" to "発見可能モードがキャンセルされました"))
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun importArduinoProgram(uri: Uri) {
        try {
            val displayName = DocumentsContract.getDocumentId(uri).substringAfterLast(":")
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fileName    = displayName ?: "program_${System.currentTimeMillis()}.txt"
            val tempFile    = File(cacheDir, fileName)
            inputStream.use { i -> FileOutputStream(tempFile).use { o -> i.copyTo(o) } }
            arduinoManager.importProgram(tempFile, fileName)
        } catch (e: Exception) {
            sendToJS("arduinoEvent", mapOf("type" to "importError", "message" to (e.message ?: "Unknown")))
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        bluetoothHIDController.cleanup()
        arduinoManager.cleanup()
        if (!isChangingConfigurations) SnipeForegroundService.stop(this)
        super.onDestroy()
    }

    // =====================================================================
    // JavaScript ブリッジ
    // =====================================================================

    inner class IntegratedBridge {

        // ── ML Kit OCR ──────────────────────────────────────────────────

        @JavascriptInterface
        fun runMlKit(base64Image: String) {
            try {
                val bytes  = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return returnError("画像デコード失敗")
                val image  = InputImage.fromBitmap(bitmap, 0)
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    .process(image)
                    .addOnSuccessListener { sendMlKitResult(JSONObject().apply { put("text", it.text) }.toString()) }
                    .addOnFailureListener { returnError(it.localizedMessage ?: "ML Kit 失敗") }
            } catch (e: Exception) { returnError(e.localizedMessage ?: "不明なエラー") }
        }

        private fun returnError(msg: String) =
            sendMlKitResult(JSONObject().apply { put("error", msg) }.toString())

        private fun sendMlKitResult(json: String) {
            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            runOnUiThread {
                webView.evaluateJavascript("""
                    (function(){var b='$b64';var bin=atob(b);var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);receiveMlKitResult(s);})();
                """.trimIndent(), null)
            }
        }

        // ── Bluetooth HID ──────────────────────────────────────────────

        /**
         * アクティブ接続: Switch の MAC アドレスを指定して接続する。
         * Switch は「持ち方・順番を変える」画面を開いておくこと。
         */
        @JavascriptInterface
        fun connectBluetoothSwitch(macAddress: String) {
            Log.d("Bridge", "connectBluetoothSwitch: $macAddress")
            bluetoothHIDController.connectToSwitch(macAddress)
        }

        /**
         * パッシブ接続 (発見可能モード):
         * Android を発見可能にして Switch からの接続を待つ。
         *
         * 手順:
         *   1. JS から startDiscoverableMode() を呼ぶ
         *   2. システムダイアログが表示される → ユーザーが「許可」
         *   3. Switch の「持ち方・順番を変える」画面を開く
         *   4. Switch が "Pro Controller" を発見して接続する
         */
        @JavascriptInterface
        fun startDiscoverableMode() {
            Log.d("Bridge", "startDiscoverableMode called")
            runOnUiThread {
                try {
                    @Suppress("DEPRECATION")
                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)  // 2分間
                    }
                    startActivityForResult(intent, DISCOVERABLE_RC)
                } catch (e: Exception) {
                    Log.e("Bridge", "startDiscoverableMode error: ${e.message}")
                    sendToJS("bluetoothError", mapOf("message" to "発見可能モード開始失敗: ${e.message}"))
                }
            }
        }

        @JavascriptInterface
        fun disconnectBluetoothSwitch() {
            bluetoothHIDController.disconnect()
        }

        @JavascriptInterface
        fun isBluetoothConnected(): Boolean = bluetoothHIDController.isConnected

        /**
         * 生バイト列で送信 (JSON形式: {"data":"0,0,0,0,0,0,0,0,0"})
         */
        @JavascriptInterface
        fun sendControllerInput(buttonData: String) {
            try {
                val data  = JSONObject(buttonData).getString("data")
                val bytes = data.split(",").map { it.trim().toInt().toByte() }.toByteArray()
                bluetoothHIDController.sendControllerInput(bytes)
            } catch (e: Exception) {
                Log.e("Bridge", "sendControllerInput error: ${e.message}", e)
            }
        }

        /**
         * ボタン名で単発プレス。
         * @param button   ボタン名 ("A", "B", "DPAD_UP", "HOME" など)
         * @param duration 保持時間 [ms]
         */
        @JavascriptInterface
        fun pressButton(button: String, duration: Int) {
            Log.d("Bridge", "pressButton: $button ${duration}ms")
            val press   = buildHidReport(setOf(button))
            val release = buildHidReport()
            bluetoothHIDController.sendControllerInput(press)
            bluetoothHIDController.scheduleRelease(release, duration.toLong().coerceAtLeast(16L))
        }

        /**
         * 複数ボタン同時プレス。
         * @param buttonsJson JSON配列文字列 例: '["A","B"]'
         * @param duration    保持時間 [ms]
         */
        @JavascriptInterface
        fun pressButtons(buttonsJson: String, duration: Int) {
            try {
                val arr     = JSONArray(buttonsJson)
                val buttons = (0 until arr.length()).map { arr.getString(it) }.toSet()
                Log.d("Bridge", "pressButtons: $buttons ${duration}ms")
                val press   = buildHidReport(buttons)
                val release = buildHidReport()
                bluetoothHIDController.sendControllerInput(press)
                bluetoothHIDController.scheduleRelease(release, duration.toLong().coerceAtLeast(16L))
            } catch (e: Exception) {
                Log.e("Bridge", "pressButtons error: ${e.message}", e)
            }
        }

        /**
         * スティック傾け。
         * @param side     "L" または "R"
         * @param x        X軸 (-1.0〜1.0)
         * @param y        Y軸 (-1.0〜1.0)
         * @param duration 保持時間 [ms]
         */
        @JavascriptInterface
        fun tiltStick(side: String, x: Double, y: Double, duration: Int) {
            Log.d("Bridge", "tiltStick: $side x=$x y=$y ${duration}ms")
            val lx = if (side.uppercase() == "L") (x * 127).toInt() else 0
            val ly = if (side.uppercase() == "L") (y * 127).toInt() else 0
            val rx = if (side.uppercase() == "R") (x * 127).toInt() else 0
            val ry = if (side.uppercase() == "R") (y * 127).toInt() else 0
            val tilt    = buildHidReport(lx = lx, ly = ly, rx = rx, ry = ry)
            val release = buildHidReport()
            bluetoothHIDController.sendControllerInput(tilt)
            bluetoothHIDController.scheduleRelease(release, duration.toLong().coerceAtLeast(16L))
        }

        // ── Arduino ─────────────────────────────────────────────────────

        @JavascriptInterface
        fun getImportedPrograms(): String = JSONArray(arduinoManager.getImportedPrograms()).toString()

        @JavascriptInterface
        fun importArduinoProgram() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                // .ino / .txt / .py / .sh / .json すべて受け入れる
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/plain",
                    "text/x-arduino",
                    "application/x-arduino",
                    "text/x-python",
                    "application/json",
                    "text/x-shellscript"
                ))
            }
            startActivityForResult(intent, PROGRAM_IMPORT_RC)
        }

        @JavascriptInterface
        fun executeArduinoProgram(programName: String, args: String = "") {
            arduinoManager.executeProgram(programName,
                if (args.isBlank()) emptyList() else args.split(" "))
        }

        @JavascriptInterface
        fun stopArduinoProgram(programName: String) = arduinoManager.stopProgram(programName)

        @JavascriptInterface
        fun deleteArduinoProgram(programName: String): Boolean =
            arduinoManager.deleteProgram(programName)

        @JavascriptInterface
        fun getProgramContent(programName: String): String =
            arduinoManager.getProgramContent(programName) ?: "ERROR: ファイルが見つかりません"

        @JavascriptInterface
        fun updateProgramContent(programName: String, content: String): Boolean =
            arduinoManager.updateProgramContent(programName, content)
    }
}
