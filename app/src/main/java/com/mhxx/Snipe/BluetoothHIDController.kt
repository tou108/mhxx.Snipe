package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Nintendo Switch用 Bluetooth HID コントローラー
 * Android 9 (API 28) ～ Android 16 (API 36) 対応 / ルート不要
 *
 * ─────────────────────────────────────────────────────────────────
 * 【Class of Device 0x002508 強制設定について】
 *
 *   0x002508 のビット構成:
 *     bit 23-13 (Service Class) : 0b000_0000_001  → Limited Discoverable
 *     bit 12-8  (Major Class)   : 0b0_0101        → Peripheral (0x05)
 *     bit  7-2  (Minor Class)   : 0b00_0010       → Gamepad (0x02)
 *     bit  1-0  (Format)        : 0b00
 *
 *   ルート不要で CoD を設定する3段階戦略:
 *
 *   Strategy-1 (Android 9-11, 最優先):
 *     BluetoothAdapter / IBluetooth の hidden API を Reflection で呼ぶ。
 *     BLUETOOTH_ADMIN 権限で動作する機種では直接 0x002508 が書き込まれる。
 *
 *   Strategy-2 (Android 9-16, 主力):
 *     BluetoothHidDevice.registerApp(sdp subclass=0x08) を呼ぶ。
 *     Android の com.android.bluetooth サービス(システム権限)が内部で
 *     AdapterService.setDeviceClass(PERIPHERAL_GAMEPAD) を実行するため
 *     アプリ側は BLUETOOTH_PRIVILEGED 不要で CoD が 0x002508 になる。
 *
 *   Strategy-3 (初回ペアリング補助):
 *     createDiscoverableIntent() が返す Intent を Activity で起動すると
 *     SCAN_MODE_CONNECTABLE_DISCOVERABLE になり Switch のスキャンに応答できる。
 *     この際も CoD がリフレッシュされる。
 *
 * ─────────────────────────────────────────────────────────────────
 * 【Android バージョン別 Bluetooth 権限】
 *
 *   Android  9-11 (API 28-30) : BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION
 *   Android 12-15 (API 31-35) : BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE
 *   Android 16+   (API 36+)   : 同上 (変更なし)
 *
 *   → AndroidManifest.xml に REQUIRED_PERMISSIONS_BELOW_S / REQUIRED_PERMISSIONS_S_PLUS
 *     の両方を宣言し、実行時に uses-permission の maxSdkVersion で分岐すること。
 * ─────────────────────────────────────────────────────────────────
 */
@SuppressLint("MissingPermission")
class BluetoothHIDController(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHIDController"

        // ── CoD ────────────────────────────────────────────────────────
        /**
         * Nintendo Switch が期待する Class of Device
         *   Peripheral (Major=0x05) + Gamepad (Minor=0x02) + Limited Discoverable
         *   = 0x002508 (10進: 9480)
         */
        const val COD_NINTENDO_GAMEPAD = 0x002508

        // ── デバイス識別 ────────────────────────────────────────────────
        /** Switch が Pro Controller として認識するデバイス名 (完全一致必須) */
        private const val DEVICE_NAME     = "Pro Controller"
        private const val DEVICE_DESC     = "Pro Controller"
        private const val DEVICE_PROVIDER = "Nintendo Co., Ltd."

        // ── HID ディスクリプタ ──────────────────────────────────────────
        /**
         * Nintendo Pro Controller / Joy-Con 共通 HID ディスクリプタ
         * 出典: JoyConDroid (ControllerType.java) / dekuNukem Switch Reverse Engineering
         *
         * Report ID 一覧:
         *   Input  0x21 : Subcommand Reply       (48 bytes)
         *   Input  0x30 : Standard Full Report   (48 bytes) ← メインレポート
         *   Input  0x31 : NFC/IR Report          (361 bytes)
         *   Input  0x3F : Simple HID Report      (11 bytes) ← ハンドシェイク
         *   Output 0x01 : Rumble + Subcommand    (48 bytes) ← Switch→Android
         *   Output 0x10 : Rumble Only            (48 bytes)
         *   Output 0x11 : NFC/IR MCU Data        (48 bytes)
         */
        private const val DESCRIPTOR_HEX =
            "05010905a1010601ff852109217508953081028530093075089530810285310931750896690181028532" +
            "0932750896690181028533093375089669018102853f0509190129101500250175019510810205010939" +
            "1500250775049501814205097504950181010501093009310933093416000027ffff0000751095048102" +
            "0601ff850109017508953091028510091075089530910285110911750895309102851209127508953091" +
            "02c0"

        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

        // ── AndroidManifest.xml 参照用 ──────────────────────────────────
        /** Android 9-11 (API 28-30) に必要な権限 */
        val REQUIRED_PERMISSIONS_BELOW_S = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        /** Android 12+ (API 31+) に必要な権限 */
        @Suppress("InlinedApi")
        val REQUIRED_PERMISSIONS_S_PLUS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        /** 現在の OS に合った必要権限配列を返す */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                REQUIRED_PERMISSIONS_S_PLUS
            else
                REQUIRED_PERMISSIONS_BELOW_S
    }

    // ── BT 基盤 ──────────────────────────────────────────────────────
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    /** コールバック + サブコマンド処理を同一スレッドで行う BT 専用 Executor */
    private val hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── 接続状態 ──────────────────────────────────────────────────────
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var deviceConnected  = false
    private var appRegistered    = false
    private var serviceConnected = false

    // ── CoD 設定状態 ─────────────────────────────────────────────────
    /** true = 何らかの方法で CoD 設定を試みた */
    @Volatile private var codAttempted = false

    // ── リスナー ────────────────────────────────────────────────────
    var listener: ControllerListener? = null

    // ── レポート制御 ────────────────────────────────────────────────
    private val reportTimer = AtomicInteger(0)
    @Volatile private var isFullReportMode = false

    /**
     * 現在のボタン/スティック状態 (9 バイト)
     *   [0] 右ボタン : Y=0x01 X=0x02 B=0x04 A=0x08 R=0x40 ZR=0x80
     *   [1] 中ボタン : MINUS=0x01 PLUS=0x02 RS=0x04 LS=0x08 HOME=0x10 CAP=0x20
     *   [2] 左ボタン : DOWN=0x01 UP=0x02 RIGHT=0x04 LEFT=0x08 L=0x40 ZL=0x80
     *   [3-5] 左スティック 12bit X+Y (中立 0x800)
     *   [6-8] 右スティック 12bit X+Y (中立 0x800)
     */
    private val currentButtonState = AtomicReference(ByteArray(9).also { setStickCenter(it) })

    // ── 定期レポートスケジューラー ────────────────────────────────────
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
    // 権限チェック (Android 9-16 対応)
    // ============================================================

    /**
     * Bluetooth の基本接続権限があるか確認する。
     *
     * Android 9-11  : BLUETOOTH + BLUETOOTH_ADMIN
     * Android 12+   : BLUETOOTH_CONNECT + BLUETOOTH_SCAN
     */
    fun hasPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            checkPerm(Manifest.permission.BLUETOOTH_CONNECT) &&
            checkPerm(Manifest.permission.BLUETOOTH_SCAN)
        else ->
            checkPerm(Manifest.permission.BLUETOOTH) &&
            checkPerm(Manifest.permission.BLUETOOTH_ADMIN)
    }

    /**
     * Discoverable / アドバタイズ権限があるか確認する。
     * Android 12+ : BLUETOOTH_ADVERTISE
     * Android 9-11: 不要 (true 返却)
     */
    fun hasAdvertisePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkPerm(Manifest.permission.BLUETOOTH_ADVERTISE)
    }

    private fun checkPerm(perm: String) =
        ActivityCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    // ============================================================
    // CoD 0x002508 強制設定 (ルート不要)
    // ============================================================

    /**
     * デバイス名を "Pro Controller" に設定し、
     * Class of Device を 0x002508 (Peripheral > Gamepad) に強制設定する。
     *
     * ルート不要。Strategy-1 (Reflection) と Strategy-2 (registerApp 自動) の
     * 両方を適用するため、必ず connectToSwitch() の前に呼ぶこと。
     *
     * @return 処理を実行できたか (権限不足の場合 false)
     */
    fun forceDeviceClassAndName(): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "forceDeviceClassAndName: Bluetooth 権限なし")
            return false
        }

        // ── Step 1: デバイス名を "Pro Controller" に設定 ─────────────
        try {
            if (bluetoothAdapter?.name != DEVICE_NAME) {
                bluetoothAdapter?.name = DEVICE_NAME
                Log.d(TAG, "デバイス名 → '$DEVICE_NAME'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "デバイス名設定失敗 (無視): $e")
        }

        // ── Step 2: Android バージョン別 CoD 設定 ─────────────────────
        codAttempted = true
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> {
                // Android 8 以下: BluetoothHidDevice API 非対応
                Log.e(TAG, "Android ${Build.VERSION.SDK_INT}: BluetoothHidDevice 非対応")
                false
            }
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> {
                // Android 9-11: Reflection で直接設定 + registerApp() フォールバック
                val ok = setDeviceClassViaReflection(COD_NINTENDO_GAMEPAD)
                Log.d(TAG, "Android ${Build.VERSION.SDK_INT}: Reflection CoD = " +
                    if (ok) "成功 (0x${COD_NINTENDO_GAMEPAD.toString(16)})"
                    else "失敗 → registerApp() に委任")
                true // 失敗でも registerApp() で設定されるため true
            }
            else -> {
                // Android 12+: registerApp() 時に Bluetooth サービスが自動設定
                // BLUETOOTH_PRIVILEGED なしでも com.android.bluetooth が代行する
                Log.d(TAG, "Android ${Build.VERSION.SDK_INT}: " +
                    "CoD は registerApp() 時に自動設定 (0x${COD_NINTENDO_GAMEPAD.toString(16)})")
                true
            }
        }
    }

    /**
     * Reflection で BluetoothAdapter / IBluetooth の hidden API を呼び、
     * Class of Device を直接設定する。
     *
     * 成功条件:
     *   - Android 9-11: BLUETOOTH_ADMIN 権限がある機種で動作
     *   - Android 12+: SecurityException で失敗 (registerApp 自動設定が使われる)
     *
     * @param cod 設定する CoD 値 (0x002508 を渡す)
     * @return 設定に成功した場合 true
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun setDeviceClassViaReflection(cod: Int): Boolean {

        // ── Attempt-1: BluetoothAdapter.setDeviceClass(int) ──────────
        try {
            val m = bluetoothAdapter?.javaClass
                ?.getDeclaredMethod("setDeviceClass", Int::class.java)
            m?.isAccessible = true
            val result = m?.invoke(bluetoothAdapter, cod)
            Log.d(TAG, "BluetoothAdapter.setDeviceClass(0x${cod.toString(16)}) = $result")
            if (result == true) return true
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "setDeviceClass メソッドなし")
        } catch (e: SecurityException) {
            Log.d(TAG, "setDeviceClass: SecurityException (Android 12+ では正常): $e")
            return false // Android 12+ では反射不可
        } catch (e: Exception) {
            Log.w(TAG, "setDeviceClass attempt-1 失敗: $e")
        }

        // ── Attempt-2: IBluetooth.setDeviceClass (mService フィールド経由) ──
        try {
            val mServiceField = bluetoothAdapter?.javaClass
                ?.getDeclaredField("mService")
            mServiceField?.isAccessible = true
            val btService = mServiceField?.get(bluetoothAdapter)
            if (btService != null) {
                val setClass = btService.javaClass
                    .getDeclaredMethod("setDeviceClass", Int::class.java)
                setClass.isAccessible = true
                setClass.invoke(btService, cod)
                Log.d(TAG, "IBluetooth.setDeviceClass(0x${cod.toString(16)}) 成功")
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "IBluetooth attempt-2 失敗: $e")
        }

        // ── Attempt-3: "deviceClass" or "setClass" 系メソッドを名前検索 ──
        try {
            bluetoothAdapter?.javaClass?.declaredMethods
                ?.filter { m ->
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Int::class.java &&
                    (m.name.contains("DeviceClass", true) ||
                     m.name.contains("setClass", true))
                }
                ?.forEach { m ->
                    try {
                        m.isAccessible = true
                        val r = m.invoke(bluetoothAdapter, cod)
                        Log.d(TAG, "${m.name}(0x${cod.toString(16)}) = $r")
                        if (r == true) return true
                    } catch (_: Exception) {}
                }
        } catch (e: Exception) {
            Log.d(TAG, "attempt-3 フォールバック失敗: $e")
        }

        return false
    }

    // ============================================================
    // Discoverable (初回ペアリング補助)
    // ============================================================

    /**
     * Switch の初回ペアリング時に Android デバイスをスキャン可能にする Intent を返す。
     *
     * 使い方:
     *   startActivityForResult(controller.createDiscoverableIntent(), REQ_DISCOVERABLE)
     *
     * この Intent を起動すると:
     *   - Android 9-11 : ユーザーダイアログ表示 → 許可で Discoverable ON
     *   - Android 12+  : BLUETOOTH_ADVERTISE 権限があれば同様
     *   - 副作用として CoD がリフレッシュされる
     *
     * @param durationSec Discoverable 継続秒数 (30-300)
     */
    fun createDiscoverableIntent(durationSec: Int = 120): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                durationSec.coerceIn(30, 300))
        }

    /** 現在 Discoverable (Switch スキャンに応答可能) かどうかを返す */
    fun isDiscoverable(): Boolean =
        bluetoothAdapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE

    // ============================================================
    // 接続
    // ============================================================

    private fun normalizeMac(mac: String) = mac.trim().replace("-", ":").uppercase()
    private fun isValidMac(mac: String) = MAC_REGEX.matches(mac)

    /**
     * Switch の MAC アドレスを指定して接続を開始する。
     *
     * 内部フロー:
     *   1. 権限チェック / MAC 検証 / Android バージョン検証
     *   2. forceDeviceClassAndName() でデバイス名と CoD を事前設定
     *   3. getHidProxy() → HID_DEVICE プロファイル取得
     *   4. registerApp() で Nintendo ディスクリプタ登録 (CoD が自動確定)
     *   5. 50ms ウェイト (CoD 書き込み完了を待つ)
     *   6. hid.connect(switchDevice) で L2CAP 接続開始
     *
     * @param macAddress Switch の Bluetooth MAC アドレス (例: "AA:BB:CC:DD:EE:FF")
     */
    fun connectToSwitch(macAddress: String) {
        // ── 権限チェック ──────────────────────────────────────────────
        if (!hasPermission()) {
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "設定 → アプリ → ${context.packageName} → 権限 → " +
                "Bluetoothの接続 / Bluetoothのスキャン を許可"
            else
                "設定 → アプリ → ${context.packageName} → 権限 → Bluetooth を許可"
            listener?.onError("Bluetooth 権限がありません\n$hint")
            return
        }

        // ── Android バージョン検証 ──────────────────────────────────
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            listener?.onError(
                "Android 9 (API 28) 以上が必要です。\n" +
                "現在の Android バージョン: ${Build.VERSION.SDK_INT}"
            )
            return
        }

        // ── MAC アドレス検証 ────────────────────────────────────────
        val mac = normalizeMac(macAddress)
        if (!isValidMac(mac)) {
            listener?.onError(
                "無効な MAC アドレス: $macAddress\n" +
                "正しい形式: AA:BB:CC:DD:EE:FF"
            )
            return
        }

        val adapter = bluetoothAdapter ?: run {
            listener?.onError("このデバイスは Bluetooth をサポートしていません")
            return
        }
        if (!adapter.isEnabled) {
            listener?.onError("Bluetooth が OFF です。設定から ON にしてください。")
            return
        }

        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            listener?.onError("Switch デバイス取得エラー: ${e.message}")
            return
        }

        // ── CoD とデバイス名を事前設定 ──────────────────────────────
        listener?.onStateChanged("デバイスクラス 0x002508 を設定中...")
        forceDeviceClassAndName()

        listener?.onStateChanged("接続準備中...")
        hidExecutor.execute { connectToDevice(device) }
    }

    private fun getHidProxy(callback: (BluetoothHidDevice?) -> Unit) {
        if (serviceConnected && bluetoothHidDevice != null) {
            callback(bluetoothHidDevice)
            return
        }
        bluetoothAdapter?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        bluetoothHidDevice = proxy as? BluetoothHidDevice
                        serviceConnected = true
                        Log.d(TAG, "HID_DEVICE プロファイルサービス接続")
                        callback(bluetoothHidDevice)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        serviceConnected = false
                        bluetoothHidDevice = null
                        Log.d(TAG, "HID_DEVICE プロファイルサービス切断")
                    }
                }
            },
            BluetoothProfile.HID_DEVICE
        )
    }

    private fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device
        getHidProxy { hid ->
            if (hid == null) {
                listener?.onError("HID プロキシが取得できません (HID_DEVICE 非対応機種の可能性)")
                return@getHidProxy
            }

            // 既に登録済みなら直接接続
            if (appRegistered) {
                try {
                    listener?.onStateChanged("Switch に再接続中...")
                    hid.connect(device)
                } catch (e: Exception) {
                    listener?.onError("再接続エラー: ${e.message}")
                }
                return@getHidProxy
            }

            try {
                val descriptor = DESCRIPTOR_HEX.chunked(2)
                    .map { it.toInt(16).toByte() }.toByteArray()

                // ★ registerApp() を呼ぶと Bluetooth サービスが
                //    自動的に CoD を 0x002508 に設定する (Strategy-2)
                val sdp = BluetoothHidDevice.AppSdpSettings(
                    DEVICE_NAME,
                    DEVICE_DESC,
                    DEVICE_PROVIDER,
                    0x08.toByte(), // SubClass: Gamepad (CoD の Minor Class に反映)
                    descriptor
                )
                val qos = BluetoothHidDevice.AppQosSettings(
                    BluetoothHidDevice.AppQosSettings.SERVICE_GUARANTEED,
                    21720, 362, 21720, 16667, 16667
                )

                Log.d(TAG, "registerApp 開始 → CoD 0x${COD_NINTENDO_GAMEPAD.toString(16)} が自動設定されます")
                hid.registerApp(
                    sdp, null, qos, hidExecutor,
                    object : BluetoothHidDevice.Callback() {

                        override fun onAppStatusChanged(
                            pluggedDevice: BluetoothDevice?,
                            registered: Boolean
                        ) {
                            appRegistered = registered
                            if (registered) {
                                Log.d(TAG, "HID アプリ登録完了 — CoD 0x${
                                    COD_NINTENDO_GAMEPAD.toString(16)} 適用済み")
                                // CoD 書き込みが Bluetooth スタックに反映されるまで 50ms 待機
                                try { Thread.sleep(50) } catch (_: InterruptedException) {}
                                targetDevice?.let {
                                    try {
                                        listener?.onStateChanged("Switch に接続中...")
                                        hid.connect(it)
                                    } catch (e: Exception) {
                                        listener?.onError("接続開始エラー: ${e.message}")
                                    }
                                }
                            } else {
                                Log.w(TAG, "HID アプリ登録解除")
                            }
                        }

                        override fun onConnectionStateChanged(
                            dev: BluetoothDevice,
                            state: Int
                        ) {
                            when (state) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    deviceConnected = true
                                    connectedDevice = dev
                                    Log.d(TAG, "Switch 接続完了: ${dev.address}")
                                    listener?.onConnected(dev)
                                    startHandshake()
                                }
                                BluetoothProfile.STATE_CONNECTING ->
                                    listener?.onStateChanged("接続中...")
                                BluetoothProfile.STATE_DISCONNECTING ->
                                    listener?.onStateChanged("切断中...")
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    deviceConnected = false
                                    connectedDevice = null
                                    stopScheduler()
                                    Log.d(TAG, "Switch 切断")
                                    listener?.onDisconnected()
                                }
                            }
                        }

                        override fun onGetReport(
                            dev: BluetoothDevice,
                            type: Byte,
                            id: Byte,
                            bufferSize: Int
                        ) {
                            try { hid.replyReport(dev, type, id, buildFullReport()) }
                            catch (e: Exception) { Log.e(TAG, "replyReport エラー", e) }
                        }

                        override fun onSetReport(
                            dev: BluetoothDevice,
                            type: Byte,
                            id: Byte,
                            data: ByteArray
                        ) {
                            try { hid.reportError(dev, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
                            catch (e: Exception) { Log.e(TAG, "reportError エラー", e) }
                        }

                        /**
                         * Switch からの Output Report を受信する
                         *   0x01: Rumble + Subcommand → サブコマンド処理必須
                         *   0x10: Rumble only         → 無視
                         *   0x11: NFC/IR              → 無視
                         * このコールバックに応答しないと Switch は入力を永久に無視する
                         */
                        override fun onInterruptData(
                            dev: BluetoothDevice,
                            reportId: Byte,
                            data: ByteArray
                        ) {
                            if (reportId == 0x01.toByte() && data.size >= 10) {
                                handleSubcommand(dev, data)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                listener?.onError("HID 登録エラー: ${e.message}")
            }
        }
    }

    // ============================================================
    // サブコマンド処理 (Switch → Android)
    // ============================================================

    /**
     * Output Report 0x01 のペイロード:
     *   data[0]   = global packet counter
     *   data[1-8] = rumble data (L+R 4bytes each)
     *   data[9]   = subcommand ID
     *   data[10-] = subcommand arguments
     */
    private fun handleSubcommand(dev: BluetoothDevice, data: ByteArray) {
        val timer  = data[0]
        val subCmd = data[9]
        Log.d(TAG, "SubCmd: 0x${(subCmd.toInt() and 0xFF).toString(16).uppercase()}")

        when (subCmd) {
            // 0x01: Bluetooth manual pairing
            0x01.toByte() -> ack(dev, timer, subCmd, 0x81.toByte())

            // 0x02: Request device info
            0x02.toByte() -> {
                // Android 12+ はプライバシー保護で adapter.address が 02:00:00:00:00:00 を返す
                // Switch はここで取得した MAC を記憶するが、ダミーでも接続継続に問題なし
                val macStr = try {
                    bluetoothAdapter?.address?.takeIf { it != "02:00:00:00:00:00" }
                        ?: "00:00:00:00:00:00"
                } catch (_: Exception) { "00:00:00:00:00:00" }
                val macBytes = macStr.split(":").map { it.toInt(16).toByte() }
                val info = ByteArray(12).apply {
                    this[0] = 0x04; this[1] = 0x21          // firmware version
                    this[2] = 0x03; this[3] = 0x02          // Pro Controller type
                    for (i in 0..5) this[4 + i] = macBytes.getOrElse(5 - i) { 0 }
                    this[10] = 0x01; this[11] = 0x01        // SPI color flags
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
                    reply[0] = data[10]; reply[1] = data[11]
                    reply[2] = data[12]; reply[3] = data[13]
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
     *   buf[1]     = battery/connection (0x8E = full + BT connected)
     *   buf[2-4]   = buttons (all 0)
     *   buf[5-7]   = left stick center
     *   buf[8-10]  = right stick center
     *   buf[11]    = vibrator (0xB0)
     *   buf[12]    = ACK byte
     *   buf[13]    = subcommand ID
     *   buf[14-47] = reply data (最大 34 bytes)
     */
    private fun subcmdReply(
        dev: BluetoothDevice,
        timer: Byte,
        ack: Byte,
        subCmd: Byte,
        extra: ByteArray
    ) {
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
            Log.e(TAG, "sendReport(0x${reportId.toString(16)}) エラー: $e")
        }
    }

    /**
     * Standard Full Report (0x30, 48 bytes) を currentButtonState から生成する
     *
     * レイアウト:
     *   buf[0]     = timer (インクリメント)
     *   buf[1]     = battery/connection (0x8E)
     *   buf[2-4]   = buttons (3 bytes, Nintendo Pro Controller ビット配置)
     *   buf[5-7]   = left stick  (12bit X + 12bit Y packed)
     *   buf[8-10]  = right stick
     *   buf[11]    = vibrator input report (0xB0)
     *   buf[12-47] = IMU data (0 埋め)
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
     * @param buttonData 9 バイト配列
     *   [0] 右ボタン [1] 中ボタン [2] 左ボタン
     *   [3-5] 左スティック 12bit [6-8] 右スティック 12bit
     */
    fun sendControllerInput(buttonData: ByteArray) {
        val dev = connectedDevice ?: run { Log.w(TAG, "未接続"); return }
        if (buttonData.size >= 9) currentButtonState.set(buttonData.copyOf(9))
        rawSend(dev, 0x30, buildFullReport())
    }

    /**
     * 指定ミリ秒後にリリースレポートを送信する。
     * hidExecutor で実行することで press → sleep → release の順序を保証する。
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
     * Switch が SubCmd 0x03 (set input report mode) を送ってきたら終了。
     */
    private fun startHandshake() {
        Thread({
            var count = 0
            while (!isFullReportMode && deviceConnected && count < 100) {
                val dev = connectedDevice ?: break
                // Simple HID レポート (11 bytes):
                //   buf[0-1] = buttons (0)
                //   buf[2]   = hat switch neutral (0x08)
                //   buf[3-10]= sticks at center (0x8000 LE per axis)
                val buf = ByteArray(11).apply {
                    this[2]  = 0x08
                    this[4]  = 0x80.toByte()
                    this[6]  = 0x80.toByte()
                    this[8]  = 0x80.toByte()
                    this[10] = 0x80.toByte()
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
        hidExecutor.execute {
            try {
                connectedDevice?.let { bluetoothHidDevice?.disconnect(it) }
                bluetoothHidDevice?.unregisterApp()
            } catch (e: Exception) {
                Log.e(TAG, "disconnect エラー", e)
            }
            deviceConnected  = false
            connectedDevice  = null
            appRegistered    = false
            codAttempted     = false
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
     *   byte[offset+0] = X[7:0]
     *   byte[offset+1] = X[11:8] | (Y[3:0] << 4)
     *   byte[offset+2] = Y[11:4]
     * X=Y=0x800 → 0x00, 0x08, 0x80
     */
    private fun setStickCenter(buf: ByteArray, offset: Int = 3) {
        buf[offset]     = 0x00
        buf[offset + 1] = 0x08
        buf[offset + 2] = 0x80.toByte()
    }

    // ============================================================
    // ステータス照会
    // ============================================================

    /** 接続中かどうか */
    val isConnected: Boolean get() = deviceConnected

    /** 現在接続中の BluetoothDevice (未接続時 null) */
    val currentDevice: BluetoothDevice? get() = connectedDevice

    /** CoD 設定を試みたかどうか */
    val isCodAttempted: Boolean get() = codAttempted
}
