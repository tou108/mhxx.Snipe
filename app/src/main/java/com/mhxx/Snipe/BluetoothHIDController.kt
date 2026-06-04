package com.mhxx.snipe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Nintendo Switch用 Bluetooth HID コントローラー
 * 🔧 修正版v2: HIDディスクリプタ拡張 (18ボタン + 両スティック)
 */
class BluetoothHIDController(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHIDController"

        // HID設定
        private const val QOS_TOKEN_RATE = 21720
        private const val QOS_TOKEN_BUCKET_SIZE = 362
        private const val QOS_PEAK_BANDWIDTH = 21720
        private const val QOS_LATENCY = 16667
        private const val QOS_DELAY_VARIATION = 16667

        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var connectedDevice: BluetoothDevice? = null
    private var targetDevice: BluetoothDevice? = null
    private var controllerListener: ControllerListener? = null
    private var appRegistered = false
    private var serviceConnected = false
    private var deviceConnected = false

    interface ControllerListener {
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
        fun onError(message: String)
        fun onStateChanged(state: String)
    }

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        bluetoothAdapter = bluetoothManager?.adapter
    }

    fun setListener(listener: ControllerListener) {
        this.controllerListener = listener
    }

    private fun isValidMacAddress(mac: String): Boolean {
        return MAC_REGEX.matches(mac)
    }

    private fun normalizeMacAddress(mac: String): String {
        return mac.trim().replace("-", ":").uppercase()
    }

    @SuppressLint("MissingPermission")
    fun getHidDeviceProxy(callback: (BluetoothHidDevice?) -> Unit) {
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
                        Log.d(TAG, "HID Device Profile connected")
                        callback(bluetoothHidDevice)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        serviceConnected = false
                        bluetoothHidDevice = null
                        Log.d(TAG, "HID Device Profile disconnected")
                    }
                }
            },
            BluetoothProfile.HID_DEVICE
        )
    }

    @SuppressLint("MissingPermission")
    fun connectToSwitch(macAddress: String) {
        if (!hasBluetoothPermissions()) {
            controllerListener?.onError("Bluetooth権限がありません。アプリの権限設定を確認してください。")
            return
        }

        val normalizedMac = normalizeMacAddress(macAddress)

        if (!isValidMacAddress(normalizedMac)) {
            controllerListener?.onError(
                "無効なMACアドレス形式です: \"$macAddress\"\n正しい形式: AA:BB:CC:DD:EE:FF"
            )
            return
        }

        if (bluetoothAdapter == null) {
            controllerListener?.onError("このデバイスはBluetoothをサポートしていません")
            return
        }

        if (bluetoothAdapter?.isEnabled == false) {
            controllerListener?.onError("Bluetoothが無効です。設定からBluetoothを有効にしてください。")
            return
        }

        val device = try {
            bluetoothAdapter?.getRemoteDevice(normalizedMac)
        } catch (e: IllegalArgumentException) {
            controllerListener?.onError("MACアドレスエラー: ${e.message}\n形式例: AA:BB:CC:DD:EE:FF")
            return
        } catch (e: SecurityException) {
            controllerListener?.onError("Bluetooth権限エラー: ${e.message}")
            return
        } catch (e: Exception) {
            controllerListener?.onError("デバイス取得エラー: ${e.message}")
            return
        }

        if (device == null) {
            controllerListener?.onError("デバイスが見つかりません: $normalizedMac")
            return
        }

        Log.d(TAG, "Connecting to: $normalizedMac")
        controllerListener?.onStateChanged("接続準備中...")

        hidExecutor.execute {
            connectToDevice(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device

        getHidDeviceProxy { hidDevice ->
            if (hidDevice == null) {
                controllerListener?.onError("HID Deviceプロキシが利用できません。Bluetoothを再起動してください。")
                return@getHidDeviceProxy
            }

            if (appRegistered) {
                try {
                    hidDevice.connect(device)
                    controllerListener?.onStateChanged("接続中...")
                } catch (e: Exception) {
                    Log.e(TAG, "Connect error: ${e.message}", e)
                    controllerListener?.onError("接続エラー: ${e.message}")
                }
                return@getHidDeviceProxy
            }

            try {
                val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                    "Switch Controller",
                    "Nintendo Switch Controller",
                    "Nintendo",
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD.toByte(),
                    buildHidDescriptor()
                )

                val qosSettings = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                    QOS_TOKEN_RATE,
                    QOS_TOKEN_BUCKET_SIZE,
                    QOS_PEAK_BANDWIDTH,
                    QOS_LATENCY,
                    QOS_DELAY_VARIATION
                )

                val registered = hidDevice.registerApp(
                    sdpSettings,
                    null,
                    qosSettings,
                    hidExecutor,
                    object : BluetoothHidDevice.Callback() {
                        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                            appRegistered = registered
                            Log.d(TAG, "App status changed: registered=$registered")

                            if (registered && targetDevice != null) {
                                try {
                                    hidDevice.connect(targetDevice!!)
                                    controllerListener?.onStateChanged("接続中...")
                                    Log.d(TAG, "Initiating connection to ${targetDevice!!.address}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Connect call failed: ${e.message}", e)
                                    controllerListener?.onError("接続開始エラー: ${e.message}")
                                }
                            } else if (!registered) {
                                controllerListener?.onError("HIDアプリの登録が解除されました")
                            }
                        }

                        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                            when (state) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    deviceConnected = true
                                    connectedDevice = device
                                    controllerListener?.onConnected(device)
                                    Log.d(TAG, "Connected to ${device.address}")
                                }
                                BluetoothProfile.STATE_CONNECTING -> {
                                    controllerListener?.onStateChanged("接続中...")
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    deviceConnected = false
                                    connectedDevice = null
                                    controllerListener?.onDisconnected()
                                    Log.d(TAG, "Disconnected from ${device.address}")
                                }
                                BluetoothProfile.STATE_DISCONNECTING -> {
                                    controllerListener?.onStateChanged("切断中...")
                                }
                            }
                        }

                        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {}
                        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {}
                        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {}
                    }
                )

                if (registered) {
                    controllerListener?.onStateChanged("HIDアプリ登録中...")
                } else {
                    controllerListener?.onError("HID App登録に失敗しました。Bluetoothを再起動してください。")
                }

            } catch (e: SecurityException) {
                controllerListener?.onError("権限エラー: Bluetooth権限を確認してください")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                controllerListener?.onError("接続エラー: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendControllerInput(buttonData: ByteArray) {
        if (!deviceConnected || connectedDevice == null || bluetoothHidDevice == null) {
            Log.w(TAG, "デバイスに接続していません")
            return
        }

        try {
            bluetoothHidDevice!!.sendReport(connectedDevice!!, 0, buttonData)
        } catch (e: Exception) {
            Log.e(TAG, "Input送信エラー: ${e.message}", e)
        }
    }

    /**
     * ✅ Fix3: リリースレポートを hidExecutor 上でスリープ後に送信する。
     * Handler(mainLooper) に投げると UI スレッドの遅延でリリースが遅れたり、
     * 順序が前後する場合があるため、Bluetooth 専用スレッドで完結させる。
     */
    fun scheduleRelease(releaseReport: ByteArray, delayMs: Long) {
        hidExecutor.execute {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {}
            sendControllerInput(releaseReport)
        }
    }

    /**
     * Nintendo Switch用 HID Descriptor
     * 18ボタン (フェース/ショルダー/システム/十字キー) + 左右スティック
     *
     * レポート構造 (7バイト):
     *   Byte 0 : ボタン  1-8  (B, A, Y, X, L, R, ZL, ZR)
     *   Byte 1 : ボタン  9-16 (MINUS, PLUS, L3, R3, HOME, CAPTURE, DPAD_UP, DPAD_DOWN)
     *   Byte 2 : ボタン 17-18 + パディング6bit (DPAD_LEFT, DPAD_RIGHT)
     *   Byte 3 : 左スティック X (0〜255, 中立=128)
     *   Byte 4 : 左スティック Y (0〜255, 中立=128)
     *   Byte 5 : 右スティック X (0〜255, 中立=128)
     *   Byte 6 : 右スティック Y (0〜255, 中立=128)
     *
     * ✅ Fix1: スティック Input を 0x06(Relative) → 0x02(Absolute) に修正
     *    Relative だと Switch がゲームパッドと認識せず全入力が無視される
     * ✅ Fix2: スティック値域を signed(-127〜127) → unsigned(0〜255) に修正
     *    Switch HID は 0-255 unsigned, 中立=128 を期待する
     */
    private fun buildHidDescriptor(): ByteArray {
        return byteArrayOf(
            0x05, 0x01,                         // Usage Page (Generic Desktop)
            0x09, 0x05,                         // Usage (Gamepad)
            0xa1.toByte(), 0x01,                // Collection (Application)

            // --- ボタン 18個 ---
            0x15, 0x00,                         // Logical Minimum (0)
            0x25, 0x01,                         // Logical Maximum (1)
            0x75, 0x01,                         // Report Size (1)
            0x95.toByte(), 0x12,                // Report Count (18)
            0x05, 0x09,                         // Usage Page (Button)
            0x19, 0x01,                         // Usage Minimum (1)
            0x29, 0x12,                         // Usage Maximum (18)
            0x81.toByte(), 0x02,                // Input (Data, Variable, Absolute)

            // --- パディング 6ビット (3バイト境界に揃える) ---
            0x75, 0x01,
            0x95.toByte(), 0x06,
            0x81.toByte(), 0x03,                // Input (Constant)

            // --- 左スティック X/Y (unsigned 0-255, center=128) ---
            0x05, 0x01,                         // Usage Page (Generic Desktop)
            0x09, 0x30,                         // Usage (X)
            0x09, 0x31,                         // Usage (Y)
            0x15, 0x00,                         // Logical Minimum (0)       ✅ Fix2
            0x26, 0xff.toByte(), 0x00,          // Logical Maximum (255)     ✅ Fix2
            0x75, 0x08,                         // Report Size (8)
            0x95.toByte(), 0x02,                // Report Count (2)
            0x81.toByte(), 0x02,                // Input (Data, Variable, Absolute) ✅ Fix1

            // --- 右スティック Z/Rz (unsigned 0-255, center=128) ---
            0x09, 0x32,                         // Usage (Z)
            0x09, 0x35,                         // Usage (Rz)
            0x15, 0x00,                         // Logical Minimum (0)       ✅ Fix2
            0x26, 0xff.toByte(), 0x00,          // Logical Maximum (255)     ✅ Fix2
            0x75, 0x08,                         // Report Size (8)
            0x95.toByte(), 0x02,                // Report Count (2)
            0x81.toByte(), 0x02,                // Input (Data, Variable, Absolute) ✅ Fix1

            0xc0.toByte()                       // End Collection
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        targetDevice = null
        hidExecutor.execute {
            try {
                connectedDevice?.let { bluetoothHidDevice?.disconnect(it) }
                bluetoothHidDevice?.unregisterApp()
                deviceConnected = false
                connectedDevice = null
                appRegistered = false
                controllerListener?.onDisconnected()
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}", e)
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun cleanup() {
        disconnect()
        hidExecutor.shutdown()
    }
}
