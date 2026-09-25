package com.bquelhas.steer

import com.getpebble.android.kit.util.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

/**
 * One AppMessage for the watchapp, built once and rendered for whichever PebbleKit transport
 * ends up carrying it (see [PebbleEmitter]):
 *  - [toPk2] for PebbleKit 2 — Core / microPebble / Gravel talking to a watchapp build that
 *    declares `companionApp` in its package.json;
 *  - [toClassic] for the legacy broadcast API — the original Pebble app, and Core running an
 *    older watchapp build without `companionApp`.
 *
 * The add* methods mirror [PebbleDictionary] (same names, same Kotlin types), so the message
 * builders in [PebbleEmitter] read exactly as they did when they filled a PebbleDictionary.
 * Both renderings carry the same keys with the same wire types, so the watch parses them the
 * same way whichever transport delivered them.
 */
class WatchMessage {

    private sealed class Value {
        class Int32(val v: Int) : Value()
        class Uint8(val v: Byte) : Value()
        /** Raw 32 bits, as with [PebbleDictionary.addUint32]. */
        class Uint32(val v: Int) : Value()
        class Text(val v: String) : Value()
        class Bytes(val v: ByteArray) : Value()
    }

    // Insertion-ordered so both renderings list the tuples in the order they were added.
    private val items = LinkedHashMap<Int, Value>()

    fun addInt32(key: Int, value: Int) { items[key] = Value.Int32(value) }
    fun addUint8(key: Int, value: Byte) { items[key] = Value.Uint8(value) }
    fun addUint32(key: Int, value: Int) { items[key] = Value.Uint32(value) }
    fun addString(key: Int, value: String) { items[key] = Value.Text(value) }
    fun addBytes(key: Int, value: ByteArray) { items[key] = Value.Bytes(value) }

    fun toClassic(): PebbleDictionary = PebbleDictionary().also { dict ->
        for ((key, value) in items) when (value) {
            is Value.Int32 -> dict.addInt32(key, value.v)
            is Value.Uint8 -> dict.addUint8(key, value.v)
            is Value.Uint32 -> dict.addUint32(key, value.v)
            is Value.Text -> dict.addString(key, value.v)
            is Value.Bytes -> dict.addBytes(key, value.v)
        }
    }

    fun toPk2(): Map<UInt, PebbleDictionaryItem> = items.entries.associate { (key, value) ->
        key.toUInt() to when (value) {
            is Value.Int32 -> PebbleDictionaryItem.Int32(value.v)
            is Value.Uint8 -> PebbleDictionaryItem.UInt8(value.v.toUByte())
            is Value.Uint32 -> PebbleDictionaryItem.UInt32(value.v.toUInt())
            is Value.Text -> PebbleDictionaryItem.Text(value.v)
            is Value.Bytes -> PebbleDictionaryItem.Bytes(value.v)
        }
    }
}

/**
 * Integer value of a PebbleKit 2 dictionary item, whatever width it arrived as, or null for
 * text/bytes. The library documents that received numbers always come as Int32/UInt32, but
 * Core decodes the watch's typed tuples as they are on the wire (a watch `dict_write_uint8`
 * can arrive as UInt8), so every width is accepted.
 */
fun PebbleDictionaryItem.asLong(): Long? = when (this) {
    is PebbleDictionaryItem.Int8 -> value.toLong()
    is PebbleDictionaryItem.UInt8 -> value.toLong()
    is PebbleDictionaryItem.Int16 -> value.toLong()
    is PebbleDictionaryItem.UInt16 -> value.toLong()
    is PebbleDictionaryItem.Int32 -> value.toLong()
    is PebbleDictionaryItem.UInt32 -> value.toLong()
    is PebbleDictionaryItem.Text, is PebbleDictionaryItem.Bytes -> null
}
