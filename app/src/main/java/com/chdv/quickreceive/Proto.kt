package com.chdv.quickreceive

import java.io.ByteArrayOutputStream

/**
 * A tiny protobuf encoder/decoder: just enough for the Quick Share messages.
 * Field numbers follow the .proto files of Nearby Connections / Nearby Sharing.
 */
class PB {
    private val out = ByteArrayOutputStream()

    private fun varint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun int(field: Int, v: Long): PB { tag(field, 0); varint(v); return this }
    fun int(field: Int, v: Int): PB = int(field, v.toLong())
    fun bool(field: Int, v: Boolean): PB = int(field, if (v) 1L else 0L)
    fun bytes(field: Int, v: ByteArray): PB { tag(field, 2); varint(v.size.toLong()); out.write(v); return this }
    fun str(field: Int, v: String): PB = bytes(field, v.toByteArray(Charsets.UTF_8))
    fun msg(field: Int, m: PB): PB = bytes(field, m.build())

    fun build(): ByteArray = out.toByteArray()
}

/** A decoded message: field number -> list of values (Long for numbers, ByteArray for length-delimited). */
class PM(data: ByteArray) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var p = 0
        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (p >= data.size) throw ProtoException("truncated varint")
                val b = data[p++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw ProtoException("varint too long")
            }
        }
        while (p < data.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> add(field, readVarint())
                1 -> {
                    if (p + 8 > data.size) throw ProtoException("truncated fixed64")
                    var v = 0L
                    for (i in 0 until 8) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
                    p += 8; add(field, v)
                }
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || p + len > data.size) throw ProtoException("truncated bytes")
                    add(field, data.copyOfRange(p, p + len)); p += len
                }
                5 -> {
                    if (p + 4 > data.size) throw ProtoException("truncated fixed32")
                    var v = 0L
                    for (i in 0 until 4) v = v or ((data[p + i].toLong() and 0xFF) shl (8 * i))
                    p += 4; add(field, v)
                }
                else -> throw ProtoException("unsupported wire type")
            }
        }
    }

    private fun add(f: Int, v: Any) { fields.getOrPut(f) { ArrayList() }.add(v) }

    fun has(f: Int) = fields.containsKey(f)
    fun long(f: Int, def: Long = 0): Long = (fields[f]?.lastOrNull() as? Long) ?: def
    fun int(f: Int, def: Int = 0): Int = long(f, def.toLong()).toInt()
    fun bytes(f: Int): ByteArray? = fields[f]?.lastOrNull() as? ByteArray
    fun str(f: Int): String? = bytes(f)?.toString(Charsets.UTF_8)
    fun msg(f: Int): PM? = bytes(f)?.let { PM(it) }
    fun msgs(f: Int): List<PM> = fields[f]?.mapNotNull { (it as? ByteArray)?.let { b -> PM(b) } } ?: emptyList()
}

class ProtoException(msg: String) : Exception(msg)

/** Field numbers and enum values used by the protocol. */
object F {
    // OfflineFrame
    const val OF_VERSION = 1; const val OF_V1 = 2
    // V1Frame (connections)
    const val V1_TYPE = 1; const val V1_CONN_REQUEST = 2; const val V1_CONN_RESPONSE = 3
    const val V1_PAYLOAD = 4; const val V1_BANDWIDTH = 5; const val V1_KEEP_ALIVE = 6; const val V1_DISCONNECTION = 7
    const val T_CONNECTION_REQUEST = 1; const val T_CONNECTION_RESPONSE = 2; const val T_PAYLOAD_TRANSFER = 3
    const val T_BANDWIDTH_UPGRADE = 4; const val T_KEEP_ALIVE = 5; const val T_DISCONNECTION = 6

    // ConnectionRequestFrame
    const val CR_ENDPOINT_ID = 1; const val CR_ENDPOINT_NAME = 2; const val CR_ENDPOINT_INFO = 6

    // PayloadTransferFrame
    const val PT_PACKET_TYPE = 1; const val PT_HEADER = 2; const val PT_CHUNK = 3; const val PT_CONTROL = 4
    const val PH_ID = 1; const val PH_TYPE = 2; const val PH_TOTAL = 3; const val PH_SENSITIVE = 4
    const val PC_FLAGS = 1; const val PC_OFFSET = 2; const val PC_BODY = 3
    const val PAYLOAD_BYTES = 1; const val PAYLOAD_FILE = 2
    const val PACKET_DATA = 1; const val PACKET_CONTROL = 2

    // Ukey2Message
    const val UK_TYPE = 1; const val UK_DATA = 2
    const val UK_ALERT = 1; const val UK_CLIENT_INIT = 2; const val UK_SERVER_INIT = 3; const val UK_CLIENT_FINISH = 4
    const val P256_SHA512 = 100

    // SecureMessage
    const val SM_HEADER_AND_BODY = 1; const val SM_SIGNATURE = 2
    const val HB_HEADER = 1; const val HB_BODY = 2
    const val H_SIG_SCHEME = 1; const val H_ENC_SCHEME = 2; const val H_IV = 5; const val H_PUBLIC_METADATA = 6

    // Sharing frames (wire_format.proto)
    const val SF_TYPE_INTRODUCTION = 1; const val SF_TYPE_RESPONSE = 2
    const val SF_TYPE_PAIRED_KEY_ENCRYPTION = 3; const val SF_TYPE_PAIRED_KEY_RESULT = 4; const val SF_TYPE_CANCEL = 6
    const val SV1_TYPE = 1; const val SV1_INTRODUCTION = 2; const val SV1_RESPONSE = 3
    const val SV1_PAIRED_KEY_ENCRYPTION = 4; const val SV1_PAIRED_KEY_RESULT = 5

    // Response status
    const val ACCEPT = 1; const val REJECT = 2; const val NOT_ENOUGH_SPACE = 3; const val UNSUPPORTED = 4
}
