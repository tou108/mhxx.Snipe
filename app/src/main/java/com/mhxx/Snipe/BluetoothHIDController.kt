package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.lang.reflect.Method
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Nintendo Switch用 Bluetooth HID コントローラー
 *
 * 【JoyConDroid 方式に変更】
 *
 * ❌ 旧: Switch の MAC アドレスを手動入力して接続 (Android → Switch)
 * ✅ 新: Android を "Joy-Con (R)" として検出可能にし、Switch 側からペアリング
 *         (Switch → Android)
 *
 * 接続手順:
 *   1. startDiscovery() を呼ぶ → Android の BT 名を "Joy-Con (R)" に変更し、
 *      discoverable モードを有効にする (60秒間)
 *   2. Switch 側で「コントローラーの持ちかた/順番を変える」画面を開く (自動スキャン開始)
 *   3. Switch が Android を発見してペアリング → 自動接続
 *   4. 切断後は Switch のペアリング情報を削除 → 次回もクリーンにペアリングできる
 */
@SuppressLint("MissingPermission")
class BluetoothHIDController(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHIDController"
        private const val NINTENDO_SWITCH = "Nintendo Switch"

        /** Switch が受け付ける BT デバイス名 */
        private const val CONTROLLER_BT_NAME = "Joy-Con (R)"

        /**
         * Nintendo Pro Controller HID ディスクリプタ
         * 出典: JoyConDroid (ControllerType.java) / dekuNukem Nintendo Switch Reverse Engineering
         */
        private const val DESCRIPTOR_HEX =
            "05010905a1010601ff852109217508953081028530093075089530810285310931750896690181028532" +
            "0932750896690181028533093375089669018102853f0509190129101500250175019510810205010939" +
            "1500250775049501814205097504950181010501093009310933093416000027ffff0000751095048102" +
            "0601ff850109017508953091028510091075089530910285110911750895309102851209127508953091" +
            "02c0"

        // QoS 設定 (JoyConDroid と同値)
        private const val QOS_TOKEN_RATE       = 21720
        private const val QOS_TOKEN_BUCKET     = 362
        private const val QOS_PEAK_BANDWIDTH   = 21720
        private const val QOS_LATENCY          = 16667
        private const val QOS_DELAY_VARIATION  = 16667
    }

    // ----- BT 基盤 -----
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private val hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ----- 接続状態 -----
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var deviceConnected = false
    private var appRegistered = false
    private var serviceConnected = false

    /** 接続前の元の BT 名 (切断・クリーンアップ時に復元) */
    private var originalBtName: String? = null

    /**
     * 接続前の元の Bluetooth デバイスクラス (切断時に復元)
     * -1 = 未取得または取得失敗
     */
    private var originalBtClass: Int = -1

    /**
     * Gamepad の Bluetooth Class of Device
     * 0x002508 = Peripheral(Major:0x05) + Gamepad(Minor:0x02)
     * Switch がコントローラーとして認識するために必要
     *
     * 設定方法: BluetoothAdapter.setBluetoothClass() は非公開APIのため
     * リフレクションでアクセスする。Android 12以降は失敗する場合があるが、
     * BluetoothHidDevice.registerApp() がOS内部でCoD設定を行う場合もある。
     */
    private val GAMEPAD_BT_CLASS = 0x002508

    // ----- リスナー -----
    var listener: ControllerListener? = null

    // ----- レポート制御 -----
    private val reportTimer = AtomicInteger(0)

    @Volatile private var isFullReportMode = false

    /**
     * 現在のボタン/スティック状態 (9バイト)
     *   [0] 右ボタン byte: Y=0x01, X=0x02, B=0x04, A=0x08, R=0x40, ZR=0x80
     *   [1] 中ボタン byte: MINUS=0x01, PLUS=0x02, R_STICK=0x04, L_STICK=0x08, HOME=0x10, CAPTURE=0x20
     *   [2] 左ボタン byte: DOWN=0x01, UP=0x02, RIGHT=0x04, LEFT=0x08, L=0x40, ZL=0x80
     *   [3–5] 左スティック: 12bit X+Y packed, 中立=0x800
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
        /** ペアリング待機中 (discoverable になった) */
        fun onDiscoveryStarted()
        /** ペアリング待機終了 */
        fun onDiscoveryStopped()
    }

    init {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = mgr?.adapter
    }

    // ============================================================
    // 権限チェック
    // ============================================================
    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED

    // ============================================================
    // 接続 (JoyConDroid 方式: Switch が Android を発見してペアリング)
    // ============================================================

    /**
     * ペアリング待機を開始する
     *
     * 1. BT デバイスクラスを Gamepad (0x002508) に変更
     * 2. Android の BT 名を "Joy-Con (R)" に変更
     * 3. HID プロキシ取得 → HID アプリ登録
     * 4. discoverable 化は呼び出し元 MainActivity が担当
     *    (ACTION_REQUEST_DISCOVERABLE は Activity からしか起動できないため)
     */
    fun startDiscovery() {
        if (!hasPermission()) { listener?.onError("Bluetooth権限がありません"); return }
        val adapter = bluetoothAdapter
            ?: run { listener?.onError("Bluetoothがサポートされていません"); return }
        if (!adapter.isEnabled) { listener?.onError("Bluetoothが無効です"); return }

        listener?.onStateChanged("ペアリング待機準備中...")

        // ① BTデバイスクラスを Gamepad (0x002508) に変更
        applyGamepadBtClass()

        // ② BT 名を "Joy-Con (R)" に変更 (元の名前を保存)
        try {
            val current = adapter.name
            if (current != CONTROLLER_BT_NAME) {
                originalBtName = current
                adapter.name = CONTROLLER_BT_NAME
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "BT name change failed: ${e.message}")
        }

        hidExecutor.execute { initHidAndRegister() }
    }

    /** ペアリング待機を停止し、BT 名と BT クラスを元に戻す */
    fun stopDiscovery() {
        restoreBtClass()
        restoreBtName()
        listener?.onDiscoveryStopped()
        listener?.onStateChanged("ペアリング待機停止")
    }

    private fun restoreBtName() {
        val original = originalBtName ?: return
        try {
            bluetoothAdapter?.name = original
            originalBtName = null
        } catch (e: SecurityException) {
            Log.w(TAG, "BT name restore failed: ${e.message}")
        }
    }

    // ============================================================
    // Bluetooth デバイスクラス (CoD) 設定
    // ============================================================

    /**
     * 現在の CoD を保存し、Gamepad クラス (0x002508) に変更する
     *
     * BluetoothAdapter.setBluetoothClass() は非公開APIのためリフレクションを使用。
     * - Android 9〜11: ほぼ成功
     * - Android 12〜13: 機種依存
     * - Android 14+: ブロックされる可能性あり
     * 失敗しても BluetoothHidDevice.registerApp() 内部でOS側がCoD設定することがある。
     */
    private fun applyGamepadBtClass() {
        try {
            // 現在の CoD を取得して保存
            val btClass = bluetoothAdapter?.bluetoothClass
            if (btClass != null) {
                val field = btClass.javaClass.getDeclaredField("mClass")
                field.isAccessible = true
                originalBtClass = field.getInt(btClass)
                Log.d(TAG, "元のBTクラス: 0x${originalBtClass.toString(16).uppercase()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "CoD 読み取り失敗: ${e.message}")
        }

        try {
            val method = BluetoothAdapter::class.java.getDeclaredMethod(
                "setBluetoothClass", Int::class.java
            )
            method.isAccessible = true
            val result = method.invoke(bluetoothAdapter, GAMEPAD_BT_CLASS)
            Log.d(TAG, "BTクラスをGamepad(0x002508)に変更: 結果=$result")
            listener?.onStateChanged("BTデバイスクラスをGamepadに変更しました")
        } catch (e: Exception) {
            // 失敗しても続行 (registerApp内部でOSが設定する場合あり)
            Log.w(TAG, "CoD 設定失敗 (Android14+では正常): ${e.message}")
            listener?.onStateChanged("BTデバイスクラス変更スキップ (OS内部で設定)")
        }
    }

    /** CoD を元の値に戻す */
    private fun restoreBtClass() {
        val original = originalBtClass
        if (original < 0) return
        try {
            val method = BluetoothAdapter::class.java.getDeclaredMethod(
                "setBluetoothClass", Int::class.java
            )
            method.isAccessible = true
            method.invoke(bluetoothAdapter, original)
            Log.d(TAG, "BTクラスを元に戻しました: 0x${original.toString(16).uppercase()}")
        } catch (e: Exception) {
            Log.w(TAG, "CoD 復元失敗: ${e.message}")
        }
        originalBtClass = -1
    }


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

    private fun initHidAndRegister() {
        getHidProxy { hid ->
            if (hid == null) { listener?.onError("HIDプロキシが利用できません"); return@getHidProxy }
            if (appRegistered) {
                // 登録済みなら discoverable 通知だけ出す
                listener?.onDiscoveryStarted()
                listener?.onStateChanged("Switch からのペアリングを待機中...")
                return@getHidProxy
            }
            registerHidApp(hid)
        }
    }

    private fun registerHidApp(hid: BluetoothHidDevice) {
        try {
            val descriptor = DESCRIPTOR_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Wireless Gamepad",    // HID 名 (SDP レコード)
                "Gamepad",             // 説明
                "Nintendo",            // プロバイダ
                0x08.toByte(),         // SubClass: Gamepad
                descriptor
            )
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                QOS_TOKEN_RATE, QOS_TOKEN_BUCKET, QOS_PEAK_BANDWIDTH,
                QOS_LATENCY, QOS_DELAY_VARIATION
            )

            hid.registerApp(sdp, null, qos, hidExecutor, object : BluetoothHidDevice.Callback() {

                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    appRegistered = registered
                    if (registered) {
                        // 既にペアリング済みの Switch があれば直接接続を試みる
                        val bondedSwitch = findBondedSwitch()
                        if (bondedSwitch != null) {
                            try { hid.connect(bondedSwitch) }
                            catch (e: Exception) { Log.w(TAG, "connect bonded: ${e.message}") }
                        } else {
                            // Switch からの発見を待つ → discoverable 開始を通知
                            listener?.onDiscoveryStarted()
                            listener?.onStateChanged("Switch からのペアリングを待機中...")
                        }
                    }
                }

                override fun onConnectionStateChanged(dev: BluetoothDevice, state: Int) {
                    // JoyConDroid と同様: "Nintendo Switch" からの接続のみ受け付ける
                    val devName = try { dev.name } catch (_: Exception) { "" }
                    if (!NINTENDO_SWITCH.equals(devName, ignoreCase = true)) {
                        Log.d(TAG, "非Switch デバイスを無視: $devName")
                        return
                    }

                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            deviceConnected = true; connectedDevice = dev
                            listener?.onDiscoveryStopped()
                            listener?.onConnected(dev)
                            startHandshake()
                        }
                        BluetoothProfile.STATE_CONNECTING  ->
                            listener?.onStateChanged("接続中...")
                        BluetoothProfile.STATE_DISCONNECTING ->
                            listener?.onStateChanged("切断中...")
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            deviceConnected = false; connectedDevice = null
                            stopScheduler()
                            unpairDevice(dev)
                            restoreBtClass()
                            restoreBtName()
                            listener?.onDisconnected()
                        }
                    }
                }

                override fun onGetReport(dev: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                    try { hid.replyReport(dev, type, id, buildFullReport()) }
                    catch (e: Exception) { Log.e(TAG, "replyReport error", e) }
                }

                override fun onSetReport(dev: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                    try { hid.reportError(dev, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
                    catch (e: Exception) { Log.e(TAG, "reportError error", e) }
                }

                override fun onInterruptData(dev: BluetoothDevice, reportId: Byte, data: ByteArray) {
                    if (reportId == 0x01.toByte() && data.size >= 10) {
                        handleSubcommand(dev, data)
                    }
                }
            })
        } catch (e: Exception) { listener?.onError("HID登録エラー: ${e.message}") }
    }

    /** ペアリング済み "Nintendo Switch" デバイスを探す (JoyConDroid の getConnectedNintendoSwitch 相当) */
    private fun findBondedSwitch(): BluetoothDevice? {
        return try {
            bluetoothAdapter?.bondedDevices?.firstOrNull {
                NINTENDO_SWITCH.equals(try { it.name } catch (_: Exception) { "" }, ignoreCase = true)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices access denied: ${e.message}")
            null
        }
    }

    /**
     * ペアリング情報を削除 (JoyConDroid の unpairDevice 相当)
     * Switch 側のペアリング情報は Switch 本体側で管理されているため、
     * Android 側で removeBond を呼ぶことで次回クリーンにペアリングできる。
     */
    private fun unpairDevice(device: BluetoothDevice) {
        try {
            val m: Method = device.javaClass.getMethod("removeBond")
            m.invoke(device)
            Log.d(TAG, "Unpaired: ${device.address}")
        } catch (e: Exception) {
            Log.w(TAG, "Unpair failed: ${e.message}")
        }
    }

    // ============================================================
    // サブコマンド処理 (Switch → Android)
    // ============================================================

    private fun handleSubcommand(dev: BluetoothDevice, data: ByteArray) {
        val timer = data[0]
        val subCmd = data[9]
        Log.d(TAG, "SubCmd: 0x${(subCmd.toInt() and 0xFF).toString(16).uppercase()}")

        when (subCmd) {
            0x01.toByte() -> ack(dev, timer, subCmd, 0x81.toByte())
            0x02.toByte() -> {
                val macStr = try { bluetoothAdapter?.address ?: "00:00:00:00:00:00" }
                             catch (_: Exception) { "00:00:00:00:00:00" }
                val macBytes = macStr.split(":").map { it.toInt(16).toByte() }
                val info = ByteArray(12).apply {
                    this[0] = 0x04; this[1] = 0x21
                    this[2] = 0x02; this[3] = 0x02  // 0x01=Joy-Con(L)  0x02=Joy-Con(R)  0x03=Pro
                    for (i in 0..5) this[4 + i] = macBytes.getOrElse(5 - i) { 0 }
                    this[10] = 0x01; this[11] = 0x01
                }
                subcmdReply(dev, timer, 0x82.toByte(), subCmd, info)
            }
            0x03.toByte() -> {
                ack(dev, timer, subCmd, 0x80.toByte())
                if (!isFullReportMode) switchToFullReportMode()
            }
            0x04.toByte() -> subcmdReply(dev, timer, 0x83.toByte(), subCmd, ByteArray(8))
            0x08.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
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
            0x11.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x21.toByte() -> subcmdReply(dev, timer, 0xA0.toByte(), subCmd, ByteArray(34))
            0x22.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x30.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x38.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x40.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x41.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x42.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x43.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x48.toByte() -> ack(dev, timer, subCmd, 0x80.toByte())
            0x50.toByte() -> subcmdReply(dev, timer, 0x80.toByte(), subCmd,
                byteArrayOf(0x08, 0x07, 0x00, 0x00))
            else -> ack(dev, timer, subCmd, 0x80.toByte())
        }
    }

    private fun ack(dev: BluetoothDevice, timer: Byte, subCmd: Byte, ack: Byte) {
        subcmdReply(dev, timer, ack, subCmd, ByteArray(0))
    }

    private fun subcmdReply(dev: BluetoothDevice, timer: Byte, ack: Byte, subCmd: Byte, extra: ByteArray) {
        val buf = ByteArray(48)
        buf[0]  = timer
        buf[1]  = 0x8E.toByte()
        setStickCenter(buf, offset = 5)
        setStickCenter(buf, offset = 8)
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

    private fun buildFullReport(): ByteArray {
        val buf = ByteArray(48)
        buf[0]  = (reportTimer.incrementAndGet() and 0xFF).toByte()
        buf[1]  = 0x8E.toByte()
        val state = currentButtonState.get()
        state.copyInto(buf, destinationOffset = 2, startIndex = 0, endIndex = 9)
        buf[11] = 0xB0.toByte()
        return buf
    }

    fun sendControllerInput(buttonData: ByteArray) {
        val dev = connectedDevice ?: run { Log.w(TAG, "未接続"); return }
        if (buttonData.size >= 9) currentButtonState.set(buttonData.copyOf(9))
        rawSend(dev, 0x30, buildFullReport())
    }

    fun scheduleRelease(releaseReport: ByteArray, delayMs: Long) {
        hidExecutor.execute {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
            sendControllerInput(releaseReport)
        }
    }

    // ============================================================
    // ハンドシェイク & 定期レポート
    // ============================================================

    private fun startHandshake() {
        Thread({
            var count = 0
            while (!isFullReportMode && deviceConnected && count < 100) {
                val dev = connectedDevice ?: break
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
        stopScheduler()
        hidExecutor.execute {
            try {
                connectedDevice?.let { bluetoothHidDevice?.disconnect(it) }
                bluetoothHidDevice?.unregisterApp()
            } catch (e: Exception) { Log.e(TAG, "disconnect error", e) }
            deviceConnected = false; connectedDevice = null; appRegistered = false
            restoreBtClass()
            restoreBtName()
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

    private fun setStickCenter(buf: ByteArray, offset: Int = 3) {
        buf[offset]     = 0x00
        buf[offset + 1] = 0x08
        buf[offset + 2] = 0x80.toByte()
    }
}
