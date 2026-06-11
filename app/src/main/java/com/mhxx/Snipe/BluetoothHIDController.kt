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
 * Nintendo Switch Pro Controller エミュレーター (Bluetooth HID)
 *
 * ■ 接続方式
 *   A) アクティブ: connectToSwitch(mac)   — AndroidがSwitchのMACに能動接続
 *   B) パッシブ:   registerAsController() — Switchからの接続を待つ (発見可能モード必須)
 *
 * ■ 接続フロー
 *   1. changeDeviceName()       → BT名を "Pro Controller" に変更 ★接続失敗の主因はここ
 *   2. registerHidApp()         → Nintendo専用ディスクリプタでHID登録
 *   3-A: hid.connect(switch)   または  3-B: Switch → Android を発見して接続
 *   4. onConnected → startHandshake() → Simple HID (0x3F) 送信ループ
 *   5. Switch → subcommand 0x03 → switchToFullReportMode()
 *   6. 15ms 間隔で Full Report (0x30) を送り続ける
 *
 * ■ v3 修正点
 *   ✅ BTデバイス名を "Pro Controller" に設定 (接続失敗の主原因を修正)
 *   ✅ パッシブ接続モード (registerAsController) 追加
 *   ✅ 再接続リトライ (最大3回, 指数バックオフ)
 *   ✅ 右スティック中立値バグ修正 (初期値が 0,0,0 になっていた)
 *   ✅ ハンドシェイクタイムアウト後のフルレポートモード強制移行
 *   ✅ hid プロキシを Callback クロージャで直接保持 (null参照リスク解消)
 *   ✅ bluetoothHidDevice を @Volatile に
 */
@SuppressLint("MissingPermission")
class BluetoothHIDController(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHIDController"

        /** Switch が Pro Controller として認識するデバイス名。完全一致が必須。 */
        const val DEVICE_NAME = "Pro Controller"

        private const val MAX_RETRY      = 3
        private const val RETRY_BASE_MS  = 1500L   // リトライ基底時間 (指数バックオフ)

        /**
         * Nintendo Switch Pro Controller / Joy-Con 共通 HID ディスクリプタ
         * 出典: JoyConDroid / dekuNukem Nintendo_Switch_Reverse_Engineering
         *
         * Report ID 一覧:
         *   Input  0x21 = Subcommand Reply       (48 bytes)
         *   Input  0x30 = Standard Full Report   (48 bytes) ← メインレポート
         *   Input  0x31 = NFC/IR Report         (361 bytes)
         *   Input  0x3F = Simple HID Report      (11 bytes) ← ハンドシェイク
         *   Output 0x01 = Rumble + Subcommand    (48 bytes) ← Switch→Androidコマンド
         *   Output 0x10 = Rumble Only            (48 bytes)
         *   Output 0x11 = NFC/IR MCU Data        (48 bytes)
         *   Output 0x12 = Vendor                 (48 bytes)
         */
        private const val DESCRIPTOR_HEX =
            "05010905a1010601ff852109217508953081028530093075089530810285310931750896690181028532" +
            "0932750896690181028533093375089669018102853f0509190129101500250175019510810205010939" +
            "1500250775049501814205097504950181010501093009310933093416000027ffff0000751095048102" +
            "0601ff850109017508953091028510091075089530910285110911750895309102851209127508953091" +
            "02c0"

        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
    }

    // ──────────────────────────────────────────────────────────────
    // BT 基盤
    // ──────────────────────────────────────────────────────────────
    private var bluetoothAdapter: BluetoothAdapter? = null

    /** getProfileProxy で得られる HID プロキシ。サービス切断時に null になる。 */
    @Volatile private var bluetoothHidDevice: BluetoothHidDevice? = null

    /**
     * HID コールバック・サブコマンド処理を担うシングルスレッド Executor。
     * registerApp() にこの Executor を渡すことで全コールバックはここで直列実行される。
     */
    private val hidExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BT-HID-Main").also { it.isDaemon = true }
    }

    // ──────────────────────────────────────────────────────────────
    // 接続状態
    // ──────────────────────────────────────────────────────────────
    @Volatile private var connectedDevice:  BluetoothDevice? = null
    @Volatile private var targetDevice:     BluetoothDevice? = null
    @Volatile private var deviceConnected   = false
    @Volatile private var appRegistered     = false
    @Volatile private var serviceConnected  = false
    @Volatile private var isPassiveMode     = false
    @Volatile private var retryCount        = 0

    // ──────────────────────────────────────────────────────────────
    // デバイス名管理
    // ──────────────────────────────────────────────────────────────
    @Volatile private var originalDeviceName: String? = null

    // ──────────────────────────────────────────────────────────────
    // リスナー
    // ──────────────────────────────────────────────────────────────
    var listener: ControllerListener? = null

    // ──────────────────────────────────────────────────────────────
    // レポート制御
    // ──────────────────────────────────────────────────────────────
    private val reportTimer = AtomicInteger(0)

    @Volatile private var isFullReportMode = false
    @Volatile private var reportScheduler: ScheduledExecutorService? = null

    /**
     * 現在のボタン/スティック状態 (9 バイト)
     *   [0] 右ボタン : Y=0x01 X=0x02 B=0x04 A=0x08 R=0x40 ZR=0x80
     *   [1] 中ボタン : MINUS=0x01 PLUS=0x02 RS=0x04 LS=0x08 HOME=0x10 CAP=0x20
     *   [2] 左ボタン : DOWN=0x01 UP=0x02 RIGHT=0x04 LEFT=0x08 L=0x40 ZL=0x80
     *   [3-5] 左スティック 12bit LE (中立 = 0x800 = 2048)
     *   [6-8] 右スティック 12bit LE (中立 = 0x800 = 2048)
     */
    private val currentButtonState = AtomicReference(ByteArray(9).also { buf ->
        setStickCenter(buf, 3)   // 左スティック中立
        setStickCenter(buf, 6)   // 右スティック中立 ← 旧コードはここが欠けていた
    })

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
    // パブリック API
    // ============================================================

    /**
     * アクティブ接続: Switch の MAC アドレスを指定して接続を試みる。
     * Switch 側は「持ち方・順番を変える」画面を開いておくこと。
     */
    fun connectToSwitch(macAddress: String) {
        if (!hasPermission())    { listener?.onError("Bluetooth権限がありません"); return }
        val mac = normalizeMac(macAddress)
        if (!isValidMac(mac))    { listener?.onError("無効なMACアドレス: $macAddress"); return }
        val adapter = bluetoothAdapter
            ?: run { listener?.onError("Bluetoothがサポートされていません"); return }
        if (!adapter.isEnabled)  { listener?.onError("Bluetoothが無効です"); return }
        val device = try { adapter.getRemoteDevice(mac) }
                     catch (e: Exception) { listener?.onError("デバイスエラー: ${e.message}"); return }

        isPassiveMode = false
        retryCount    = 0
        listener?.onStateChanged("接続準備中...")
        hidExecutor.execute { setupAndConnect(device) }
    }

    /**
     * パッシブ接続: HID アプリを登録し Switch からの接続を待つ。
     * 呼び出し前に MainActivity から ACTION_REQUEST_DISCOVERABLE を発行すること。
     */
    fun registerAsController() {
        if (!hasPermission())   { listener?.onError("Bluetooth権限がありません"); return }
        val adapter = bluetoothAdapter
            ?: run { listener?.onError("Bluetoothがサポートされていません"); return }
        if (!adapter.isEnabled) { listener?.onError("Bluetoothが無効です"); return }

        isPassiveMode = true
        retryCount    = 0
        listener?.onStateChanged("Switch接続待機中...")
        hidExecutor.execute { setupAndConnect(null) }
    }

    fun disconnect() {
        isPassiveMode = false
        targetDevice  = null
        retryCount    = 0
        stopScheduler()
        hidExecutor.execute {
            try {
                connectedDevice?.let { bluetoothHidDevice?.disconnect(it) }
                bluetoothHidDevice?.unregisterApp()
            } catch (e: Exception) { Log.e(TAG, "disconnect error", e) }
            deviceConnected = false
            connectedDevice = null
            appRegistered   = false
            restoreDeviceName()
            restoreDeviceClass()   // ← CoD を元の値に戻す
            listener?.onDisconnected()
        }
    }

    fun cleanup() {
        disconnect()
        hidExecutor.shutdownNow()
    }

    val isConnected: Boolean get() = deviceConnected

    // ============================================================
    // デバイス名管理
    // ============================================================

    /** BT デバイス名を "Pro Controller" に変更し、元の名前を保存する。 */
    private fun changeDeviceName() {
        try {
            val adapter = bluetoothAdapter ?: return
            if (originalDeviceName == null) originalDeviceName = adapter.name
            if (adapter.name != DEVICE_NAME) {
                adapter.name = DEVICE_NAME
                Log.i(TAG, "BT name: '${originalDeviceName}' → '$DEVICE_NAME'")
                // name変更がBTスタックに伝播するまで少し待つ
                Thread.sleep(300)
            }
        } catch (e: Exception) {
            Log.w(TAG, "changeDeviceName failed: ${e.message}")
        }
    }

    /** BT デバイス名を元に戻す。 */
    private fun restoreDeviceName() {
        val orig = originalDeviceName ?: return
        try {
            val adapter = bluetoothAdapter ?: return
            if (orig.isNotBlank() && adapter.name != orig) {
                adapter.name = orig
                Log.i(TAG, "BT name restored: '$DEVICE_NAME' → '$orig'")
            }
            originalDeviceName = null
        } catch (e: Exception) {
            Log.w(TAG, "restoreDeviceName failed: ${e.message}")
        }
    }

    // ============================================================
    // Device Class (CoD) 管理
    // ============================================================

    /**
     * Switch が Pro Controller として認識する CoD
     * 0x002508 = Peripheral (Major=0x05) / Joystick (Minor bits)
     * Bluetooth++ で設定した値と同じ
     */
    private val TARGET_COD = 0x002508

    /** 接続前に保存する元の CoD。-1 は未保存を意味する。 */
    @Volatile private var originalDeviceClass: Int = -1

    /**
     * Bluetooth Device Class を Switch 互換値 (0x002508) に変更する。
     * WRITE_SECURE_SETTINGS が付与されていない場合は警告ログのみ出して続行する。
     */
    private fun changeDeviceClass() {
        try {
            val cr = context.contentResolver
            val current = android.provider.Settings.Secure.getInt(
                cr, "bluetooth_device_class", 0)
            if (originalDeviceClass == -1) originalDeviceClass = current
            if (current != TARGET_COD) {
                android.provider.Settings.Secure.putInt(
                    cr, "bluetooth_device_class", TARGET_COD)
                Log.i(TAG, "CoD: 0x%06X → 0x%06X".format(current, TARGET_COD))
                Thread.sleep(200)   // BTスタックへの伝播待ち
            } else {
                Log.d(TAG, "CoD already 0x%06X — skip".format(TARGET_COD))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "CoD変更スキップ: WRITE_SECURE_SETTINGS未付与 — " +
                "adb shell pm grant com.mhxx.snipe android.permission.WRITE_SECURE_SETTINGS を実行してください")
        } catch (e: Exception) {
            Log.w(TAG, "changeDeviceClass failed: ${e.message}")
        }
    }

    /** 切断時に元の CoD を復元する。 */
    private fun restoreDeviceClass() {
        if (originalDeviceClass == -1) return
        try {
            android.provider.Settings.Secure.putInt(
                context.contentResolver, "bluetooth_device_class", originalDeviceClass)
            Log.i(TAG, "CoD restored: 0x%06X".format(originalDeviceClass))
            originalDeviceClass = -1
        } catch (e: Exception) {
            Log.w(TAG, "restoreDeviceClass failed: ${e.message}")
        }
    }

    // ============================================================
    // 接続フロー
    // ============================================================

    private fun setupAndConnect(device: BluetoothDevice?) {
        targetDevice = device
        changeDeviceName()
        changeDeviceClass()   // ← CoD を Switch 互換値に変更
        getHidProxy { hid ->
            if (hid == null) {
                listener?.onError("HIDサービスに接続できません")
                return@getHidProxy
            }
            if (appRegistered) {
                if (!isPassiveMode && device != null) doConnect(hid, device)
                return@getHidProxy
            }
            registerHidApp(hid, device)
        }
    }

    private fun doConnect(hid: BluetoothHidDevice, device: BluetoothDevice) {
        try {
            val n = retryCount + 1
            listener?.onStateChanged("接続中...($n/$MAX_RETRY)")
            hid.connect(device)
            Log.d(TAG, "hid.connect() → ${device.address} (attempt $n)")
        } catch (e: Exception) {
            Log.e(TAG, "doConnect error: ${e.message}")
            listener?.onError("接続エラー: ${e.message}")
        }
    }

    private fun getHidProxy(callback: (BluetoothHidDevice?) -> Unit) {
        if (serviceConnected && bluetoothHidDevice != null) {
            callback(bluetoothHidDevice)
            return
        }
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as? BluetoothHidDevice
                    serviceConnected   = true
                    callback(bluetoothHidDevice)
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    serviceConnected   = false
                    bluetoothHidDevice = null
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp(hid: BluetoothHidDevice, initialDevice: BluetoothDevice?) {
        try {
            val descriptor = DESCRIPTOR_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val sdp = BluetoothHidDeviceAppSdpSettings(
                DEVICE_NAME,
                DEVICE_NAME,
                "Nintendo Co., Ltd.",
                0x08,          // SUBCLASS1_GAMEPAD
                descriptor
            )
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                21720, 362, 21720, 16667, 16667
            )
            hid.registerApp(sdp, null, qos, hidExecutor, buildCallback(hid))
            Log.d(TAG, "registerApp called (device=${initialDevice?.address ?: "passive"})")
        } catch (e: Exception) {
            Log.e(TAG, "registerApp error: ${e.message}")
            listener?.onError("HID登録エラー: ${e.message}")
        }
    }

    // ============================================================
    // HID Callback
    // ============================================================

    /**
     * BluetoothHidDevice.Callback を生成する。
     * [hid] を直接クロージャで保持することで、フィールド参照時の null 安全性を確保する。
     */
    private fun buildCallback(hid: BluetoothHidDevice) = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            Log.d(TAG, "onAppStatusChanged: registered=$registered")
            when {
                registered && !isPassiveMode && targetDevice != null ->
                    doConnect(hid, targetDevice!!)
                registered && isPassiveMode ->
                    listener?.onStateChanged("\"$DEVICE_NAME\" 登録完了 — Switchから接続してください")
                !registered ->
                    Log.w(TAG, "HID app unregistered")
            }
        }

        override fun onConnectionStateChanged(dev: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: ${dev.address} state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    deviceConnected = true
                    connectedDevice = dev
                    retryCount      = 0
                    listener?.onConnected(dev)
                    startHandshake()
                }
                BluetoothProfile.STATE_CONNECTING    -> listener?.onStateChanged("接続中...")
                BluetoothProfile.STATE_DISCONNECTING -> listener?.onStateChanged("切断中...")
                BluetoothProfile.STATE_DISCONNECTED  -> {
                    deviceConnected = false
                    connectedDevice = null
                    stopScheduler()

                    val target = targetDevice
                    if (!isPassiveMode && target != null && retryCount < MAX_RETRY) {
                        // hidExecutor をブロックしないよう別スレッドでバックオフ sleep
                        retryCount++
                        val delay = RETRY_BASE_MS * retryCount
                        listener?.onStateChanged("再接続待機中 ($retryCount/$MAX_RETRY) ${delay}ms後...")
                        Thread {
                            try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                            if (targetDevice != null && !deviceConnected) {
                                hidExecutor.execute { doConnect(hid, target) }
                            }
                        }.also { it.isDaemon = true; it.start() }
                    } else {
                        val exhausted = !isPassiveMode && retryCount >= MAX_RETRY
                        if (exhausted) listener?.onError("接続失敗 (${MAX_RETRY}回試行)")
                        retryCount   = 0
                        targetDevice = null
                        listener?.onDisconnected()
                    }
                }
            }
        }

        override fun onGetReport(dev: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            try { hid.replyReport(dev, type, id, buildFullReport()) }
            catch (e: Exception) { Log.e(TAG, "replyReport error: ${e.message}") }
        }

        override fun onSetReport(dev: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            try { hid.reportError(dev, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
            catch (e: Exception) { Log.e(TAG, "reportError error: ${e.message}") }
        }

        /** Switch からの Output Report 受信 (主にサブコマンド処理) */
        override fun onInterruptData(dev: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // data は reportId を除いたペイロード
            // Output Report 0x01 レイアウト:
            //   data[0]  = global counter
            //   data[1-8]= rumble data
            //   data[9]  = subcommand ID
            //   data[10+]= subcommand args
            if (reportId == 0x01.toByte() && data.size >= 10) {
                handleSubcommand(dev, hid, data)
            }
        }
    }

    // ============================================================
    // サブコマンド処理 (Switch → Android)
    // ============================================================

    private fun handleSubcommand(dev: BluetoothDevice, hid: BluetoothHidDevice, data: ByteArray) {
        val timer  = data[0]
        val subCmd = data[9]
        Log.d(TAG, "SubCmd 0x${(subCmd.toInt() and 0xFF).toString(16).padStart(2,'0').uppercase()}")

        when (subCmd) {
            // 0x01: BT Manual Pairing
            0x01.toByte() -> ack(dev, hid, timer, subCmd, 0x81.toByte())

            // 0x02: Request Device Info → ファームウェア/MACを返す
            0x02.toByte() -> {
                val macStr   = try { bluetoothAdapter?.address ?: "00:00:00:00:00:00" }
                               catch (_: Exception) { "00:00:00:00:00:00" }
                val macBytes = macStr.split(":").map { it.toInt(16).toByte() }
                val info = ByteArray(12).apply {
                    this[0] = 0x04; this[1] = 0x21    // firmware ver 4.33
                    this[2] = 0x03; this[3] = 0x02    // device type: Pro Controller
                    for (i in 0..5) this[4 + i] = macBytes.getOrElse(5 - i) { 0 }
                    this[10] = 0x01; this[11] = 0x01  // SPI color flags
                }
                subcmdReply(dev, hid, timer, 0x82.toByte(), subCmd, info)
            }

            // 0x03: Set Input Report Mode → Full Report Mode へ移行
            0x03.toByte() -> {
                ack(dev, hid, timer, subCmd, 0x80.toByte())
                if (!isFullReportMode) switchToFullReportMode()
            }

            // 0x04: Trigger Buttons Elapsed Time
            0x04.toByte() -> subcmdReply(dev, hid, timer, 0x83.toByte(), subCmd, ByteArray(8))

            // 0x08: Set Shipment Low Power State
            0x08.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x10: SPI Flash Read → ゼロ埋め応答
            0x10.toByte() -> {
                val len   = if (data.size >= 15) (data[14].toInt() and 0xFF) else 0
                val reply = ByteArray(5 + len)
                if (data.size >= 14) {
                    reply[0] = data[10]; reply[1] = data[11]
                    reply[2] = data[12]; reply[3] = data[13]
                }
                if (data.size >= 15) reply[4] = data[14]
                subcmdReply(dev, hid, timer, 0x90.toByte(), subCmd, reply)
            }

            // 0x11: SPI Flash Write
            0x11.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x21: Set NFC/IR MCU Config
            0x21.toByte() -> subcmdReply(dev, hid, timer, 0xA0.toByte(), subCmd, ByteArray(34))

            // 0x22: Set NFC/IR MCU State
            0x22.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x30: Set Player Lights
            0x30.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x38: Set HOME Light
            0x38.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x40: Enable IMU (6-axis)
            0x40.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x41: Set IMU Sensitivity
            0x41.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x42: Write IMU Registers
            0x42.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x43: Read IMU Registers
            0x43.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x48: Enable Vibration
            0x48.toByte() -> ack(dev, hid, timer, subCmd, 0x80.toByte())

            // 0x50: Get Regulated Voltage (~1800mV)
            0x50.toByte() -> subcmdReply(dev, hid, timer, 0x80.toByte(), subCmd,
                byteArrayOf(0x08, 0x07, 0x00, 0x00))

            // その他: 汎用 ACK
            else -> ack(dev, hid, timer, subCmd, 0x80.toByte())
        }
    }

    private fun ack(dev: BluetoothDevice, hid: BluetoothHidDevice,
                    timer: Byte, subCmd: Byte, ackByte: Byte) {
        subcmdReply(dev, hid, timer, ackByte, subCmd, ByteArray(0))
    }

    /**
     * Subcommand Reply (Report ID 0x21, 48 bytes) を送信する。
     *
     * buf レイアウト (reportId 0x21 を除いた 48 bytes):
     *   [0]    = timer
     *   [1]    = battery/connection (0x8E = full + BT connected)
     *   [2-4]  = buttons (all 0)
     *   [5-7]  = left stick center
     *   [8-10] = right stick center
     *   [11]   = vibrator (0xB0)
     *   [12]   = ACK byte
     *   [13]   = subcommand ID
     *   [14-47]= reply data (最大 34 bytes)
     */
    private fun subcmdReply(dev: BluetoothDevice, hid: BluetoothHidDevice,
                            timer: Byte, ack: Byte, subCmd: Byte, extra: ByteArray) {
        val buf = ByteArray(48)
        buf[0]  = timer
        buf[1]  = 0x8E.toByte()
        setStickCenter(buf, 5)
        setStickCenter(buf, 8)
        buf[11] = 0xB0.toByte()
        buf[12] = ack
        buf[13] = subCmd
        extra.copyInto(buf, 14, 0, minOf(extra.size, 34))
        try {
            hid.sendReport(dev, 0x21, buf)
        } catch (e: Exception) {
            Log.e(TAG, "subcmdReply error: ${e.message}")
        }
    }

    // ============================================================
    // レポート送信 (Android → Switch)
    // ============================================================

    private fun buildFullReport(): ByteArray {
        val buf = ByteArray(48)
        buf[0]  = (reportTimer.incrementAndGet() and 0xFF).toByte()
        buf[1]  = 0x8E.toByte()
        currentButtonState.get().copyInto(buf, destinationOffset = 2, startIndex = 0, endIndex = 9)
        buf[11] = 0xB0.toByte()
        return buf
    }

    /**
     * ボタン/スティック状態を Switch に送信する (public API)
     * @param buttonData 9 バイト配列 (MainActivity.buildHidReport() の戻り値)
     */
    fun sendControllerInput(buttonData: ByteArray) {
        val dev = connectedDevice ?: run { Log.w(TAG, "sendControllerInput: not connected"); return }
        if (buttonData.size >= 9) currentButtonState.set(buttonData.copyOf(9))
        try {
            bluetoothHidDevice?.sendReport(dev, 0x30, buildFullReport())
        } catch (e: Exception) {
            Log.e(TAG, "sendControllerInput error: $e")
        }
    }

    /**
     * press → sleep(delayMs) → release をシリアルに実行する。
     * hidExecutor で直列実行するので press/release の順序が保証される。
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
     * 接続直後に Simple HID (0x3F) を 100ms おきに送り Switch を「目覚め」させる。
     * Switch が subcommand 0x03 を送ってきたら isFullReportMode=true になり終了。
     * 100 回 (10 秒) 経っても移行しない場合は強制的に Full Report Mode へ。
     */
    private fun startHandshake() {
        Thread({
            Log.d(TAG, "Handshake started")
            var count = 0
            while (!isFullReportMode && deviceConnected && count < 100) {
                val dev = connectedDevice ?: break
                // Simple HID レポート (11 bytes):
                //   [0-1]  buttons (0)
                //   [2]    hat neutral (0x08)
                //   [3-10] 4 × 16bit sticks at center (high byte = 0x80)
                val buf = ByteArray(11).apply {
                    this[2]  = 0x08
                    this[4]  = 0x80.toByte()
                    this[6]  = 0x80.toByte()
                    this[8]  = 0x80.toByte()
                    this[10] = 0x80.toByte()
                }
                try {
                    bluetoothHidDevice?.sendReport(dev, 0x3F, buf)
                } catch (e: Exception) {
                    Log.e(TAG, "handshake sendReport error: ${e.message}")
                    break
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                count++
            }
            if (deviceConnected && !isFullReportMode) {
                Log.w(TAG, "Handshake timeout after $count attempts — forcing Full Report Mode")
                switchToFullReportMode()
            } else {
                Log.d(TAG, "Handshake complete after $count attempts")
            }
        }, "BT-Handshake").also { it.isDaemon = true; it.start() }
    }

    /** 0x30 Full Report を 15ms (≈66Hz) おきに送信開始する */
    private fun switchToFullReportMode() {
        isFullReportMode = true
        Log.d(TAG, "Full Report Mode started (15ms/report)")
        val sched = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "BT-ReportScheduler").also { it.isDaemon = true }
        }
        reportScheduler = sched
        sched.scheduleAtFixedRate({
            val dev = connectedDevice ?: return@scheduleAtFixedRate
            try {
                bluetoothHidDevice?.sendReport(dev, 0x30, buildFullReport())
            } catch (e: Exception) {
                Log.e(TAG, "scheduled report error: ${e.message}")
            }
        }, 0L, 15L, TimeUnit.MILLISECONDS)
    }

    private fun stopScheduler() {
        isFullReportMode = false
        reportScheduler?.shutdownNow()
        reportScheduler = null
    }

    // ============================================================
    // ユーティリティ
    // ============================================================

    private fun normalizeMac(mac: String) = mac.trim().replace("-", ":").uppercase()
    private fun isValidMac(mac: String)   = MAC_REGEX.matches(mac)

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED

    /**
     * 12bit スティック中立値 (X=Y=0x800=2048) を 3 バイトにパック。
     *   byte[offset+0] = 0x00  (X[7:0])
     *   byte[offset+1] = 0x08  (X[11:8]=8 | Y[3:0]<<4=0 → 0x08)
     *   byte[offset+2] = 0x80  (Y[11:4]=0x80)
     */
    private fun setStickCenter(buf: ByteArray, offset: Int) {
        buf[offset]     = 0x00
        buf[offset + 1] = 0x08
        buf[offset + 2] = 0x80.toByte()
    }
}
