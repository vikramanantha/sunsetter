package com.vikramanantha.sunsetter.ble

import java.util.UUID

/**
 * MeRGBW / LT-06 "Sunset lights" BLE protocol.
 * See https://github.com/SplitterBlue/mergbw-protocol and sunsetter/initial_plan.md for what was verified on the lamp.
 *
 * Command frame: 55 <cmd> FF <total_len> <payload...> <checksum>, checksum = ~sum(previous bytes).
 * Reply frame (notify FFF4): 56 <cmd> FF <total_len> <payload...> <checksum>.
 */
object Protocol {
    /** The lamp in my room. */
    const val LAMP_ADDRESS = "FF:10:10:B1:99:84"

    val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_STATUS = 0x00
    const val CMD_POWER = 0x01
    const val CMD_COLOR = 0x03
    const val CMD_BRIGHTNESS = 0x05
    const val CMD_SCENE = 0x06

    /** Brightness bytes above 100 wrap and come out dimmer, so always clamp. */
    const val BRIGHTNESS_MIN = 5
    const val BRIGHTNESS_MAX = 100

    private const val HEAD_COMMAND = 0x55
    private const val HEAD_REPLY = 0x56

    fun frame(cmd: Int, vararg payload: Int): ByteArray {
        val bytes = IntArray(5 + payload.size)
        bytes[0] = HEAD_COMMAND
        bytes[1] = cmd and 0xFF
        bytes[2] = 0xFF
        bytes[3] = bytes.size
        payload.forEachIndexed { i, b -> bytes[4 + i] = b and 0xFF }
        bytes[bytes.size - 1] = bytes.sum().inv() and 0xFF
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    fun status() = frame(CMD_STATUS)
    fun power(on: Boolean) = frame(CMD_POWER, if (on) 1 else 0)
    fun color(r: Int, g: Int, b: Int) = frame(CMD_COLOR, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    fun brightness(level: Int) = frame(CMD_BRIGHTNESS, level.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX))
    fun scene(index: Int) = frame(CMD_SCENE, index and 0xFF)

    sealed interface Reply {
        /** The lamp received command [cmd]; [ok] when the result byte is 00. */
        data class Ack(val cmd: Int, val ok: Boolean) : Reply

        /** Reply to [status]. Color and scene aren't reported, only power and brightness. */
        data class Status(val on: Boolean, val brightness: Int) : Reply
    }

    /** Parses a FFF4 notification, or null if it isn't a reply we understand. Reply checksums aren't checked. */
    fun parse(value: ByteArray): Reply? {
        val b = value.map { it.toInt() and 0xFF }
        if (b.size < 6 || b[0] != HEAD_REPLY) return null
        return when {
            b[1] == CMD_STATUS && b.size >= 7 -> Reply.Status(on = b[4] == 1, brightness = b[5])
            else -> Reply.Ack(cmd = b[1], ok = b[4] == 0)
        }
    }

    /** Scenes for device type 5. Sunset drives the lamp's separate amber emitter. */
    enum class Scene(val index: Int, val label: String) {
        Sunset(134, "Amber"),
        Sunrise(139, "Sunrise"),
        SummerSun(142, "Summer sun"),
    }
}
