package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.Random
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Nintendo Switch用 Bluetooth HID コントローラー
 *
 * 【修正履歴】
 *
 * ✅ 修正1: MACアドレスの取得元を変更
 *   ❌ 旧: bluetoothAdapter?.address → Android 6+ では常に "02:00:00:00:00:00" を返す
 *   ✅ 新: SharedPreferences に永続保存したランダムMAC を使用
 *         (JoyConDroid と同じ方式 — 毎回同じMAC を Switch に報告することで
 *          ペアリング情報の一貫性を保証する)
 *
 * ✅ 修正2: サブコマンド 0x01 (Bluetooth Manual Pairing) の実装を修正
 *   ❌ 旧: data[10] のサブタイプを読まず、全パターンを空データACK で返していた
 *          → 未ペアリングのSwitchがMACとLTKを要求してもデータを返せないため
 *            ペアリングネゴシエーションが失敗していた
 *   ✅ 新: サブタイプ別に正しいデータを返す
 *          0x01 → コントローラーのMAC (Little-Endian) を返す
 *          0x02 → LTK XOR 0xAA を返す
 *          0x03 → 単純ACK
 *
 * ✅ 修正3: registerApp() の inQos を null → qos に変更
 *   ❌ 旧: hid.registerApp(sdp, null, qos, ...)
 *   ✅ 新: hid.registerApp(sdp, qos, qos, ...)
 *          (JoyConDroid と同一。一部のSwitch FW で inQos=null だと接続拒否される)
 *
 * ✅ 修正4: SDP Provider 名を修正
 *   ❌ 旧: "Nintendo Co., Ltd."
 *   ✅ 新: "Nintendo"
 *          (JoyConDroid と同一。正式なプロバイダー文字列に合わせる)
 */
@SuppressLint("MissingPermission")
class BluetoothHIDController(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHIDController"

        // ✅ 修正1: SharedPreferences キー定数
        private const val PREFS_NAME = "bt_hid_prefs"
        private const val PREF_CONTROLLER_MAC = "controller_mac"

        /**
         * Nintendo Pro Controller / Joy-Con 共通 HID ディスクリプタ
         * 出典: JoyConDroid (ControllerType.java) / dekuNukem Nintendo Switch Reverse Engineering
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
    private val hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ----- 接続状態 -----
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var deviceConnected = false
    private var appRegistered = false
    private var serviceConnected = false

    // ----- リスナー -----
    var listener: ControllerListener? = null

    // ----- レポート制御 -----
    private val reportTimer = AtomicInteger(0)

    @Volatile private var isFullReportMode = false

    private val currentButtonState = AtomicReference(ByteArray(9).also { setStickCenter(it) })

    // ----- 定期レポートスケジューラー -----
    @Volatile private var reportScheduler: ScheduledExecutorService? = null

    @Volatile private var pendingReconnectDevice: BluetoothDevice? = null

    // ============================================================
    // インターフェース
    // ============================================================
    interface ControllerListener {
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
        fun onError(message: String)
        fun onStateChanged(state: String)
        fun onBondingStarted(device: BluetoothDevice) {}
    }

    init {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = mgr?.adapter
    }

    // ============================================================
    // ✅ 修正1: MACアドレス管理
    // ============================================================

    /**
     * コントローラーのMACアドレスを取得する。
     *
     * SharedPreferences に保存済みのMACがあればそれを使う。
     * なければランダム生成して永続保存する。
     *
     * 【なぜ bluetoothAdapter.address を使わないか】
     * Android 6.0 (API 23) 以降、プライバシー保護のため
     * BluetoothAdapter.getAddress() は常に "02:00:00:00:00:00" を返す。
     * Switch は subcommand 0x02 / 0x01 で受け取ったMACを使って
     * コントローラーを識別するため、偽のMACではペアリング情報が壊れる。
     */
    private fun getOrGenerateMacAddress(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_CONTROLLER_MAC, null)
        if (saved != null) return saved

        // ランダム生成 (Unicast + Locally Administered)
        val rand = Random()
        val bytes = ByteArray(6)
        rand.nextBytes(bytes)
        bytes[0] = (bytes[0].toInt() and 0xFE).toByte()  // bit0=0 → Unicast
        // bit1は1のままでもよいが JoyConDroid に合わせて 0 にする
        // bytes[0] = (bytes[0].toInt() and 0xFC).toByte()

        val mac = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        prefs.edit().putString(PREF_CONTROLLER_MAC, mac).apply()
        Log.d(TAG, "Generated new controller MAC: $mac")
        return mac
    }

    /**
     * MACアドレス文字列 → バイト配列 (Big-Endian: AA:BB:CC:DD:EE:FF → [AA, BB, CC, DD, EE, FF])
     */
    private fun macToBytes(mac: String): ByteArray =
        mac.split(":").map { it.toInt(16).toByte() }.toByteArray()

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

        val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
        if (isBonded) {
            listener?.onStateChanged("接続準備中...")
        } else {
            listener?.onStateChanged("初回接続: Switch のペアリング画面で「さがす」を押してください...")
            listener?.onBondingStarted(device)
        }
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
                val prev = connectedDevice
                if (prev != null && prev.address != device.address) {
                    // ① 別Switchへ切替: 旧接続を切断 → 切断完了後に doFullReset (STATE_DISCONNECTED で実行)
                    Log.d(TAG, "別デバイスへ切替: ${prev.address} → ${device.address}")
                    pendingReconnectDevice = device
                    try { hid.disconnect(prev) } catch (e: Exception) {
                        Log.w(TAG, "旧デバイス切断エラー: ${e.message}")
                        pendingReconnectDevice = null
                        doFullReset(hid, device)   // 切断失敗時もフルリセット
                    }
                } else if (prev == null) {
                    // ② appRegistered=true だが未接続 → 旧SwitchのHID自動再接続を防ぐため完全リセット
                    Log.d(TAG, "新規接続 (appRegistered=true/未接続) → doFullReset: ${device.address}")
                    doFullReset(hid, device)
                } else {
                    // ③ 同一デバイスへの再接続
                    doConnect(hid, device)
                }
                return@getHidProxy
            }
            try {
                try {
                    hid.connectedDevices?.forEach { d ->
                        Log.d(TAG, "既存HID接続を事前切断: ${d.address}")
                        try { hid.disconnect(d) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}

                val descriptor = DESCRIPTOR_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                // ✅ 修正4: provider を "Nintendo" に変更 (旧: "Nintendo Co., Ltd.")
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Pro Controller",
                    "Pro Controller",
                    "Nintendo",               // ← 修正: JoyConDroid と同一
                    0x08,
                    descriptor
                )
                // ✅ 修正3: inQos に null → qos を渡す (旧: hid.registerApp(sdp, null, qos, ...))
                val qos = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                    21720, 362, 21720, 16667, 16667
                )

                hid.registerApp(sdp, qos, qos, hidExecutor, object : BluetoothHidDevice.Callback() {

                    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                        appRegistered = registered
                        if (registered) {
                            val target = targetDevice ?: return
                            if (pluggedDevice != null && pluggedDevice.address != target.address) {
                                Log.d(TAG, "登録時に不要デバイスが接続中: ${pluggedDevice.address} → 切断")
                                try { hid.disconnect(pluggedDevice) } catch (_: Exception) {}
                                pendingReconnectDevice = target
                                return
                            }
                            if (pluggedDevice?.address == target.address) {
                                return
                            }
                            doConnect(hid, target)
                        }
                    }

                    override fun onConnectionStateChanged(dev: BluetoothDevice, state: Int) {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                val target = targetDevice
                                if (target != null && dev.address != target.address) {
                                    Log.w(TAG, "意図しないデバイス自動接続: ${dev.address} (期待: ${target.address}) → 切断")
                                    listener?.onStateChanged(
                                        "⚠ 別のSwitch(${dev.address})が自動接続しました\n" +
                                        "切断して ${target.address} を待機中..."
                                    )
                                    try { hid.disconnect(dev) } catch (e: Exception) {
                                        Log.e(TAG, "unwanted device disconnect error", e)
                                    }
                                    return
                                }
                                deviceConnected = true; connectedDevice = dev
                                listener?.onConnected(dev)
                                startHandshake()
                            }
                            BluetoothProfile.STATE_CONNECTING  -> listener?.onStateChanged("接続中...")
                            BluetoothProfile.STATE_DISCONNECTING -> listener?.onStateChanged("切断中...")
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (connectedDevice?.address == dev.address) {
                                    deviceConnected = false; connectedDevice = null
                                    stopScheduler()
                                    val pending = pendingReconnectDevice
                                    if (pending != null) {
                                        pendingReconnectDevice = null
                                        Log.d(TAG, "旧接続切断完了 → フルリセットして新デバイスへ接続: ${pending.address}")
                                        doFullReset(hid, pending)   // doConnect→doFullReset に変更
                                    } else {
                                        listener?.onDisconnected()
                                    }
                                } else {
                                    Log.d(TAG, "拒否済みデバイス切断完了: ${dev.address}")
                                    val target = targetDevice
                                    if (target != null && !deviceConnected) {
                                        Log.d(TAG, "ターゲットへの接続を再試行: ${target.address}")
                                        doConnect(hid, target)
                                    }
                                }
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

            // ✅ 修正2: サブコマンド 0x01 (Bluetooth Manual Pairing) を正しく実装
            //
            // 旧実装: ack(dev, timer, subCmd, 0x81.toByte())
            //         → data[10] のサブタイプを無視して空データACKを返していた
            //         → 未ペアリングSwitchはMACとLTKを受け取れずペアリング失敗
            //
            // 新実装: data[10] のサブタイプを読んで正しいデータを返す
            //   0x01: Switch が「コントローラーのBT MACを教えて」と要求
            //         → 保存済みMAC を Little-Endian で返す
            //   0x02: Switch が「LTKを教えて」と要求
            //         → LTK XOR 0xAA (ゼロLTK → 全 0xAA) を返す
            //   0x03: Switch が「ペアリング情報を保存した」と通知
            //         → 単純ACK (0x80) を返す
            0x01.toByte() -> {
                val pairRequestType = if (data.size > 10) data[10].toInt() and 0xFF else 0x03
                Log.d(TAG, "PairRequestType: 0x${pairRequestType.toString(16).uppercase()}")

                when (pairRequestType) {
                    0x01 -> {
                        // コントローラーのBT MACを Little-Endian で返す
                        val controllerMac = getOrGenerateMacAddress()
                        val macBytes = macToBytes(controllerMac)
                        val replyData = ByteArray(32)
                        replyData[0] = 0x01
                        for (i in 0..5) replyData[1 + i] = macBytes[5 - i] // Little-Endian
                        Log.d(TAG, "Pairing 0x01: sending MAC $controllerMac (LE)")
                        subcmdReply(dev, timer, 0x81.toByte(), subCmd, replyData)
                    }
                    0x02 -> {
                        // LTK XOR 0xAA (ゼロLTKのXOR結果 = 全0xAA)
                        val ltk = ByteArray(16) { 0xAA.toByte() }
                        Log.d(TAG, "Pairing 0x02: sending LTK (all 0xAA)")
                        subcmdReply(dev, timer, 0x81.toByte(), subCmd, ltk)
                    }
                    else -> {
                        // 0x03: 保存確認 → 単純ACK
                        Log.d(TAG, "Pairing 0x03: simple ACK")
                        ack(dev, timer, subCmd, 0x80.toByte())
                    }
                }
            }

            // ✅ 修正1の適用箇所: subcommand 0x02 でも保存済みMACを使う
            //
            // 旧実装: bluetoothAdapter?.address → "02:00:00:00:00:00" を返す
            // 新実装: getOrGenerateMacAddress() → 永続保存したMACを返す
            0x02.toByte() -> {
                val controllerMac = getOrGenerateMacAddress()
                val macBytes = macToBytes(controllerMac)
                Log.d(TAG, "DeviceInfo: reporting MAC $controllerMac")
                val info = ByteArray(12).apply {
                    this[0] = 0x04; this[1] = 0x21          // firmware ver
                    this[2] = 0x03; this[3] = 0x02          // Pro Controller
                    for (i in 0..5) this[4 + i] = macBytes[5 - i] // Little-Endian
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

    /**
     * HIDアプリ登録を完全にリセットしてから新しいSwitchへ接続する。
     *
     * 【用途】
     *   - 別のSwitchへ切り替える際、旧SwitchのHID自動再接続を遮断する
     *   - appRegistered=true の状態から新規ペアリングするためのクリーンスタート
     *
     * 【処理フロー】
     *   1. 全HID接続を切断 (connectedDevices)
     *   2. unregisterApp() でHIDプロファイルを解放 → BTスタックから旧Switch情報を消去
     *   3. 300ms 待機 (BTスタック安定化)
     *   4. connectToDevice() を再呼び出し → 新規 registerApp フローへ
     *
     * 【なぜ doConnect ではなくこれが必要か】
     *   appRegistered=true のまま別のSwitchに doConnect() すると、
     *   旧Switch (ペアリング済み) がHIDプロファイルへ自動再接続を試み続け、
     *   新Switchへの接続を妨害する。unregisterApp() によりこの競合を排除できる。
     */
    private fun doFullReset(hid: BluetoothHidDevice, device: BluetoothDevice) {
        Log.d(TAG, "doFullReset 開始 → ${device.address}")
        listener?.onStateChanged("接続リセット中 (旧Switch切断)...")

        // 全既存接続を切断
        try {
            hid.connectedDevices?.forEach { d ->
                Log.d(TAG, "  doFullReset: 既存接続を切断: ${d.address}")
                try { hid.disconnect(d) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // HIDアプリ登録解除 (旧SwitchがHIDプロファイルへ自動接続するのを防ぐ)
        try { hid.unregisterApp() } catch (_: Exception) {}
        appRegistered = false
        deviceConnected = false
        connectedDevice = null

        // BTスタック安定化を待ってから再登録・接続
        hidExecutor.execute {
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            if (targetDevice?.address == device.address) {
                Log.d(TAG, "doFullReset 完了 → connectToDevice 再呼び出し: ${device.address}")
                connectToDevice(device)
            }
        }
    }

    private fun doConnect(hid: BluetoothHidDevice, device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            try { hid.connect(device) }
            catch (e: Exception) { listener?.onError("接続エラー: ${e.message}") }
        } else {
            try { hid.connect(device) } catch (_: Exception) {}
            listener?.onStateChanged("Switch のペアリング画面で「さがす」を押してください\nペアリング完了後に自動接続されます")
        }
    }

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

    fun onBondCompleted(device: BluetoothDevice) {
        if (targetDevice != null && device.address != targetDevice!!.address) {
            targetDevice = device
        } else if (targetDevice == null) {
            targetDevice = device
        }
        listener?.onStateChanged("ペアリング完了 → HID 接続中...")
        hidExecutor.execute {
            if (appRegistered && bluetoothHidDevice != null) {
                try { bluetoothHidDevice!!.connect(device) }
                catch (e: Exception) { listener?.onError("ペアリング後の接続エラー: ${e.message}") }
            } else {
                connectToDevice(device)
            }
        }
    }

    fun disconnect() {
        targetDevice = null
        pendingReconnectDevice = null
        stopScheduler()
        hidExecutor.execute {
            try {
                connectedDevice?.let { bluetoothHidDevice?.disconnect(it) }
                bluetoothHidDevice?.unregisterApp()
            } catch (e: Exception) { Log.e(TAG, "disconnect error", e) }
            deviceConnected = false; connectedDevice = null; appRegistered = false
            serviceConnected = false; bluetoothHidDevice = null
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
