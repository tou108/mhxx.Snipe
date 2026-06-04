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
 * JoyConDroid のBluetoothHID実装を参考に Kotlin で再実装
 */
class BluetoothHIDController(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHIDController"
        private const val NINTENDO_SWITCH = "Nintendo Switch"
        
        // HID設定
        private const val HID_PROFILE_TIME_OUT_SECONDS = 10
        private const val QOS_TOKEN_RATE = 21720
        private const val QOS_TOKEN_BUCKET_SIZE = 362
        private const val QOS_PEAK_BANDWIDTH = 21720
        private const val QOS_LATENCY = 16667
        private const val QOS_DELAY_VARIATION = 16667
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var hidExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var connectedDevice: BluetoothDevice? = null
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
     * Bluetooth HID デバイスプロキシを取得
     */
    @SuppressLint("MissingPermission")
    fun getHidDeviceProxy(callback: (BluetoothHidDevice?) -> Unit) {
        if (serviceConnected) {
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
     * Nintendo Switch に接続 (MAC アドレスで指定)
     */
    @SuppressLint("MissingPermission")
    fun connectToSwitch(macAddress: String) {
        if (!hasBluetoothPermissions()) {
            controllerListener?.onError("Bluetooth権限がありません")
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        if (device == null) {
            controllerListener?.onError("デバイスが見つかりません: $macAddress")
            return
        }

        hidExecutor.execute {
            connectToDevice(device)
        }
    }

    /**
     * デバイスに直接接続
     */
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        getHidDeviceProxy { hidDevice ->
            if (hidDevice == null) {
                controllerListener?.onError("HID Deviceプロキシが利用不可")
                return@getHidDeviceProxy
            }

            try {
                // HID SDP 設定
                val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                    "Switch Controller",  // name
                    "Nintendo Switch",     // description
                    "Nintendo",            // provider
                    // [修正1] Byte同士のorはIntになるので.toByte()で戻す
                    (BluetoothHidDevice.SUBCLASS1_KEYBOARD.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()).toByte(),
                    buildHidDescriptor()   // HID descriptor
                )

                // HID QoS 設定
                // [修正2] コンストラクタは6引数: (serviceType, tokenRate, tokenBucketSize, peakBandwidth, latency, delayVariation)
                val qosSettings = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,  // serviceType
                    QOS_TOKEN_RATE,        // tokenRate
                    QOS_TOKEN_BUCKET_SIZE, // tokenBucketSize
                    QOS_PEAK_BANDWIDTH,    // peakBandwidth
                    QOS_LATENCY,           // latency
                    QOS_DELAY_VARIATION    // delayVariation
                )

                // アプリを HID として登録
                if (hidDevice.registerApp(
                        sdpSettings,
                        null,
                        qosSettings,
                        hidExecutor,
                        // [修正3] abstract classは()でコンストラクタを呼ぶ必要がある
                        object : BluetoothHidDevice.Callback() {
                            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice, registered: Boolean) {
                                appRegistered = registered
                                Log.d(TAG, "App status changed: registered=$registered")
                            }

                            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                                when (state) {
                                    BluetoothProfile.STATE_CONNECTED -> {
                                        deviceConnected = true
                                        connectedDevice = device
                                        controllerListener?.onConnected(device)
                                        Log.d(TAG, "Connected to ${device.address}")
                                    }
                                    BluetoothProfile.STATE_DISCONNECTED -> {
                                        deviceConnected = false
                                        connectedDevice = null
                                        controllerListener?.onDisconnected()
                                        Log.d(TAG, "Disconnected from ${device.address}")
                                    }
                                }
                            }
                        }
                    )) {
                    Log.d(TAG, "HID App registered successfully")
                    controllerListener?.onStateChanged("接続中...")
                } else {
                    controllerListener?.onError("HID App登録に失敗")
                }

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
        // 標準的なゲームコントローラーディスクリプタ
        // [修正4] 127(0x7F)より大きい値は.toByte()が必要
        return byteArrayOf(
            0x05, 0x01,              // Usage Page (Generic Desktop)
            0x09, 0x05,              // Usage (Game Pad)
            0xa1.toByte(), 0x01,     // Collection (Application)
            0x15, 0x00,              // Logical Minimum (0)
            0x25, 0x01,              // Logical Maximum (1)
            0x35, 0x00,              // Physical Minimum (0)
            0x45, 0x01,              // Physical Maximum (1)
            0x75, 0x01,              // Report Size (1)
            0x95.toByte(), 0x0e,     // Report Count (14)
            0x05, 0x09,              // Usage Page (Button)
            0x19, 0x01,              // Usage Minimum (Button 1)
            0x29, 0x0e,              // Usage Maximum (Button 14)
            0x81.toByte(), 0x02,     // Input (Data, Variable, Absolute)
            0x95.toByte(), 0x02,     // Report Count (2)
            0x81.toByte(), 0x01,     // Input (Constant)
            0x05, 0x01,              // Usage Page (Generic Desktop)
            0x09, 0x30,              // Usage (X)
            0x09, 0x31,              // Usage (Y)
            0x15, 0x81.toByte(),     // Logical Minimum (-127)
            0x25, 0x7f,              // Logical Maximum (127)
            0x75, 0x08,              // Report Size (8)
            0x95.toByte(), 0x02,     // Report Count (2)
            0x81.toByte(), 0x06,     // Input (Data, Variable, Relative)
            0xc0.toByte()            // End Collection
        )
    }

    /**
     * 接続解除
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        hidExecutor.execute {
            try {
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
