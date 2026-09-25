package com.bquelhas.steer

import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WatchMessage] must render the SAME tuples for both transports — the watch parses them the
 * same way whichever one delivered the message — and [asLong] must read every integer width
 * PebbleKit 2 can hand us from the watch.
 */
class WatchMessageTest {

    private fun sample() = WatchMessage().apply {
        addInt32(NavKeys.NAV_TURN, -7)
        addUint8(NavKeys.NAV_TEXT_BEGIN, 1.toByte())
        addString(NavKeys.NAV_TEXT, "Turn left onto Rua Augusta")
        addBytes(NavKeys.NAV_ICON_BITMAP, byteArrayOf(0, 1, -1))
        addUint32(NavKeys.NAV_BG_COLOR, 0xFFAA00)
        addUint8(NavKeys.NAV_FAV_ICON, 200.toByte())   // > 127: must stay unsigned
    }

    @Test
    fun pk2RenderingKeepsKeysAndWireTypes() {
        val pk2 = sample().toPk2()
        assertEquals(PebbleDictionaryItem.Int32(-7), pk2[NavKeys.NAV_TURN.toUInt()])
        assertEquals(PebbleDictionaryItem.UInt8(1u), pk2[NavKeys.NAV_TEXT_BEGIN.toUInt()])
        assertEquals(PebbleDictionaryItem.Text("Turn left onto Rua Augusta"), pk2[NavKeys.NAV_TEXT.toUInt()])
        assertEquals(PebbleDictionaryItem.Bytes(byteArrayOf(0, 1, -1)), pk2[NavKeys.NAV_ICON_BITMAP.toUInt()])
        assertEquals(PebbleDictionaryItem.UInt32(0xFFAA00u), pk2[NavKeys.NAV_BG_COLOR.toUInt()])
        assertEquals(PebbleDictionaryItem.UInt8(200u), pk2[NavKeys.NAV_FAV_ICON.toUInt()])
        assertEquals(6, pk2.size)
    }

    @Test
    fun classicRenderingCarriesTheSameValues() {
        val classic = sample().toClassic()
        assertEquals(-7L, classic.getInteger(NavKeys.NAV_TURN))
        assertEquals(1L, classic.getUnsignedIntegerAsLong(NavKeys.NAV_TEXT_BEGIN))
        assertEquals("Turn left onto Rua Augusta", classic.getString(NavKeys.NAV_TEXT))
        assertArrayEquals(byteArrayOf(0, 1, -1), classic.getBytes(NavKeys.NAV_ICON_BITMAP))
        assertEquals(0xFFAA00L, classic.getUnsignedIntegerAsLong(NavKeys.NAV_BG_COLOR))
        // Classic PebbleDictionary keeps a uint8 as the signed byte (-56); the byte on the wire is
        // the same 0xC8 either way, which the watch reads as 200.
        assertEquals(200L, classic.getUnsignedIntegerAsLong(NavKeys.NAV_FAV_ICON)!! and 0xFF)
        assertEquals(6, classic.size())
    }

    @Test
    fun laterAddOfTheSameKeyReplacesTheValue() {
        val msg = WatchMessage().apply {
            addUint8(NavKeys.NAV_TEXT_SIZE, 0.toByte())
            addUint8(NavKeys.NAV_TEXT_SIZE, 1.toByte())
        }
        assertEquals(mapOf(NavKeys.NAV_TEXT_SIZE.toUInt() to PebbleDictionaryItem.UInt8(1u)), msg.toPk2())
        assertEquals(1L, msg.toClassic().getUnsignedIntegerAsLong(NavKeys.NAV_TEXT_SIZE))
    }

    @Test
    fun asLongReadsEveryIntegerWidth() {
        assertEquals(5L, PebbleDictionaryItem.UInt8(5u).asLong())
        assertEquals(255L, PebbleDictionaryItem.UInt8(255u).asLong())
        assertEquals(-3L, PebbleDictionaryItem.Int8((-3).toByte()).asLong())
        assertEquals(65535L, PebbleDictionaryItem.UInt16(65535).asLong())
        assertEquals(-300L, PebbleDictionaryItem.Int16((-300).toShort()).asLong())
        assertEquals(12L, PebbleDictionaryItem.Int32(12).asLong())
        assertEquals(4294967295L, PebbleDictionaryItem.UInt32(4294967295L).asLong())
        assertNull(PebbleDictionaryItem.Text("3").asLong())
        assertNull(PebbleDictionaryItem.Bytes(byteArrayOf(3)).asLong())
    }

    @Test
    fun triggerRouteReadsTheSameWhateverWidthArrives() {
        // The watch writes uint8 for NAV_TRIGGER_ROUTE / NAV_ROUTE_MODE; the Pebble app may pass
        // it through as UInt8 or widen it to UInt32/Int32 (what the library documents).
        for (item in listOf(
            PebbleDictionaryItem.UInt8(4u),
            PebbleDictionaryItem.UInt32(4L),
            PebbleDictionaryItem.Int32(4),
        )) {
            val data = mapOf(NavKeys.NAV_TRIGGER_ROUTE.toUInt() to item)
            val readInt: (Int) -> Long? = { key -> data[key.toUInt()]?.asLong() }
            assertEquals(4L, readInt(NavKeys.NAV_TRIGGER_ROUTE))
            assertNull(readInt(NavKeys.NAV_REQUEST_FAVS))
        }
    }
}
