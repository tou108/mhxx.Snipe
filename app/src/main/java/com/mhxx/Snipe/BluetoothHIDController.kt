package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Nintendo Switch用 Bluetooth HID コントローラー
 *
 * 【旧バージョンからの根本的な変更点】
 *
 * ❌ 旧: 汎用 HID Gamepad ディスクリプタ
 * ✅ 新: Nintendo Switch が唯一受け付ける Nintendo 独自ディスクリプタ (JoyConDroid と同一)
 *
 * ❌ 旧: sendReport(device, reportId=0, 7バイト)
 * ✅ 新: sendReport(device, 0x30, 48バイト) / sendReport(device, 0x21, 48バイト)
 *
 * ❌ 旧: サブコマンドハンドラなし
 * ✅ 新: Switch が送ってくる Output Report (0x01) に含まれるサブコマンドすべてに応答
 *       (応答しないと Switch は入力を永久に無視する)
 *
 * ❌ 旧: ボタンデータが 7バイト汎用形式
 * ✅ 新: 3バイトボタン + 12bit アナログスティック×2 = 9バイト (これを 48バイトレポートに埋め込む)
 *
 * ❌ 旧: 接続後にレポートを送り続けない
 * ✅ 新: 接続直後に 0x3F Simple HID ハンドシェイク → 0x30 Full Report を 15ms ごとに送信
 */
@SuppressLint("MissingPermission")
class BluetoothHIDController(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHIDController"

        /**
         * Nintendo Pro Controller / Joy-Con 共通 HID ディスクリプタ
         * 出典: JoyConDroid (ControllerType.java) / dekuNukem Nintendo Switch Reverse Engineering
         *
         * Switch はこのバイト列以外を持つデバイスを「コントローラー」として認識しない。
         * 含まれる Report ID:
         *   0x21 = Subcommand Reply (Input, 48 bytes)
         *   0x30 = Standard Full Report (Input, 48 bytes)  ← メインレポート
         *   0x31 = NFC/IR Report (Input, 361 bytes)
         *   0x3F = Simple HID Report (Input, 11 bytes)    ← ハンドシェイク用
         *   0x01 = Rumble + Subcommand (Output, 48 bytes) ← Switch からのコマンド受信
         *   0x10 = Rumble Only (Output, 48 bytes)
         *   0x11 = NFC/IR MCU Data (Output, 48 bytes)
         */
        private const val DESCRIPTOR_HEX =
            "05010905a1010601ff852109217508953081028530093075089530810285310931750896690181028532" +
            "0932750896690181028533093375089669018102853f0509190129101500250175019510810205010939" +
            "1500250775049501814205097504950181010501093009310933093416000027ffff0000751095048102" +
            "0601ff850109017508953091028510091075089530910285110911750895309102851209127508953091" +
            "02c0"

        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
    }

    // ----- BT 基盤 -----
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    // コールバック + サブコマンド処理を同一スレッドで行う BT 専用 Executor
    private val hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ----- 接続状態 -----
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var deviceConnected = false
    @Volatile private var appRegistered = false   // @Volatile で hidExecutor 外からも安全に読める
    @Volatile private var serviceConnected = false

    // ----- リスナー -----
    var listener: ControllerListener? = null

    // ----- レポート制御 -----
    /** 各レポートに付与するタイマー値 (0–255 でインクリメント) */
    private val reportTimer = AtomicInteger(0)

    /** true になると定期 Full Report 送信を開始する */
    @Volatile private var isFullReportMode = false

    /**
     * 現在のボタン/スティック状態 (9バイト)
     *   [0] 右ボタン byte: Y=0x01, X=0x02, B=0x04, A=0x08, R=0x40, ZR=0x80
     *   [1] 中ボタン byte: MINUS=0x01, PLUS=0x02, R_STICK=0x04, L_STICK=0x08, HOME=0x10, CAPTURE=0x20
     *   [2] 左ボタン byte: DOWN=0x01, UP=0x02, RIGHT=0x04, LEFT=0x08, L=0x40, ZL=0x80
     *   [3–5] 左スティック: 12bit X (LE) + 12bit Y (LE), 中立 = 0x800
     *   [6–8] 右スティック: 同上
     */
    private val currentButtonState = AtomicReference(ByteArray(9).also { setStickCenter(it) })

    // ----- 定期レポートスケジューラー -----
    @Volatile private var reportScheduler: ScheduledExecutorService? = null

    // ============================================================
    // インターフェース
    // ============================================================
    interface ControllerListener {
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
        fun onError(message: String)
        fun onStateChanged(state: String)
    }

    init {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = mgr?.adapter
    }

    // ============================================================
    // 接続
    // ============================================================

    private fun normalizeMac(mac: String) = mac.trim().replace("-", ":").uppercase()
    private fun isValidMac(mac: String) = MAC_REGEX.matches(mac)

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED

    fun connectToSwitch(macAddress: String) {
        if (!hasPermission()) { listener?.onError("Bluetooth権限がありません"); return }
        val mac = normalizeMac(macAddress)
        if (!isValidMac(mac)) { listener?.onError("無効なMACアドレス: $macAddress"); return }
        val adapter = bluetoothAdapter ?: run { listener?.onError("Bluetoothがサポートされていません"); return }
        if (!adapter.isEnabled) { listener?.onError("Bluetoothが無効です"); return }
        val device = try { adapter.getRemoteDevice(mac) }
                     catch (e: Exception) { listener?.onError("デバイスエラー: ${e.message}"); return }
        listener?.onStateChanged("接続準備中...")
        hidExecutor.execute { connectToDevice(device) }
    }

    private fun getHidProxy(callback: (BluetoothHidDevice?) -> Unit) {
        if (serviceConnected && bluetoothHidDevice != null) { callback(bluetoothHidDevice); return }
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as? BluetoothHidDevice
                    serviceConnected = true
                    callback(bluetoothHidDevice)
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    serviceConnected = false; bluetoothHidDevice = null
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device
        getHidProxy { hid ->
            if (hid == null) { listener?.onError("HIDプロキシが利用できません"); return@getHidProxy }
            if (appRegistered) {
                try { hid.connect(device) }
                catch (e: Exception) { listener?.onError("接続エラー: ${e.message}") }
                return@getHidProxy
            }
            try {
                // ★ Nintendo 専用ディスクリプタ
                val descriptor = DESCRIPTOR_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                // ─────────────────────────────────────────────────────────────
                // Class of Device (CoD) の計算:
                //   Android は subclass 値から CoD を自動計算する
                //   SUBCLASS1_GAMEPAD = 0x08
                //   CoD = 0x002500 | (0x08 & 0xFC) = 0x002500 | 0x08 = 0x002508
                //     Major Device Class: Peripheral (0x0500)
                //     Minor Device Class: Gamepad    (0x0008)
                //   → Nintendo Switch が期待する CoD 0x002508 と一致
                // ─────────────────────────────────────────────────────────────
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Pro Controller",                    // Switch が認識するデバイス名 (完全一致が必要)
                    "Pro Controller",                    // 説明
                    "Nintendo Co., Ltd.",                // Switch が期待するメーカー名
                    BluetoothHidDevice.SUBCLASS1_GAMEPAD, // 0x08 → CoD = 0x002508 (Peripheral/Gamepad)
                    descriptor
                )
                val qos = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                    21720, 362, 21720, 16667, 16667
                )

                hid.registerApp(sdp, null, qos, hidExecutor, object : BluetoothHidDevice.Callback() {

                    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                        appRegistered = registered
                        if (registered) {
                            // 登録成功 → Switch への接続を開始
                            val target = targetDevice
                            if (target != null) {
                                try { hid.connect(target) }
                                catch (e: Exception) { listener?.onError("接続開始エラー: ${e.message}") }
                            }
                        } else {
                            // 登録解除 (disconnect() 後 or Switch 側からの強制解除)
                            Log.d(TAG, "App unregistered")
                            stopScheduler()
                        }
                    }

                    override fun onConnectionStateChanged(dev: BluetoothDevice, state: Int) {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                deviceConnected = true; connectedDevice = dev
                                listener?.onConnected(dev)
                                // 接続直後: Simple HID ハンドシェイク開始
                                startHandshake()
                            }
                            BluetoothProfile.STATE_CONNECTING  -> listener?.onStateChanged("接続中...")
                            BluetoothProfile.STATE_DISCONNECTING -> listener?.onStateChanged("切断中...")
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                deviceConnected = false; connectedDevice = null
                                stopScheduler()
                                listener?.onDisconnected()
                            }
                        }
                    }

                    /** Switch から GET_REPORT が来た場合 — 現在状態を返す */
                    override fun onGetReport(dev: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                        try { hid.replyReport(dev, type, id, buildFullReport()) }
                        catch (e: Exception) { Log.e(TAG, "replyReport error", e) }
                    }

                    override fun onSetReport(dev: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                        try { hid.reportError(dev, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
                        catch (e: Exception) { Log.e(TAG, "reportError error", e) }
                    }

                    /**
                     * ★ Switch からの Output Report を受信する
                     *   reportId=0x01: Rumble + Subcommand  ← サブコマンド処理が必要
                     *   reportId=0x10: Rumble only          ← 無視でよい
                     *   reportId=0x11: NFC/IR               ← 無視でよい
                     *
                     * このコールバックへの応答なしでは Switch は入力を受け付けない。
                     */
                    override fun onInterruptData(dev: BluetoothDevice, reportId: Byte, data: ByteArray) {
                        if (reportId == 0x01.toByte() && data.size >= 10) {
                            handleSubcommand(dev, data)
                        }
                    }
                })
            } catch (e: Exception) { listener?.onError("接続エラー: ${e.message}") }
        }
    }

    // ============================================================
    // サブコマンド処理 (Switch → Android)
    // ============================================================

    /**
     * Output Report 0x01 のペイロード構造:
     *   data[0]   = global packet counter
     *   data[1–8] = rumble data (L+R, 4 bytes each)
     *   data[9]   = subcommand ID
     *   data[10–] = subcommand arguments
     */
    private fun handleSubcommand(dev: BluetoothDevice, data: ByteArray) {
        val timer = data[0]
        val subCmd = data[9]
        Log.d(TAG, "SubCmd: 0x${(subCmd.toInt() and 0xFF).toString(16).uppercase()}")

        when (subCmd) {
            // 0x01: Bluetooth manual pairing
            0x01.toByte() -> ack(dev, timer, subCmd, 0x81.toByte())

            // 0x02: Request device info
            0x02.toByte() -> {
                val macStr = try { bluetoothAdapter?.address ?: "00:00:00:00:00:00" }
                             catch (_: Exception) { "00:00:00:00:00:00" }
                val macBytes = macStr.split(":").map { it.toInt(16).toByte() }
                val info = ByteArray(12).apply {
                    this[0] = 0x04; this[1] = 0x21          // firmware ver
                    this[2] = 0x03; this[3] = 0x02          // Pro Controller
                    for (i in 0..5) this[4 + i] = macBytes.getOrElse(5 - i) { 0 }
                    this[10] = 0x01; this[11] = 0x01        // SPI colors
                }
                subcmdReply(dev, timer, 0x82.toByte(), subCmd, info)
            }

            // 0x03: Set input report mode → Full Report Mode へ切り替え
            0x03.toByte() -> {
                ack(dev, timer, subCmd, 0x80.toByte())
                if (!isFullReportMode) switchToFullReportMode()
            }

            // 0x04: Trigger buttons elapsed time
            0x04.toByte() -> subcmdReply(dev, timer, 0x83.toByte(), subCmd, ByteArray(8))

            // 0x08: Set shipment mode
            0x08.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x10: SPI flash read → ゼロ埋めで応答
            0x10.toByte() -> {
                val len = if (data.size >= 15) (data[14].toInt() and 0xFF) else 0
                val reply = ByteArray(5 + len)
                if (data.size >= 14) {
                    reply[0]=data[10]; reply[1]=data[11]
                    reply[2]=data[12]; reply[3]=data[13]
                }
                if (data.size >= 15) reply[4] = data[14]
                subcmdReply(dev, timer, 0x90.toByte(), subCmd, reply)
            }

            // 0x11: SPI flash write
            0x11.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x21: Set NFC/IR MCU config
            0x21.toByte() -> subcmdReply(dev, timer, 0xA0.toByte(), subCmd, ByteArray(34))

            // 0x22: Set NFC/IR MCU state
            0x22.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x30: Set player lights
            0x30.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x38: Set HOME light
            0x38.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x40: Enable IMU (6-axis)
            0x40.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x41: Set IMU sensitivity
            0x41.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x42: Write IMU registers
            0x42.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x43: Read IMU registers
            0x43.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x48: Enable vibration
            0x48.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())

            // 0x50: Get regulated voltage
            0x50.toByte() -> subcmdReply(dev, timer, 0x80.toByte(), subCmd,
                byteArrayOf(0x08, 0x07, 0x00, 0x00)) // ~1800mV

            // その他: 汎用 ACK
            else -> ack(dev, timer, subCmd, 0x80.toByte())
        }
    }

    private fun ack(dev: BluetoothDevice, timer: Byte, subCmd: Byte, ack: Byte) {
        subcmdReply(dev, timer, ack, subCmd, ByteArray(0))
    }

    /**
     * Subcommand Reply (Report ID 0x21, 48 bytes) を送信する
     *
     * レイアウト:
     *   buf[0]     = timer
     *   buf[1]     = battery (0x8E = full + BT connected)
     *   buf[2–4]   = buttons (all 0)
     *   buf[5–7]   = left stick center (0x800)
     *   buf[8–10]  = right stick center (0x800)
     *   buf[11]    = vibrator (0xB0)
     *   buf[12]    = ACK byte
     *   buf[13]    = subcommand ID
     *   buf[14–47] = subcommand reply data (最大 34 bytes)
     */
    private fun subcmdReply(dev: BluetoothDevice, timer: Byte, ack: Byte, subCmd: Byte, extra: ByteArray) {
        val buf = ByteArray(48)
        buf[0]  = timer
        buf[1]  = 0x8E.toByte()
        setStickCenter(buf, offset = 5)   // left stick center at buf[5–7]
        setStickCenter(buf, offset = 8)   // right stick center at buf[8–10]
        buf[11] = 0xB0.toByte()
        buf[12] = ack
        buf[13] = subCmd
        extra.copyInto(buf, 14, 0, minOf(extra.size, 34))
        rawSend(dev, 0x21, buf)
    }

    // ============================================================
    // レポート送信 (Android → Switch)
    // ============================================================

    private fun rawSend(dev: BluetoothDevice, reportId: Int, data: ByteArray) {
        try {
            bluetoothHidDevice?.sendReport(dev, reportId, data)
        } catch (e: Exception) {
            Log.e(TAG, "sendReport(0x${reportId.toString(16)}) error: $e")
        }
    }

    /**
     * Standard Full Report (0x30, 48 bytes) を currentButtonState から生成する
     *
     * レイアウト:
     *   buf[0]     = timer (インクリメント)
     *   buf[1]     = battery/connection (0x8E)
     *   buf[2–4]   = buttons (3 bytes, Nintendo Pro Controller ビット配置)
     *   buf[5–7]   = left stick  (12bit X + 12bit Y packed)
     *   buf[8–10]  = right stick (12bit X + 12bit Y packed)
     *   buf[11]    = vibrator input report (0xB0)
     *   buf[12–47] = IMU data (すべて 0)
     */
    private fun buildFullReport(): ByteArray {
        val buf = ByteArray(48)
        buf[0]  = (reportTimer.incrementAndGet() and 0xFF).toByte()
        buf[1]  = 0x8E.toByte()
        val state = currentButtonState.get()
        state.copyInto(buf, destinationOffset = 2, startIndex = 0, endIndex = 9)
        buf[11] = 0xB0.toByte()
        return buf
    }

    /**
     * ボタン/スティック状態を Switch に送信する (public API)
     *
     * @param buttonData 9バイト配列 (buildHidReport() の戻り値を渡す)
     *   [0] 右ボタン  [1] 中ボタン  [2] 左ボタン
     *   [3–5] 左スティック 12bit   [6–8] 右スティック 12bit
     */
    fun sendControllerInput(buttonData: ByteArray) {
        val dev = connectedDevice ?: run { Log.w(TAG, "未接続"); return }
        if (buttonData.size >= 9) currentButtonState.set(buttonData.copyOf(9))
        rawSend(dev, 0x30, buildFullReport())
    }

    /**
     * 指定ミリ秒後にリリースレポートを送信する
     * hidExecutor で実行することで press → sleep → release の順序を保証する
     */
    fun scheduleRelease(releaseReport: ByteArray, delayMs: Long) {
        hidExecutor.execute {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
            sendControllerInput(releaseReport)
        }
    }

    // ============================================================
    // ハンドシェイク & 定期レポート
    // ============================================================

    /**
     * 接続直後に Simple HID (0x3F) レポートを送り続け Switch を "目覚め" させる。
     * Switch が 0x03 (set input report mode) を送ってきたら isFullReportMode=true になり終了。
     *
     * hidExecutor と別スレッドで実行することで、onInterruptData コールバックの処理を
     * ブロックしないようにする。
     */
    private fun startHandshake() {
        Thread({
            var count = 0
            while (!isFullReportMode && deviceConnected && count < 100) {
                val dev = connectedDevice ?: break
                // Simple HID レポート (11 bytes):
                //   buf[0–1] = buttons (0)
                //   buf[2]   = hat switch neutral (0x08)
                //   buf[3–10]= sticks at center (0x8000 LE per axis)
                val buf = ByteArray(11).apply {
                    this[2] = 0x08
                    this[4] = 0x80.toByte(); this[6] = 0x80.toByte()
                    this[8] = 0x80.toByte(); this[10] = 0x80.toByte()
                }
                rawSend(dev, 0x3F, buf)
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                count++
            }
        }, "BT-Handshake").also { it.isDaemon = true; it.start() }
    }

    /** 0x30 Full Report を 15ms (≒66Hz) ごとに送信開始する */
    private fun switchToFullReportMode() {
        isFullReportMode = true
        Log.d(TAG, "Full Report Mode 開始 (15ms/report)")
        val sched = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "BT-ReportScheduler").also { it.isDaemon = true }
        }
        reportScheduler = sched
        sched.scheduleAtFixedRate({
            val dev = connectedDevice ?: return@scheduleAtFixedRate
            rawSend(dev, 0x30, buildFullReport())
        }, 0L, 15L, TimeUnit.MILLISECONDS)
    }

    private fun stopScheduler() {
        isFullReportMode = false
        reportScheduler?.shutdown()
        reportScheduler = null
    }

    // ============================================================
    // 切断 & クリーンアップ
    // ============================================================

    fun disconnect() {
        targetDevice = null
        stopScheduler()
        // appRegistered を先にリセットして、切断中に connectToSwitch が呼ばれても
        // registerApp を重複して呼ばないようにする
        appRegistered = false
        hidExecutor.execute {
            try {
                connectedDevice?.let { bluetoothHidDevice?.disconnect(it) }
                bluetoothHidDevice?.unregisterApp()
            } catch (e: Exception) { Log.e(TAG, "disconnect error", e) }
            deviceConnected = false; connectedDevice = null
            listener?.onDisconnected()
        }
    }

    fun cleanup() {
        disconnect()
        hidExecutor.shutdown()
    }

    // ============================================================
    // ユーティリティ
    // ============================================================

    /**
     * 12bit スティックの中立値 (0x800 = 2048) をバイト配列に書き込む
     * 12bit X + 12bit Y を 3バイトにパック:
     *   byte[offset+0] = X[7:0]
     *   byte[offset+1] = X[11:8] | (Y[3:0] << 4)
     *   byte[offset+2] = Y[11:4]
     * X=2048=0x800, Y=2048=0x800 → 0x00, 0x08, 0x80
     */
    private fun setStickCenter(buf: ByteArray, offset: Int = 3) {
        buf[offset]     = 0x00
        buf[offset + 1] = 0x08
        buf[offset + 2] = 0x80.toByte()
    }
}
