package ru.sodovaya.kble.bms

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

// Define the ANT command functions.
enum class AntCommandFuncs(val value: Int) {
    Status(0x01),
    DeviceInfo(0x02),
    WriteRegister(0x51)
}

// Calculate CRC16 (Modbus parameters: init=0xFFFF, xorOut=0x0000, reversed)
fun calcCRC16(data: ByteArray): ByteArray {
    var crc = 0xFFFF
    for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        for (i in 0 until 8) {
            crc = if ((crc and 1) != 0) {
                (crc shr 1) xor 0xA001
            } else {
                crc shr 1
            }
        }
    }
    return byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
}

// Build the ANT command frame.
// Starts with 0x7E, 0xA1 then the function, low/high addr, value,
// followed by the CRC (calculated on the frame from index 1) and the trailer 0xAA, 0x55.
fun antCommand(func: AntCommandFuncs, addr: Int, value: Int): ByteArray {
    val frame = ByteArray(6)
    frame[0] = 0x7e.toByte()
    frame[1] = 0xa1.toByte()
    frame[2] = func.value.toByte()
    frame[3] = (addr and 0xFF).toByte()
    frame[4] = ((addr shr 8) and 0xFF).toByte()
    frame[5] = value.toByte()
    val crc = calcCRC16(frame.sliceArray(1 until frame.size))
    return frame + crc + byteArrayOf(0xaa.toByte(), 0x55.toByte())
}

// Data classes for device info and battery sample.
data class DeviceInfo(
    val mnf: String,
    val model: String,
    val hwVersion: String,
    val swVersion: String,
    val name: String?,
    val sn: String?
)

data class BmsSample(
    val voltage: Float,
    val current: Float,
    val charge: Float,
    val capacity: Float,
    val cycleCapacity: Float,
    val soc: Int,
    val temperatures: List<Float>,
    val mosTemperature: Int,
    val switches: Map<String, Boolean>
)

// Class handling the BLE connection and ANT protocol communication.
class AntBt(private val context: Context, private val device: BluetoothDevice) {

    companion object {
        // The BLE characteristic UUID used for the ANT protocol.
        val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null

    // Used to wait for responses from BLE notifications.
    private var responseDeferred: CompletableDeferred<ByteArray>? = null

    // Buffer for assembling notification fragments.
    private var buffer = ByteArray(0)
    // Store cell voltages if needed.
    private var voltages: List<Int> = emptyList()

    // Used for awaiting connection and service discovery.
    private var connectDeferred: CompletableDeferred<Unit>? = null

    // BluetoothGattCallback to handle connection, service discovery, and notifications.
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("AntBMS", "onConnectionStateChange $newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Once connected, start service discovery.
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectDeferred?.completeExceptionally(Exception("Disconnected"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("AntBMS", "onServicesDiscovered")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Enable notifications on the ANT characteristic.
                val characteristic = findCharacteristic(gatt)
                if (characteristic != null) {
                    // Set the characteristic write type to WRITE_TYPE_NO_RESPONSE
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                    // Enable notifications locally...
                    gatt.setCharacteristicNotification(characteristic, true)
                    // ...and write the descriptor to enable notifications on the remote side.
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

                    // Optionally request a larger MTU (if your device supports it)
                    gatt.requestMtu(512)
                    // Note: It can be useful to wait for onDescriptorWrite before completing connectDeferred.
                    connectDeferred?.complete(Unit)
                } else {
                    connectDeferred?.completeExceptionally(Exception("Characteristic not found"))
                }
            } else {
                connectDeferred?.completeExceptionally(Exception("Service discovery failed"))
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_UUID) {
                val data = characteristic.value
                processNotification(data)
            }
        }
    }

    // Helper: Search for the characteristic within discovered services.
    private fun findCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        for (service in gatt.services) {
            val characteristic = service.getCharacteristic(CHAR_UUID)
            if (characteristic != null) {
                return characteristic
            }
        }
        return null
    }

    // Process incoming BLE notifications.
    // This mimics the Python _notification_handler:
    // – It accumulates data until a frame ending (0x55) is detected,
    // – Then checks the frame length and CRC.
    private fun processNotification(data: ByteArray) {
        // Append the new data to our buffer
        buffer += data

        // Continue processing while there is enough data in the buffer for at least a header
        while (buffer.isNotEmpty()) {
            // Check the header bytes; if these don’t match, discard the buffer
            if (buffer[0] != 0x7e.toByte() || buffer[1] != 0xa1.toByte()) {
                Log.w("AntBMS", "Buffer header mismatch. Clearing buffer.")
                buffer = ByteArray(0)
                return
            }

            // The length of the payload is in the 6th byte (index 5)
            val dataLen = buffer[5].toInt() and 0xFF
            // The full frame length: 6 header bytes + payload length + 4 (2 bytes CRC + 2 bytes trailer)
            val expectedFrameLen = 6 + dataLen + 4

            // If we don't yet have the full frame, break out and wait for more data.
            if (buffer.size < expectedFrameLen) {
                break
            }

            // Extract a full frame from the beginning of the buffer.
            val frame = buffer.sliceArray(0 until expectedFrameLen)
            // Remove the processed frame from the buffer
            buffer = if (buffer.size > expectedFrameLen) {
                buffer.sliceArray(expectedFrameLen until buffer.size)
            } else {
                ByteArray(0)
            }

            // Validate the trailer bytes (should be 0xAA, 0x55)
            if (frame[expectedFrameLen - 2] != 0xaa.toByte() || frame[expectedFrameLen - 1] != 0x55.toByte()) {
                Log.w("AntBMS", "Invalid trailer bytes. Discarding frame.")
                continue // Skip to the next frame, if any
            }

            // Validate the CRC.
            val computedCrc = calcCRC16(frame.sliceArray(1 until expectedFrameLen - 4))
            val expectedCrc = frame.sliceArray(expectedFrameLen - 4 until expectedFrameLen - 2)
            if (!computedCrc.contentEquals(expectedCrc)) {
                Log.w("AntBMS", "CRC mismatch. Discarding frame.")
                continue // Skip invalid frame
            }

            // At this point, we have a complete and valid frame.
            // If you're waiting for a response, complete your deferred.
            responseDeferred?.complete(frame)
        }
    }

    // Connect to the BLE device and enable notifications.
    suspend fun connect(): Boolean {
        if (bluetoothGatt == null) {
            connectDeferred = CompletableDeferred()
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            return try {
                connectDeferred?.await()
                Log.d("AntBMS", "Connected")
                true
            } catch (e: Exception) {
                false
            }
        } else return true
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
    }

    // Write a command to the BLE characteristic and await the response from notifications.
    private suspend fun writeCommandAndAwaitResponse(cmd: ByteArray): ByteArray {
        val gatt = bluetoothGatt ?: throw IllegalStateException("Not connected")
        val characteristic = findCharacteristic(gatt) ?: throw IllegalStateException("Characteristic not found")

        // Set the write type to "write without response" as required by the device.
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        responseDeferred = CompletableDeferred()
        characteristic.value = cmd
        Log.d("AntBMS", "Writing")
        val writeResult = gatt.writeCharacteristic(characteristic)
        if (!writeResult) {
            throw IllegalStateException("Write failed")
        }
        return responseDeferred!!.await()
    }

    // Fetch device information.
    // Sends a DeviceInfo command (0x02 with address 0x026c and value 0x20)
    // and decodes the returned hardware and software strings.
    suspend fun fetchDeviceInfo(): DeviceInfo {
        val cmd = antCommand(AntCommandFuncs.DeviceInfo, 0x026c, 0x20)
        val response = writeCommandAndAwaitResponse(cmd)
        val hwBytes = response.sliceArray(6 until 6 + 16)
        val hw = hwBytes.toString(Charsets.UTF_8).trim { it <= ' ' }
        val swBytes = response.sliceArray(22 until 22 + 16)
        val sw = swBytes.toString(Charsets.UTF_8).trim { it <= ' ' }
        return DeviceInfo(
            mnf = "ANT",
            model = "ANT-$hw",
            hwVersion = hw,
            swVersion = sw,
            name = null,
            sn = null
        )
    }

    // Fetch the BMS sample status.
    // This sends a Status command (0x01 with address 0x0000 and value 0xbe)
    // and parses cell voltages, temperatures, current, SOC, etc.
    suspend fun fetch(): BmsSample {
        val cmd = antCommand(AntCommandFuncs.Status, 0x0000, 0xbe)
        val response = writeCommandAndAwaitResponse(cmd)
        // Helper functions for reading integers from the response.
        fun u16(i: Int): Int =
            ((response[i].toInt() and 0xFF) or ((response[i + 1].toInt() and 0xFF) shl 8))
        fun i16(i: Int): Int {
            val v = u16(i)
            return if (v >= 0x8000) v - 0x10000 else v
        }
        fun u32(i: Int): Long =
            (response[i].toLong() and 0xFF) or
            ((response[i + 1].toLong() and 0xFF) shl 8) or
            ((response[i + 2].toLong() and 0xFF) shl 16) or
            ((response[i + 3].toLong() and 0xFF) shl 24)

        val numTemp = response[8].toInt() and 0xFF
        val numCell = response[9].toInt() and 0xFF
        var offset = 34
        val cellVoltages = mutableListOf<Int>()
        for (i in 0 until numCell) {
            cellVoltages.add(u16(offset + i * 2))
        }
        voltages = cellVoltages
        offset += numCell * 2
        val temperatures = mutableListOf<Float>()
        for (i in 0 until numTemp) {
            val t = u16(offset + i * 2)
            temperatures.add(if (t != 65496) t.toFloat() else Float.NaN)
        }
        offset += numTemp * 2
        val mosTemp = u16(offset)
        offset += 2
        offset += 2 // Skip balancer temperature.
        val voltage = u16(offset) * 0.01f
        offset += 2
        val current = i16(offset) * 0.1f
        offset += 2
        val soc = u16(offset)
        offset += 2
        offset += 2 // Skip SOH.
        val switchDsg = response[offset].toInt() and 0xFF
        offset += 1
        val switchChg = response[offset].toInt() and 0xFF
        offset += 1
        offset += 1 // Skip balance state.
        offset += 1 // Reserved.
        val capacity = u32(offset) * 0.000001f
        offset += 4
        val charge = u32(offset) * 0.000001f
        offset += 4
        val cycleCharge = u32(offset) * 0.001f
        offset += 4

        return BmsSample(
            voltage = voltage,
            current = current,
            charge = charge,
            capacity = capacity,
            cycleCapacity = cycleCharge,
            soc = soc,
            temperatures = temperatures,
            mosTemperature = mosTemp,
            switches = mapOf(
                "discharge" to (switchDsg == 1),
                "charge" to (switchChg == 1)
            )
        )
    }

    // Return the stored cell voltages.
    suspend fun fetchVoltages(): List<Int> {
        return voltages
    }

    suspend fun setSwitch(switch: String, state: Boolean) {
        val registerOnOff = mapOf(
            "charge" to Pair(0x0006, 0x0004),
            "discharge" to Pair(0x0003, 0x0001),
            "balance" to Pair(0x000D, 0x000E),
            "buzzer" to Pair(0x001E, 0x001F)
        )
        val addrPair = registerOnOff[switch] ?: throw IllegalArgumentException("Unknown switch: $switch")
        val addr = if (state) addrPair.first else addrPair.second
        val cmd = antCommand(AntCommandFuncs.WriteRegister, addr, 0)
        writeCommandAndAwaitResponse(cmd)
    }
}
