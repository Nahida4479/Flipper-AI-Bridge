package com.flipperai.bridge

import java.io.ByteArrayOutputStream

object Pb {
    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun uint(field: Int, value: Long): ByteArray = tag(field, 0) + varint(value)

    fun bool(field: Int, value: Boolean): ByteArray = if (value) uint(field, 1) else ByteArray(0)

    fun bytes(field: Int, data: ByteArray): ByteArray = tag(field, 2) + varint(data.size.toLong()) + data

    fun str(field: Int, value: String): ByteArray = bytes(field, value.toByteArray(Charsets.UTF_8))

    class Reader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
        fun hasMore() = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalStateException("truncated varint")
        }

        fun next(): Field? {
            if (!hasMore()) return null
            val key = readVarint().toInt()
            val field = key ushr 3
            return when (key and 7) {
                0 -> Field(field, readVarint(), null)
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > end) throw IllegalStateException("truncated field")
                    val slice = data.copyOfRange(pos, pos + len)
                    pos += len
                    Field(field, 0, slice)
                }
                1 -> {
                    pos += 8
                    Field(field, 0, null)
                }
                5 -> {
                    pos += 4
                    Field(field, 0, null)
                }
                else -> throw IllegalStateException("unsupported wire type")
            }
        }
    }

    class Field(val number: Int, val value: Long, val data: ByteArray?)
}
