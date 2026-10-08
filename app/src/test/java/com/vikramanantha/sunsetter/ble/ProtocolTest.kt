package com.vikramanantha.sunsetter.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected bytes are the ones verified against the lamp with nRF Connect. */
class ProtocolTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun commands() {
        assertEquals("5501FF0601A3", hex(Protocol.power(true)))
        assertEquals("5501FF0600A4", hex(Protocol.power(false)))
        assertEquals("5500FF05A6", hex(Protocol.status()))
        assertEquals("5503FF08FF0000A1", hex(Protocol.color(255, 0, 0)))
        assertEquals("5503FF080000FFA1", hex(Protocol.color(0, 0, 255)))
        assertEquals("5505FF06059B", hex(Protocol.brightness(5)))
        assertEquals("5505FF06643C", hex(Protocol.brightness(100)))
        assertEquals("5506FF068619", hex(Protocol.scene(134)))
    }

    @Test fun brightnessIsClamped() {
        assertEquals(hex(Protocol.brightness(100)), hex(Protocol.brightness(255)))
        assertEquals(hex(Protocol.brightness(5)), hex(Protocol.brightness(0)))
    }

    @Test fun parsesReplies() {
        assertEquals(Protocol.Reply.Status(on = false, brightness = 100), Protocol.parse(bytes("5600FF0F0064000000000000000046")))
        assertEquals(Protocol.Reply.Status(on = true, brightness = 100), Protocol.parse(bytes("5600FF0F0164000000000000000045")))
        assertEquals(Protocol.Reply.Status(on = true, brightness = 5), Protocol.parse(bytes("5600FF0F01050000000000000000A4")))
        assertEquals(Protocol.Reply.Ack(cmd = 1, ok = true), Protocol.parse(bytes("5601FF06004A")))
        assertEquals(Protocol.Reply.Ack(cmd = 6, ok = true), Protocol.parse(bytes("5606FF06008A")))
        assertEquals(null, Protocol.parse(bytes("5501FF0601A3")))
    }
}
