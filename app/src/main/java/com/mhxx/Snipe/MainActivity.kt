package com.mhxx.snipe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tcpBridge: TcpBridge
    private lateinit var proConBridge: ProConBridge
    private lateinit var bluetoothBridge: BluetoothBridge   // ← 追加: BT HID ブリッジ

    // ── Pro Controller ボタンマッピング ───────────────────────────────
    companion object {
        // Android KeyCode → Switch HID ボタンビットマスク
        val PROCON_BUTTON_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A      to 0x0004,   // A → Switch A（このProConはボタンラベル＝Switchレイアウト）
            KeyEvent.KEYCODE_BUTTON_B      to 0x0002,   // B → Switch B
            KeyEvent.KEYCODE_BUTTON_X      to 0x0008,   // X
            KeyEvent.KEYCODE_BUTTON_Y      to 0x0001,   // Y
            KeyEvent.KEYCODE_BUTTON_L1     to 0x0010,   // L
            KeyEvent.KEYCODE_BUTTON_R1     to 0x0020,   // R
            KeyEvent.KEYCODE_BUTTON_L2     to 0x0040,   // ZL
            KeyEvent.KEYCODE_BUTTON_R2     to 0x0080,   // ZR
            KeyEvent.KEYCODE_BUTTON_SELECT to 0x0100,   // MINUS (−)
            KeyEvent.KEYCODE_BUTTON_START  to 0x0200,   // PLUS  (+)
            KeyEvent.KEYCODE_BUTTON_THUMBL to 0x0400,   // Lスティック押込
            KeyEvent.KEYCODE_BUTTON_THUMBR to 0x0800,   // Rスティック押込
            KeyEvent.KEYCODE_BUTTON_MODE   to 0x1000    // HOME
        )
        // Switch ビットマスク → ボタン名（JS 通知用）
        val SWITCH_BUTTON_NAMES = mapOf(
            0x0001 to "Y",       0x0002 to "B",       0x0004 to "A",
            0x0008 to "X",       0x0010 to "L",       0x0020 to "R",
            0x0040 to "ZL",      0x0080 to "ZR",      0x0100 to "MINUS",
            0x0200 to "PLUS",    0x0400 to "LSTICK",  0x0800 to "RSTICK",
            0x1000 to "HOME",    0x2000 to "CAPTURE"
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tcpBridge   = TcpBridge()
        proConBridge = ProConBridge()
        bluetoothBridge = BluetoothBridge()             // ← 追加

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled                = true
                domStorageEnabled                = true
                allowFileAccess                  = true
                allowContentAccess               = true
                mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                defaultTextEncodingName          = "UTF-8"
                useWideViewPort                  = true
                loadWithOverviewMode             = true
                setSupportZoom(true)
                builtInZoomControls              = true
                displayZoomControls              = false
            }

            // JavaScript ブリッジ登録
            addJavascriptInterface(MlKitBridge(), "Android")
            addJavascriptInterface(tcpBridge,     "SwitchTCP")
            addJavascriptInterface(proConBridge,  "ProCon")   // ← 新規
            addJavascriptInterface(bluetoothBridge, "SwitchBT") // ← 追加: BT HID

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    android.util.Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
                override fun onShowFileChooser(
                    wv: WebView,
                    callback: ValueCallback<Array<android.net.Uri>>,
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
                    android.util.Log.d("MhxxSnipe", "Page loaded: $url")
                }
            }

            loadUrl("file:///android_asset/snipe_modified.html")
        }

        setContentView(webView)
    }

    override fun onDestroy() {
        super.onDestroy()
        tcpBridge.disconnect()
        bluetoothBridge.cleanup()   // ← 追加
    }

    // ─────────────────────────────────────────────────────────────────
    // Pro Controller イベント捕捉
    // WebView よりも前に Activity 層でゲームパッド入力をインターセプト
    // ─────────────────────────────────────────────────────────────────

    /** キーイベント（ボタン press / release）*/
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (proConBridge.isPassthrough()) {
            val device = InputDevice.getDevice(event.deviceId)
            if (isGamepadDevice(device)) {
                val mask = PROCON_BUTTON_MAP[event.keyCode]
                if (mask != null) {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 0) {   // キーリピートは無視
                                tcpBridge.pressButton(mask)
                                bluetoothBridge.pressButton(mask)   // ← 追加: BT HID にも転送
                                proConBridge.notifyButtonEvent(mask, true)
                            }
                        }
                        KeyEvent.ACTION_UP -> {
                            tcpBridge.releaseButton(mask)
                            bluetoothBridge.releaseButton(mask)     // ← 追加: BT HID にも転送
                            proConBridge.notifyButtonEvent(mask, false)
                        }
                    }
                    return true   // WebView に渡さない
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** モーションイベント（スティック・Hat）*/
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (proConBridge.isPassthrough() &&
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            proConBridge.handleMotionEvent(event)
            return true   // WebView に渡さない
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ── ヘルパー ──────────────────────────────────────────────────────

    private fun isGamepadDevice(device: InputDevice?): Boolean {
        if (device == null) return false
        val src = device.sources
        return (src and InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD ||
               (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** 軸値 (−1.0 〜 1.0)  →  スティック値 (0 〜 255) 、デッドゾーン 8% */
    private fun axisToStick(value: Float): Int {
        val dz = if (Math.abs(value) < 0.08f) 0f else value
        return ((dz + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
    }

    /** HAT_X / HAT_Y 軸 → Switch Hat 方向インデックス (0〜7, 8=NEUTRAL) */
    private fun hatAxisToHat(hatX: Float, hatY: Float): Int {
        val x = when {
            hatX > 0.5f  ->  1
            hatX < -0.5f -> -1
            else         ->  0
        }
        val y = when {
            hatY > 0.5f  ->  1
            hatY < -0.5f -> -1
            else         ->  0
        }
        return when {
            x == 0  && y == -1 -> 0   // UP
            x == 1  && y == -1 -> 1   // UP_RIGHT
            x == 1  && y == 0  -> 2   // RIGHT
            x == 1  && y == 1  -> 3   // DOWN_RIGHT
            x == 0  && y == 1  -> 4   // DOWN
            x == -1 && y == 1  -> 5   // DOWN_LEFT
            x == -1 && y == 0  -> 6   // LEFT
            x == -1 && y == -1 -> 7   // UP_LEFT
            else               -> 8   // NEUTRAL
        }
    }

    // ── ファイル選択コールバック ──────────────────────────────────────
    private var fileChooserCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val FILE_CHOOSER_RC = 1001

    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == FILE_CHOOSER_RC) {
            fileChooserCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            fileChooserCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // =========================================================
    // Bluetooth HID Bridge（新規）
    // BluetoothHIDController を JS/Activity から操作し、
    // 物理プロコン入力を Switch に 0x30 Full Report として転送する
    // =========================================================
    @SuppressLint("MissingPermission")
    inner class BluetoothBridge : BluetoothHIDController.ControllerListener {

        private val hidController = BluetoothHIDController(this@MainActivity)

        // ── HID 状態管理 ──────────────────────────────────────────────
        /** ボタンビットマスク（PROCON_BUTTON_MAP 準拠の 16bit 統一形式）*/
        @Volatile private var buttonMask  = 0
        /** D-pad 方向インデックス（0–7, 8=NEUTRAL）*/
        @Volatile private var hatIndex    = 8
        /** スティック値（0–255, center=128）*/
        @Volatile private var lxState     = 128
        @Volatile private var lyState     = 128
        @Volatile private var rxState     = 128
        @Volatile private var ryState     = 128
        /** 接続フラグ（ControllerListener コールバックで更新）*/
        @Volatile private var btConnected = false

        init { hidController.listener = this }

        // ── JavaScript インターフェース ────────────────────────────────

        /** Switch に Bluetooth HID として接続する */
        @JavascriptInterface
        fun connect(mac: String) = hidController.connectToSwitch(mac)

        /** 切断 */
        @JavascriptInterface
        fun disconnect() = hidController.disconnect()

        /** 接続状態を返す */
        @JavascriptInterface
        fun isConnected(): Boolean = btConnected

        // ── Activity から呼ばれる内部 API ─────────────────────────────

        /** ボタン押下（mask は PROCON_BUTTON_MAP 形式）*/
        fun pressButton(mask: Int) {
            buttonMask = buttonMask or mask
            submitReport()
        }

        /** ボタン解放 */
        fun releaseButton(mask: Int) {
            buttonMask = buttonMask and mask.inv()
            submitReport()
        }

        /**
         * スティック更新（0–255, center=128）
         * TCP 側と同じ値をそのまま受け取り、packStick12 内で Y 軸反転を行う
         */
        fun moveSticks(lx: Int, ly: Int, rx: Int, ry: Int) {
            lxState = lx; lyState = ly; rxState = rx; ryState = ry
            submitReport()
        }

        /** D-pad 更新（0–7, 8=NEUTRAL）*/
        fun moveHat(hat: Int) {
            hatIndex = hat
            submitReport()
        }

        /** 全入力をニュートラルに戻す */
        fun resetAll() {
            buttonMask = 0; hatIndex = 8
            lxState = 128; lyState = 128; rxState = 128; ryState = 128
            submitReport()
        }

        fun cleanup() = hidController.cleanup()

        // ── レポートビルド & 送信 ──────────────────────────────────────

        private fun submitReport() {
            // sendControllerInput 内部で connectedDevice を確認するため、未接続時は空振りになる
            hidController.sendControllerInput(buildHidBytes())
        }

        /**
         * PROCON_BUTTON_MAP 統一マスク → Nintendo Pro Controller 9バイト HID フォーマット
         *
         * buf[0] 右ボタン : Y=0x01, X=0x02, B=0x04, A=0x08, R=0x40, ZR=0x80
         * buf[1] 中ボタン : MINUS=0x01, PLUS=0x02, RSTICK=0x04, LSTICK=0x08, HOME=0x10, CAPTURE=0x20
         * buf[2] 左ボタン : DOWN=0x01, UP=0x02, RIGHT=0x04, LEFT=0x08, L=0x40, ZL=0x80
         * buf[3–5] 左スティック 12bit LE
         * buf[6–8] 右スティック 12bit LE
         */
        private fun buildHidBytes(): ByteArray {
            val buf = ByteArray(9)
            var b0 = 0; var b1 = 0; var b2 = 0
            val m = buttonMask

            // 右ボタン byte
            if (m and 0x0001 != 0) b0 = b0 or 0x01   // Y
            if (m and 0x0008 != 0) b0 = b0 or 0x02   // X
            if (m and 0x0002 != 0) b0 = b0 or 0x04   // B
            if (m and 0x0004 != 0) b0 = b0 or 0x08   // A
            if (m and 0x0020 != 0) b0 = b0 or 0x40   // R
            if (m and 0x0080 != 0) b0 = b0 or 0x80   // ZR
            // 中ボタン byte
            if (m and 0x0100 != 0) b1 = b1 or 0x01   // MINUS
            if (m and 0x0200 != 0) b1 = b1 or 0x02   // PLUS
            if (m and 0x0800 != 0) b1 = b1 or 0x04   // RSTICK
            if (m and 0x0400 != 0) b1 = b1 or 0x08   // LSTICK
            if (m and 0x1000 != 0) b1 = b1 or 0x10   // HOME
            if (m and 0x2000 != 0) b1 = b1 or 0x20   // CAPTURE
            // 左ボタン byte
            if (m and 0x0010 != 0) b2 = b2 or 0x40   // L
            if (m and 0x0040 != 0) b2 = b2 or 0x80   // ZL

            // D-pad → 左ボタン byte のビット
            when (hatIndex) {
                0 ->                             b2 = b2 or 0x02           // UP
                1 -> { b2 = b2 or 0x02;         b2 = b2 or 0x04 }         // UP+RIGHT
                2 ->                             b2 = b2 or 0x04           // RIGHT
                3 -> { b2 = b2 or 0x01;         b2 = b2 or 0x04 }         // DOWN+RIGHT
                4 ->                             b2 = b2 or 0x01           // DOWN
                5 -> { b2 = b2 or 0x01;         b2 = b2 or 0x08 }         // DOWN+LEFT
                6 ->                             b2 = b2 or 0x08           // LEFT
                7 -> { b2 = b2 or 0x02;         b2 = b2 or 0x08 }         // UP+LEFT
                // 8 = NEUTRAL: nothing
            }

            buf[0] = b0.toByte()
            buf[1] = b1.toByte()
            buf[2] = b2.toByte()

            // スティック変換（Y軸は反転: Nintendo フォーマット = 大→上）
            packStick12(buf, 3, lxState, lyState)
            packStick12(buf, 6, rxState, ryState)

            return buf
        }

        /**
         * スティック値（0–255）を 12bit 値へ変換し 3バイトにパックする
         *   X: そのまま  0=左, 128=中立, 255=右
         *   Y: 反転      Android AXIS_Y = -1.0(上) → 0(上) → Nintendo 上=大(0xFFF方向)
         *      (255 - y255) << 4 にすることで中立 128 → 0x800 になる
         *
         * パック形式 (Nintendo LE 12bit×2 → 3bytes):
         *   buf[o+0] = X[7:0]
         *   buf[o+1] = X[11:8] | (Y[3:0] << 4)
         *   buf[o+2] = Y[11:4]
         */
        private fun packStick12(buf: ByteArray, offset: Int, x255: Int, y255: Int) {
            val x = (x255 shl 4) and 0xFFF
            val y = ((255 - y255) shl 4) and 0xFFF   // Y軸反転
            buf[offset]     = (x and 0xFF).toByte()
            buf[offset + 1] = ((x shr 8) or ((y and 0x0F) shl 4)).toByte()
            buf[offset + 2] = (y shr 4).toByte()
        }

        // ── ControllerListener 実装 ────────────────────────────────────

        override fun onConnected(device: BluetoothDevice) {
            btConnected = true
            notifyBtJs("""{"event":"connected","address":"${device.address}"}""")
        }

        override fun onDisconnected() {
            btConnected = false
            notifyBtJs("""{"event":"disconnected"}""")
        }

        override fun onError(message: String) {
            notifyBtJs("""{"event":"error","message":"${message.replace("\"", "'")}"}""")
        }

        override fun onStateChanged(state: String) {
            notifyBtJs("""{"event":"state","state":"${state.replace("\"", "'")}"}""")
        }

        /** JS の receiveBtStatus(json) へ Base64 経由で通知 */
        private fun notifyBtJs(json: String) {
            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js  = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    if(typeof receiveBtStatus==='function')receiveBtStatus(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // =========================================================
    // ML Kit Bridge（既存）
    // =========================================================
    inner class MlKitBridge {

        @JavascriptInterface
        fun runMlKit(base64Image: String) {
            try {
                val bytes  = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return returnError("画像デコード失敗")

                val image      = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build()
                )
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        sendToJs(JSONObject().apply { put("text", result.text) }.toString())
                    }
                    .addOnFailureListener { e ->
                        returnError(e.localizedMessage ?: "ML Kit 認識失敗")
                    }
            } catch (e: Exception) {
                returnError(e.localizedMessage ?: "不明なエラー")
            }
        }

        private fun returnError(msg: String) =
            sendToJs(JSONObject().apply { put("error", msg) }.toString())

        private fun sendToJs(json: String) {
            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js  = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    receiveMlKitResult(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // =========================================================
    // TCP Bridge（既存）
    // =========================================================
    inner class TcpBridge {

        // inner class は companion object を持てないため、インスタンスプロパティとして定義
        private val PROTO_BINARY     = 0
        private val PROTO_SYSBOTBASE = 1

        private val HAT_NAMES = arrayOf(
            "DUP","DDOWN","DLEFT","DRIGHT"
        )
        // hatIndex(0-7, 8=NEUTRAL) → 同時押しすべき方向ボタンのビットマスク
        // sys-botbase は DUP_RIGHT のような斜め名を解釈できないため、
        // DUP/DDOWN/DLEFT/DRIGHT の組み合わせ(同時 press)で表現する
        private val HAT_DIR_MASK = intArrayOf(
            0x1,       // 0 UP          -> DUP
            0x1 or 0x8,// 1 UP_RIGHT    -> DUP + DRIGHT
            0x8,       // 2 RIGHT       -> DRIGHT
            0x2 or 0x8,// 3 DOWN_RIGHT  -> DDOWN + DRIGHT
            0x2,       // 4 DOWN        -> DDOWN
            0x2 or 0x4,// 5 DOWN_LEFT   -> DDOWN + DLEFT
            0x4,       // 6 LEFT        -> DLEFT
            0x1 or 0x4 // 7 UP_LEFT     -> DUP + DLEFT
        )
        private val BUTTON_NAMES = mapOf(
            0x0001 to "Y",    0x0002 to "B",    0x0004 to "A",    0x0008 to "X",
            0x0010 to "L",    0x0020 to "R",    0x0040 to "ZL",   0x0080 to "ZR",
            0x0100 to "MINUS",0x0200 to "PLUS",
            0x0400 to "LSTICK",0x0800 to "RSTICK",
            0x1000 to "HOME", 0x2000 to "CAPTURE"
        )

        private val executor     = Executors.newSingleThreadExecutor()
        private val _connected   = AtomicBoolean(false)
        private var socket       : Socket?       = null
        private var outputStream : OutputStream? = null
        private var protocol     : Int           = PROTO_SYSBOTBASE

        @Volatile private var buttonState = 0
        @Volatile private var hatState    = 8
        @Volatile private var lxState     = 128
        @Volatile private var lyState     = 128
        @Volatile private var rxState     = 128
        @Volatile private var ryState     = 128

        @JavascriptInterface
        fun connect(ip: String, port: Int, proto: Int) {
            protocol = proto
            executor.submit {
                try {
                    socket?.close()
                    val s = Socket()
                    s.soTimeout = 3000
                    s.connect(InetSocketAddress(ip, port), 4000)
                    outputStream = s.getOutputStream()
                    socket = s
                    _connected.set(true)
                    if (protocol == PROTO_SYSBOTBASE) {
                        sendText("configure mainLoopSleepTime 50\n")
                        sendNeutralState()
                    }
                    notifyJs(true, "接続成功 ${ip}:${port}")
                } catch (e: Exception) {
                    _connected.set(false)
                    notifyJs(false, "接続失敗: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            executor.submit {
                try {
                    if (_connected.get()) { sendNeutralState(); Thread.sleep(50) }
                    outputStream?.close(); socket?.close()
                } catch (_: Exception) {}
                _connected.set(false); socket = null; outputStream = null
                notifyJs(false, "切断しました")
            }
        }

        @JavascriptInterface fun isConnected() = _connected.get()

        @JavascriptInterface
        fun pressButton(button: Int) { buttonState = buttonState or button; submitReport() }

        @JavascriptInterface
        fun releaseButton(button: Int) { buttonState = buttonState and button.inv(); submitReport() }

        @JavascriptInterface
        fun releaseAllButtons() { buttonState = 0; submitReport() }

        @JavascriptInterface
        fun pressHat(hat: Int) { hatState = hat.coerceIn(0, 8); submitReport() }

        @JavascriptInterface
        fun releaseHat() { hatState = 8; submitReport() }

        @JavascriptInterface
        fun moveLeftStick(lx: Int, ly: Int) {
            lxState = lx.coerceIn(0, 255); lyState = ly.coerceIn(0, 255); submitReport()
        }

        @JavascriptInterface
        fun moveRightStick(rx: Int, ry: Int) {
            rxState = rx.coerceIn(0, 255); ryState = ry.coerceIn(0, 255); submitReport()
        }

        @JavascriptInterface
        fun resetSticks() {
            lxState = 128; lyState = 128; rxState = 128; ryState = 128; submitReport()
        }

        @JavascriptInterface
        fun resetAll() {
            buttonState = 0; hatState = 8
            lxState = 128; lyState = 128; rxState = 128; ryState = 128; submitReport()
        }

        private fun submitReport() {
            if (!_connected.get()) return
            executor.submit {
                try {
                    when (protocol) {
                        PROTO_BINARY     -> sendBinaryReport()
                        PROTO_SYSBOTBASE -> sendSysBotBaseReport()
                    }
                } catch (e: Exception) {
                    _connected.set(false)
                    notifyJs(false, "送信エラー: ${e.message}")
                }
            }
        }

        private fun sendBinaryReport() {
            val r = ByteArray(8)
            r[0] = (buttonState        and 0xFF).toByte()
            r[1] = ((buttonState shr 8) and 0xFF).toByte()
            r[2] = hatState.toByte()
            r[3] = lxState.toByte(); r[4] = lyState.toByte()
            r[5] = rxState.toByte(); r[6] = ryState.toByte()
            r[7] = 0
            outputStream?.write(r); outputStream?.flush()
        }

        private fun sendSysBotBaseReport() {
            val sb = StringBuilder()
            sb.append("setStick LEFT ${toSysBotStick(lxState)} ${toSysBotStick(lyState)}\n")
            sb.append("setStick RIGHT ${toSysBotStick(rxState)} ${toSysBotStick(ryState)}\n")
            BUTTON_NAMES.forEach { (mask, name) ->
                if (buttonState and mask != 0) sb.append("press $name\n")
                else                           sb.append("release $name\n")
            }
            if (hatState < 8) {
                val dirMask = HAT_DIR_MASK[hatState]
                HAT_NAMES.forEachIndexed { i, name ->
                    if (dirMask and (1 shl i) != 0) sb.append("press $name\n")
                    else                            sb.append("release $name\n")
                }
            } else {
                HAT_NAMES.forEach { sb.append("release $it\n") }
            }
            sendText(sb.toString())
        }

        private fun sendNeutralState() {
            if (protocol == PROTO_BINARY) {
                val r = ByteArray(8)
                r[2] = 8; r[3] = 128.toByte(); r[4] = 128.toByte(); r[5] = 128.toByte(); r[6] = 128.toByte()
                outputStream?.write(r); outputStream?.flush()
            } else {
                sendText("setStick LEFT 0 0\nsetStick RIGHT 0 0\n")
            }
        }

        private fun sendText(text: String) {
            outputStream?.write(text.toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        }

        private fun toSysBotStick(v: Int) = ((v - 128) * 257).coerceIn(-32768, 32767)

        private fun notifyJs(connected: Boolean, message: String) {
            val json = """{"connected":$connected,"message":"${message.replace("\"","'")}"}"""
            val b64  = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js   = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    if(typeof receiveTcpStatus==='function')receiveTcpStatus(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // =========================================================
    // Pro Controller Bridge（新規）
    // 物理プロコン入力を受けて TcpBridge + JS 記録系へ中継する
    // =========================================================
    inner class ProConBridge {

        @Volatile private var passthrough = false

        // モーションイベントのスロットリング用（直前値）
        @Volatile private var lastLX  = 128
        @Volatile private var lastLY  = 128
        @Volatile private var lastRX  = 128
        @Volatile private var lastRY  = 128
        @Volatile private var lastHat = 8

        // ── JavaScript インターフェース ────────────────────────────────

        /** パススルーの ON / OFF 切替 */
        @JavascriptInterface
        fun setPassthrough(enabled: Boolean) {
            passthrough = enabled
            if (!enabled) {
                tcpBridge.resetAll()         // TCP: ニュートラルに戻す
                bluetoothBridge.resetAll()   // ← 追加: BT HID もニュートラルに戻す
            }
            notifyState()
        }

        @JavascriptInterface
        fun isPassthrough(): Boolean = passthrough

        /** 現在接続されているゲームパッドデバイス一覧を JSON 配列で返す */
        @JavascriptInterface
        fun getConnectedGamepads(): String {
            val list = InputDevice.getDeviceIds()
                .toList()
                .mapNotNull { InputDevice.getDevice(it) }
                .filter { d ->
                    val src = d.sources
                    (src and InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD ||
                    (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                }
                .map { "\"${it.name.replace("\"", "'")}\"" }
            return "[${list.joinToString(",")}]"
        }

        // ── Activity から呼ばれる内部メソッド ─────────────────────────

        /** ボタン press / release イベントを JS へ通知 */
        fun notifyButtonEvent(mask: Int, pressed: Boolean) {
            val name = SWITCH_BUTTON_NAMES[mask] ?: "UNKNOWN"
            notifyJs("""{"type":"button","mask":$mask,"key":"$name","pressed":$pressed}""")
        }

        /** スティック / Hat モーションイベントを処理してスロットリング付きで中継 */
        fun handleMotionEvent(event: MotionEvent) {
            val lx  = axisToStick(event.getAxisValue(MotionEvent.AXIS_X))    // Lスティック横 = AXIS_X（標準Android対応）
            val ly  = axisToStick(event.getAxisValue(MotionEvent.AXIS_Y))    // Lスティック縦 = AXIS_Y
            val rx  = axisToStick(event.getAxisValue(MotionEvent.AXIS_Z))    // Rスティック横 = AXIS_Z
            val ry  = axisToStick(event.getAxisValue(MotionEvent.AXIS_RZ))   // Rスティック縦 = AXIS_RZ
            val hat = hatAxisToHat(
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            )

            val DEADBAND = 3
            val stickMoved = Math.abs(lx - lastLX) > DEADBAND ||
                             Math.abs(ly - lastLY) > DEADBAND ||
                             Math.abs(rx - lastRX) > DEADBAND ||
                             Math.abs(ry - lastRY) > DEADBAND
            val hatMoved   = hat != lastHat

            if (stickMoved || hatMoved) {
                if (stickMoved) {
                    tcpBridge.moveLeftStick(lx, ly)
                    tcpBridge.moveRightStick(rx, ry)
                    bluetoothBridge.moveSticks(lx, ly, rx, ry)   // ← 追加: BT HID にも転送
                }
                if (hatMoved) {
                    if (hat < 8) {
                        tcpBridge.pressHat(hat)
                        bluetoothBridge.moveHat(hat)              // ← 追加: BT HID にも転送
                    } else {
                        tcpBridge.releaseHat()
                        bluetoothBridge.moveHat(8)                // ← 追加: BT HID にも転送 (NEUTRAL)
                    }
                }
                lastLX = lx; lastLY = ly
                lastRX = rx; lastRY = ry
                lastHat = hat
                notifyJs("""{"type":"motion","lx":$lx,"ly":$ly,"rx":$rx,"ry":$ry,"hat":$hat}""")
            }
        }

        // ── 内部ユーティリティ ────────────────────────────────────────

        private fun notifyState() {
            notifyJs("""{"type":"state","passthrough":$passthrough}""")
        }

        private fun notifyJs(json: String) {
            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js  = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    if(typeof receiveProConEvent==='function')receiveProConEvent(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }
}
