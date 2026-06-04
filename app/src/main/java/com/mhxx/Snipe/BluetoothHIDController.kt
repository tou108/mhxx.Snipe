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
 * 🔧 修正版: クラッシュ修正 + 接続ロジック修正
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

        // 🔧 追加: MACアドレスの正規表現
        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var connectedDevice: BluetoothDevice? = null
    private var targetDevice: BluetoothDevice? = null   // 🔧 追加: 接続先デバイスを保持
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

    /**
     * 🔧 追加: MACアドレスのバリデーション
     */
    private fun isValidMacAddress(mac: String): Boolean {
        return MAC_REGEX.matches(mac)
    }

    /**
     * 🔧 追加: MACアドレスの正規化（コロン区切り・大文字に統一）
     */
    private fun normalizeMacAddress(mac: String): String {
        val trimmed = mac.trim()
        // ダッシュをコロンに置換して大文字に
        return trimmed.replace("-", ":").uppercase()
    }

    /**
     * Bluetooth HID デバイスプロキシを取得
     */
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

    /**
     * 🔧 修正: Nintendo Switch に接続 (MAC アドレスで指定)
     * - MACアドレスのバリデーション追加
     * - try-catch でクラッシュを防止
     */
    @SuppressLint("MissingPermission")
    fun connectToSwitch(macAddress: String) {
        if (!hasBluetoothPermissions()) {
            controllerListener?.onError("Bluetooth権限がありません。アプリの権限設定を確認してください。")
            return
        }

        // 🔧 MACアドレスを正規化
        val normalizedMac = normalizeMacAddress(macAddress)

        // 🔧 MACアドレス形式チェック（クラッシュの原因）
        if (!isValidMacAddress(normalizedMac)) {
            controllerListener?.onError(
                "無効なMACアドレス形式です: \"$macAddress\"\n" +
                "正しい形式: AA:BB:CC:DD:EE:FF"
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

        // 🔧 getRemoteDevice を try-catch で保護（クラッシュの主原因）
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

    /**
     * 🔧 修正: デバイスに直接接続
     * - connect() 呼び出しを追加（元のコードでは接続処理が不完全だった）
     */
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        targetDevice = device  // 接続先を保持

        getHidDeviceProxy { hidDevice ->
            if (hidDevice == null) {
                controllerListener?.onError("HID Deviceプロキシが利用できません。Bluetoothを再起動してください。")
                return@getHidDeviceProxy
            }

            // 既に登録済みの場合は再登録せずに接続
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
                    (BluetoothHidDevice.SUBCLASS1_GAMEPAD.toInt()).toByte(),
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
                                // 🔧 修正: アプリ登録後に明示的にconnect()を呼ぶ（元のコードで欠けていた重要な処理）
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
                                    Log.d(TAG, "Connecting to ${device.address}")
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    deviceConnected = false
                                    connectedDevice = null
                                    controllerListener?.onDisconnected()
                                    Log.d(TAG, "Disconnected from ${device.address}")
                                }
                                BluetoothProfile.STATE_DISCONNECTING -> {
                                    controllerListener?.onStateChanged("切断中...")
                                    Log.d(TAG, "Disconnecting from ${device.address}")
                                }
                            }
                        }

                        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                            Log.d(TAG, "onGetReport: type=$type, id=$id")
                        }

                        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                            Log.d(TAG, "onSetReport: type=$type, id=$id")
                        }

                        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
                            Log.d(TAG, "onInterruptData: reportId=$reportId")
                        }
                    }
                )

                if (registered) {
                    Log.d(TAG, "registerApp() called successfully, waiting for onAppStatusChanged...")
                    controllerListener?.onStateChanged("HIDアプリ登録中...")
                } else {
                    controllerListener?.onError("HID App登録に失敗しました。Bluetoothを再起動してください。")
                }

            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error: ${e.message}", e)
                controllerListener?.onError("権限エラー: Bluetooth権限を確認してください")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                controllerListener?.onError("接続エラー: ${e.message}")
            }
        }
    }

    /**
     * コントローラー入力を送信
     */
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
     * Nintendo Switch用 HID Descriptor
     */
    private fun buildHidDescriptor(): ByteArray {
        return byteArrayOf(
            0x05, 0x01,
            0x09, 0x05,
            0xa1.toByte(), 0x01,
            0x15, 0x00,
            0x25, 0x01,
            0x35, 0x00,
            0x45, 0x01,
            0x75, 0x01,
            0x95.toByte(), 0x0e,
            0x05, 0x09,
            0x19, 0x01,
            0x29, 0x0e,
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x02,
            0x81.toByte(), 0x01,
            0x05, 0x01,
            0x09, 0x30,
            0x09, 0x31,
            0x15, 0x81.toByte(),
            0x25, 0x7f,
            0x75, 0x08,
            0x95.toByte(), 0x02,
            0x81.toByte(), 0x06,
            0xc0.toByte()
        )
    }

    /**
     * 接続解除
     */
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
                Log.d(TAG, "Disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}", e)
            }
        }
    }

    /**
     * Bluetooth権限チェック
     */
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

    /**
     * リソース解放
     */
    fun cleanup() {
        disconnect()
        hidExecutor.shutdown()
    }
}
