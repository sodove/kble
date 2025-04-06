package ru.sodovaya.kble.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.sodovaya.kble.R
import ru.sodovaya.kble.bms.AntBt
import ru.sodovaya.kble.settings.ServiceSettingsPreferences
import ru.sodovaya.kble.settings.ServiceSettingsState
import ru.sodovaya.kble.utils.ControllerData
import ru.sodovaya.kble.utils.ParseScooterData
import ru.sodovaya.kble.utils.READ_UUID
import ru.sodovaya.kble.utils.SEND_UUID
import ru.sodovaya.kble.utils.SERVICE_UUID
import ru.sodovaya.kble.utils.convertToPercentage
import ru.sodovaya.kble.utils.toScooterData
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.timer
import kotlin.math.roundToInt

class BmsManager(private val context: Context, private val device: BluetoothDevice?) {
    private var antBms: AntBt? = null
    var currentVoltage = 0.0
    var currentAmperage = 0.0

    fun connectBms() {
        device ?: return
        antBms = AntBt(context, device)
    }

    suspend fun updateBmsData() = runCatching {
        if (antBms?.connect() == true) {
            val sample = antBms!!.fetch()
            currentVoltage = sample.voltage.toDouble()
            currentAmperage = sample.current.toDouble()
        }
    }.onFailure {
        Log.e("BmsManager", "Ошибка BMS: ${it.message}")
    }

    fun disconnectBms() {
        antBms?.disconnect()
    }
}

class KellyManager(private val context: Context, private val device: BluetoothDevice) {
    var controllerData = ControllerData()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    fun connect(onConnected: () -> Unit) {
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) close()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                writeChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(SEND_UUID)
                if (writeChar == null) {
                    close(); return
                }
                enableNotifications(gatt)
                onConnected()
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray) {
                if (c.uuid == READ_UUID) {
                    ParseScooterData(controllerData, data)?.let { updated -> controllerData = updated }
                }
            }
        })
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val readChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(READ_UUID) ?: return
        gatt.setCharacteristicNotification(readChar, true)
        val desc = readChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }
    }

    suspend fun sendCommands() {
        val cmds = listOf(byteArrayOf(0x3A, 0x00, 0x3A), byteArrayOf(0x3B, 0x00, 0x3B))
        val localGatt = gatt ?: return
        writeChar ?: return
        cmds.forEach {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                localGatt.writeCharacteristic(writeChar!!, it, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                writeChar!!.value = it
                writeChar!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                localGatt.writeCharacteristic(writeChar!!)
            }
            delay(50)
        }
    }

    fun close() {
        gatt?.close()
        gatt = null
    }
}

class GpsSpeedManager(private val context: Context) : LocationListener {
    var gpsSpeed = 0f
    private var locationManager: LocationManager? = null

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this)
        }
    }

    fun stopLocationUpdates() {
        locationManager?.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        gpsSpeed = location.speed * 3.6f
    }
}

class BluetoothForegroundService : Service() {
    private val bluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var bmsManager: BmsManager? = null
    private var kellyManager: KellyManager? = null
    private var gpsManager: GpsSpeedManager? = null

    private var isAlive = false
    private var noiseTimer: Timer? = null
    private var volumeTimer: Timer? = null
    private var settings = ServiceSettings()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settingsState = ServiceSettingsState(ServiceSettings(), null)
        settings = ServiceSettingsPreferences(this, settingsState).loadServiceSettings()

        val deviceAddress: String? = intent?.getStringExtra("device")

        if (deviceAddress != null){
            isAlive = true
            setupWakelock()

            startForeground(1, createNotification())
            connectKellyAndBms(deviceAddress)
            setupGpsSpeed()
            volumeThreadWorker()
            broadcastThread()
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "BLUETOOTH_SERVICE_CHANNEL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "kble Service", NotificationManager.IMPORTANCE_LOW)
            channel.description = "kble Service"
            channel.enableVibration(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("kble")
            .setContentText("Сервис работает")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun connectKellyAndBms(deviceAddress: String) {
        val kellyDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
        kellyDevice?.let {
            kellyManager = KellyManager(this, it).apply {
                connect {
                    noiseTimer?.cancel()
                    noiseTimer = timer(period = 100) { runBlocking { sendCommands() } }
                }
            }
        }

        val bmsDevice = bluetoothAdapter.getRemoteDevice("32:E3:2E:02:23:45")
        bmsManager = BmsManager(this, bmsDevice).apply { connectBms() }

        CoroutineScope(Dispatchers.IO).launch {
            while (isAlive) {
                delay(500)
                bmsManager?.updateBmsData()
            }
        }
    }

    private fun setupGpsSpeed() {
        gpsManager = GpsSpeedManager(this).apply { startLocationUpdates() }
    }

    private fun volumeThreadWorker() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        volumeTimer?.cancel()
        if (settings.volumeServiceEnabled) {
            volumeTimer = timer(period = 150) {
                val minimalVolume = settings.minimalVolume
                val maximumVolumeAt = settings.maximumVolumeAt
                val kellySpeed = kellyManager?.controllerData?.toScooterData()?.speed?.toFloat() ?: 0f
                val calcVolume = (convertToPercentage(kellySpeed, 0f, maximumVolumeAt) / 100f * maxVolume)
                    .coerceIn(minimalVolume, maxVolume.toFloat())
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, calcVolume.roundToInt(), 0)
            }
        }
    }

    private fun broadcastThread() {
        GlobalScope.launch(Dispatchers.Default) {
            while (isAlive) {
                val dataIntent = Intent("BluetoothData")
                val kData = kellyManager?.controllerData?.toScooterData()
                dataIntent.putExtra("kellySpeed", kData?.speed ?: 0f)
                dataIntent.putExtra("gpsSpeed", gpsManager?.gpsSpeed ?: 0f)
                dataIntent.putExtra("voltage", bmsManager?.currentVoltage ?: 0.0)
                dataIntent.putExtra("amperage", bmsManager?.currentAmperage ?: 0.0)
                sendBroadcast(dataIntent)
                delay(300)
            }
        }
    }

    private fun setupWakelock() {
        val wakelockLevel = when (settings.wakelockVariant) {
            WakelockVariant.KEEP_SCREEN_ON, WakelockVariant.HIDDEN_ALLOWED_CPU -> PowerManager.PARTIAL_WAKE_LOCK
            WakelockVariant.DISABLED -> 0
        }
        if (wakelockLevel != 0) {
            wakeLock?.release()
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(wakelockLevel, "EndlessService::lock")
                .apply { acquire() }
        }
    }

    override fun onDestroy() {
        isAlive = false
        noiseTimer?.cancel()
        volumeTimer?.cancel()
        kellyManager?.close()
        bmsManager?.disconnectBms()
        gpsManager?.stopLocationUpdates()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onDestroy()
    }
}
