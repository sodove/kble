@file:OptIn(ExperimentalStdlibApi::class)

package ru.sodovaya.kble.utils

import android.util.Log
import ru.sodovaya.kble.service.ScooterData

data class ControllerData(
    val throttle: Int = 0,
    val brakePedal: Int = 0,
    val switchBrake: Int = 0,
    val switchForward: Int = 0,
    val switchFoot: Int = 0,
    val switchReverse: Int = 0,
    val switchHallA: Int = 0,
    val switchHallB: Int = 0,
    val switchHallC: Int = 0,
    val voltageBattery: Int = 0,
    val temperatureMotor: Int = 0,
    val temperatureController: Int = 0,
    val directionSetting: Int = 0,
    val directionActual: Int = 0,
    val motorSpeed: Int = 0,
    val phaseCurrent: Int = 0
)

fun ControllerData.toScooterData(): ScooterData {
    Log.d("ContrData", this.toString())
    return ScooterData(
        isConnected = "Maybe connected",
        voltage = voltageBattery.toDouble(),
        speed = rpmToSpeed(motorSpeed),
        battery = 100,
        amperage = phaseCurrent.toDouble(),
        temperature = temperatureController
    )
}

fun ParseScooterData(controllerData: ControllerData = ControllerData(), value: ByteArray): ControllerData? {
    return when (value[0]) {
        0x3A.toByte() -> unpackPacketA(value, controllerData).also {
            Log.i("KellyParser", "Parsing 0x3A data")
        }
        0x3B.toByte() -> unpackPacketB(value, controllerData).also {
            Log.i("KellyParser", "Parsing 0x3B data")
        }
        else -> {
            Log.w("KellyParser", "Something is wrong in received bytearray")
            Log.w("KellyParser", value.toHexString(HexFormat.Default))
            null
        }
    }
}

fun unpackPacketA(raw: ByteArray, controllerData: ControllerData): ControllerData? {
    if (raw.size == 19 && raw[0] == 0x3A.toByte() && raw[18] == getChecksum(raw)) {
        val throttle = raw[2].toInt()
        val brakePedal = raw[3].toInt()
        val switchBrake = raw[4].toInt()
        val switchForward = raw[5].toInt()
        val switchFoot = raw[6].toInt()
        val switchReverse = raw[7].toInt()
        val switchHallA = raw[8].toInt()
        val switchHallB = raw[9].toInt()
        val switchHallC = raw[10].toInt()
        val voltageBattery = raw[11].toInt()
        val temperatureMotor = raw[12].toInt()
        val temperatureController = raw[13].toInt()
        val directionSetting = raw[14].toInt()
        val directionActual = raw[15].toInt()
        return controllerData.copy(
            throttle = throttle,
            brakePedal = brakePedal,
            switchBrake = switchBrake,
            switchForward = switchForward,
            switchFoot = switchFoot,
            switchReverse = switchReverse,
            switchHallA = switchHallA,
            switchHallB = switchHallB,
            switchHallC = switchHallC,
            voltageBattery = voltageBattery,
            temperatureMotor = temperatureMotor,
            temperatureController = temperatureController,
            directionSetting = directionSetting,
            directionActual = directionActual,
        )
    }
    return null
}

fun unpackPacketB(raw: ByteArray, controllerData: ControllerData): ControllerData? {
    if (raw.size == 19 && raw[0] == 0x3B.toByte() && raw[18] == getChecksum(raw)) {
        val motorSpeed = raw[5].toUByte().toInt() + (raw[4].toUByte().toInt() * 255)
        val phaseCurrent = raw[7].toUByte().toInt() + (raw[6].toUByte().toInt() * 255)
        return controllerData.copy(
            motorSpeed = motorSpeed,
            phaseCurrent = phaseCurrent
        )
    }
    return null
}

private fun getChecksum(raw: ByteArray): Byte {
    var checksum = 0
    for (i in 0 until raw.size - 1) {
        checksum += raw[i].toInt()
        if (checksum > 0xFF) {
            checksum -= 0xFF
        }
    }
    return checksum.toByte()
}