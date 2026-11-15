package com.chdv.quickreceive

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicInteger

/** What the sender wants to send. */
data class Incoming(
    val id: Int,
    val sender: String,
    val deviceType: Int,
    val pin: String,
    val files: List<FileMeta>,
    val text: TextMeta?,
) {
    val totalBytes: Long get() = files.sumOf { it.size } + (text?.size ?: 0)
}

data class FileMeta(val payloadId: Long, val name: String, val size: Long, val mime: String)
data class TextMeta(val payloadId: Long, val title: String, val type: Int, val size: Long)

/** A file that was saved. [uri] can be opened by other apps. */
data class Saved(val name: String, val size: Long, val uri: String, val mime: String, val location: String)

/** Where incoming files go. */
interface Storage {
    fun freeBytes(): Long
    fun create(name: String, mime: String, size: Long): Sink
}

interface Sink {
    val out: OutputStream
    fun finish(): Saved
    fun abort()
}

interface Events {
    fun onIncoming(conn: InboundConnection, info: Incoming)
    fun onProgress(conn: InboundConnection, info: Incoming, received: Long)
    fun onDone(conn: InboundConnection, info: Incoming, saved: List<Saved>, text: String?)
    fun onFailed(conn: InboundConnection, info: Incoming?, reason: String)
    fun log(msg: String) {}
}

/**
 * One incoming Quick Share connection (we are the receiving side, i.e. the "server").
 * Wi-Fi LAN medium: TCP, every message prefixed with a 4-byte big-endian length.
 */
class InboundConnection(
    private val socket: Socket,
    private val storage: Storage,
    private val events: Events,
) : Runnable {

    private enum class State { INITIAL, GOT_CONNECTION_REQUEST, SENT_SERVER_INIT, GOT_CLIENT_FINISH, ENCRYPTED, WAITING_FOR_USER, RECEIVING, DONE }

    val id = nextId.incrementAndGet()
    private val input = DataInputStream(socket.getInputStream().buffered(256 * 1024))
    private val output = DataOutputStream(socket.getOutputStream())
    @Volatile private var state = State.INITIAL
    @Volatile private var closed = false

    private var senderName = "מכשיר"
    private var senderType = 0
    private var clientInitRaw: ByteArray? = null
    private var serverInitRaw: ByteArray? = null
    private var commitment: ByteArray? = null
    private var keyPair: KeyPair? = null
    private var keys: SessionKeys? = null
    private var pin = ""
    private var serverSeq = 0
    private var clientSeq = 0

    private val byteBuffers = HashMap<Long, java.io.ByteArrayOutputStream>()
    private var info: Incoming? = null
    private val files = HashMap<Long, FileRx>()
    private val saved = ArrayList<Saved>()
    private var textPayloadId: Long? = null
    private var receivedText: String? = null
    private var received = 0L
    private var lastProgress = 0L

    private class FileRx(val meta: FileMeta, val sink: Sink) { var got = 0L }

    override fun run() {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 120_000
            while (!closed) {
                val len = input.readInt()
                if (len < 0 || len > MAX_FRAME) throw ProtoException("bad frame length $len")
                val frame = ByteArray(len)
                input.readFully(frame)
                handle(frame)
            }
        } catch (e: Throwable) {
            if (!closed && state != State.DONE) {
                val reason = when (e) {
                    is EOFException -> "השולח ניתק את החיבור"
                    is java.net.SocketTimeoutException -> "החיבור נותק (אין תגובה)"
                    else -> e.message ?: e.javaClass.simpleName
                }
                events.log("connection $id failed in $state: $e")
                fail(reason)
            }
        } finally {
            close()
        }
    }

    // ---------- wire ----------

    private fun send(bytes: ByteArray) {
        synchronized(output) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }

    private fun offlineFrame(type: Int, fill: (PB) -> Unit = {}): ByteArray =
        PB().int(F.OF_VERSION, 1).msg(F.OF_V1, PB().int(F.V1_TYPE, type).also(fill)).build()

    private fun sendEncrypted(offlineFrame: ByteArray) {
        val k = keys ?: throw IllegalStateException("no keys")
        synchronized(output) {
            serverSeq++
            val d2d = PB().bytes(1, offlineFrame).int(2, serverSeq).build()
            val iv = Crypto.randomBytes(16)
            val body = Crypto.aesEncrypt(k.encrypt, iv, d2d)
            val metadata = PB().int(1, 13 /* DEVICE_TO_DEVICE_MESSAGE */).int(2, 1).build()
            val header = PB().int(F.H_SIG_SCHEME, 1 /* HMAC_SHA256 */).int(F.H_ENC_SCHEME, 2 /* AES_256_CBC */)
                .bytes(F.H_IV, iv).bytes(F.H_PUBLIC_METADATA, metadata)
            val hb = PB().msg(F.HB_HEADER, header).bytes(F.HB_BODY, body).build()
            val sm = PB().bytes(F.SM_HEADER_AND_BODY, hb).bytes(F.SM_SIGNATURE, Crypto.hmac(k.sendHmac, hb)).build()
            send(sm)
        }
    }

    private fun sendBytesPayload(data: ByteArray, payloadId: Long) {
        fun frame(flags: Int, offset: Long, body: ByteArray?) = offlineFrame(F.T_PAYLOAD_TRANSFER) { v1 ->
            val header = PB().int(F.PH_ID, payloadId).int(F.PH_TYPE, F.PAYLOAD_BYTES)
                .int(F.PH_TOTAL, data.size.toLong()).bool(F.PH_SENSITIVE, false)
            val chunk = PB().int(F.PC_FLAGS, flags).int(F.PC_OFFSET, offset)
            if (body != null) chunk.bytes(F.PC_BODY, body)
            v1.msg(F.V1_PAYLOAD, PB().int(F.PT_PACKET_TYPE, F.PACKET_DATA).msg(F.PT_HEADER, header).msg(F.PT_CHUNK, chunk))
        }
        sendEncrypted(frame(0, 0, data))
        sendEncrypted(frame(1, data.size.toLong(), null))
    }

    private fun sendSharingFrame(type: Int, field: Int, inner: PB) {
        val frame = PB().int(1, 1).msg(2, PB().int(F.SV1_TYPE, type).msg(field, inner)).build()
        sendBytesPayload(frame, Crypto.random.nextLong())
    }

    private fun sendDisconnection() {
        try {
            val f = offlineFrame(F.T_DISCONNECTION) { it.msg(F.V1_DISCONNECTION, PB()) }
            if (keys != null) sendEncrypted(f) else send(f)
        } catch (_: Throwable) {}
    }

    private fun sendUkeyAlert(type: Int) {
        try { send(PB().int(F.UK_TYPE, F.UK_ALERT).msg(F.UK_DATA, PB().int(1, type)).build()) } catch (_: Throwable) {}
    }

    // ---------- protocol ----------

    private fun handle(frame: ByteArray) {
        when (state) {
            State.INITIAL -> onConnectionRequest(PM(frame))
            State.GOT_CONNECTION_REQUEST -> onClientInit(frame)
            State.SENT_SERVER_INIT -> onClientFinish(frame)
            State.GOT_CLIENT_FINISH -> onPlainConnectionResponse(PM(frame))
            else -> onSecureMessage(PM(frame))
        }
    }

    private fun onConnectionRequest(of: PM) {
        val v1 = of.msg(F.OF_V1) ?: throw ProtoException("no v1")
        if (v1.int(F.V1_TYPE) != F.T_CONNECTION_REQUEST) throw ProtoException("expected connection request")
        val cr = v1.msg(F.V1_CONN_REQUEST) ?: throw ProtoException("no connection request")
        val ei = cr.bytes(F.CR_ENDPOINT_INFO)
        if (ei != null && ei.size > 17) {
            senderType = (ei[0].toInt() shr 1) and 7
            val hasName = ei[0].toInt() and 0x10 == 0
            val nameLen = ei[17].toInt() and 0xFF
            if (hasName && ei.size >= 18 + nameLen) senderName = String(ei, 18, nameLen, Charsets.UTF_8)
        } else {
            cr.str(F.CR_ENDPOINT_NAME)?.let { senderName = it }
        }
        events.log("connection request from $senderName")
        state = State.GOT_CONNECTION_REQUEST
    }

    private fun onClientInit(raw: ByteArray) {
        val msg = PM(raw)
        if (msg.int(F.UK_TYPE) != F.UK_CLIENT_INIT) { sendUkeyAlert(3); throw ProtoException("expected UKEY2 client init") }
        val ci = PM(msg.bytes(F.UK_DATA) ?: run { sendUkeyAlert(4); throw ProtoException("empty client init") })
        if (ci.int(1) != 1) { sendUkeyAlert(100); throw ProtoException("UKEY2 version ${ci.int(1)}") }
        if ((ci.bytes(2)?.size ?: 0) != 32) { sendUkeyAlert(101); throw ProtoException("bad random") }
        commitment = ci.msgs(3).firstOrNull { it.int(1) == F.P256_SHA512 }?.bytes(2)
        if (commitment == null) { sendUkeyAlert(102); throw ProtoException("no P256 cipher") }
        val next = ci.str(4)
        if (next != null && next != "AES_256_CBC-HMAC_SHA256") events.log("next protocol: $next")
        clientInitRaw = raw

        val kp = Crypto.newP256KeyPair()
        keyPair = kp
        val (x, y) = Crypto.publicXY(kp)
        val publicKey = PB().int(1, 1 /* EC_P256 */).msg(2, PB().bytes(1, x).bytes(2, y)).build()
        val serverInit = PB().int(1, 1).bytes(2, Crypto.randomBytes(32)).int(3, F.P256_SHA512).bytes(4, publicKey).build()
        val out = PB().int(F.UK_TYPE, F.UK_SERVER_INIT).bytes(F.UK_DATA, serverInit).build()
        serverInitRaw = out
        send(out)
        state = State.SENT_SERVER_INIT
    }

    private fun onClientFinish(raw: ByteArray) {
        if (!Crypto.constantTimeEquals(Crypto.sha512(raw), commitment!!)) throw ProtoException("UKEY2 commitment mismatch")
        val msg = PM(raw)
        if (msg.int(F.UK_TYPE) != F.UK_CLIENT_FINISH) throw ProtoException("expected client finish")
        val cf = PM(msg.bytes(F.UK_DATA) ?: throw ProtoException("empty client finish"))
        val gpk = PM(cf.bytes(1) ?: throw ProtoException("no public key"))
        val ec = gpk.msg(2) ?: throw ProtoException("not an EC key")
        val x = ec.bytes(1) ?: throw ProtoException("no x")
        val y = ec.bytes(2) ?: throw ProtoException("no y")
        val shared = Crypto.ecdh(keyPair!!, x, y)
        val k = SessionKeys.derive(shared, clientInitRaw!!, serverInitRaw!!)
        keys = k
        pin = Crypto.pinCode(k.authKey)
        state = State.GOT_CLIENT_FINISH
    }

    private fun onPlainConnectionResponse(of: PM) {
        val type = of.msg(F.OF_V1)?.int(F.V1_TYPE)
        if (type != F.T_CONNECTION_RESPONSE) { events.log("unexpected plaintext frame $type"); return }
        val response = offlineFrame(F.T_CONNECTION_RESPONSE) {
            it.msg(F.V1_CONN_RESPONSE, PB().int(1, 0).int(3, 1 /* ACCEPT */).msg(4, PB().int(1, 4 /* os type */)))
        }
        send(response)
        state = State.ENCRYPTED
        sendSharingFrame(F.SF_TYPE_PAIRED_KEY_ENCRYPTION, F.SV1_PAIRED_KEY_ENCRYPTION,
            PB().bytes(1, Crypto.randomBytes(72)).bytes(2, Crypto.randomBytes(6)))
    }

    private fun onSecureMessage(sm: PM) {
        val k = keys!!
        val hb = sm.bytes(F.SM_HEADER_AND_BODY) ?: throw ProtoException("no header and body")
        val sig = sm.bytes(F.SM_SIGNATURE) ?: throw ProtoException("no signature")
        if (!Crypto.constantTimeEquals(Crypto.hmac(k.recvHmac, hb), sig)) throw ProtoException("bad signature")
        val hbm = PM(hb)
        val iv = hbm.msg(F.HB_HEADER)?.bytes(F.H_IV) ?: throw ProtoException("no iv")
        val d2d = PM(Crypto.aesDecrypt(k.decrypt, iv, hbm.bytes(F.HB_BODY) ?: ByteArray(0)))
        clientSeq++
        if (d2d.int(2) != clientSeq) throw ProtoException("bad sequence number")
        val v1 = PM(d2d.bytes(1) ?: throw ProtoException("empty message")).msg(F.OF_V1) ?: return
        when (v1.int(F.V1_TYPE)) {
            F.T_PAYLOAD_TRANSFER -> onPayload(v1.msg(F.V1_PAYLOAD) ?: return)
            F.T_KEEP_ALIVE -> sendEncrypted(offlineFrame(F.T_KEEP_ALIVE) { it.msg(F.V1_KEEP_ALIVE, PB().bool(1, true)) })
            F.T_DISCONNECTION -> {
                if (state != State.DONE) fail("השולח ביטל את השליחה")
                close()
            }
            else -> events.log("ignored frame type ${v1.int(F.V1_TYPE)}")
        }
    }

    private fun onPayload(pt: PM) {
        val header = pt.msg(F.PT_HEADER) ?: return
        val payloadId = header.long(F.PH_ID)
        if (pt.int(F.PT_PACKET_TYPE) == F.PACKET_CONTROL) {
            val event = pt.msg(F.PT_CONTROL)?.int(1) ?: 0
            if (event == 1 || event == 2) { fail("השולח ביטל את השליחה"); close() }
            return
        }
        val chunk = pt.msg(F.PT_CHUNK) ?: return
        val flags = chunk.int(F.PC_FLAGS)
        val offset = chunk.long(F.PC_OFFSET)
        val body = chunk.bytes(F.PC_BODY) ?: ByteArray(0)
        when (header.int(F.PH_TYPE)) {
            F.PAYLOAD_BYTES -> {
                if (header.long(F.PH_TOTAL) > MAX_FRAME) throw ProtoException("bytes payload too large")
                val buf = byteBuffers.getOrPut(payloadId) { java.io.ByteArrayOutputStream() }
                if (offset != buf.size().toLong()) throw ProtoException("bad offset")
                buf.write(body)
                if (flags and 1 == 1) {
                    byteBuffers.remove(payloadId)
                    onBytesPayload(payloadId, buf.toByteArray())
                }
            }
            F.PAYLOAD_FILE -> onFileChunk(payloadId, offset, body, flags and 1 == 1)
        }
    }

    private fun onBytesPayload(payloadId: Long, data: ByteArray) {
        if (payloadId == textPayloadId) {
            receivedText = String(data, Charsets.UTF_8)
            received += data.size
            maybeFinish()
            return
        }
        val frame = PM(data)
        val v1 = frame.msg(2) ?: return
        when (v1.int(F.SV1_TYPE)) {
            F.SF_TYPE_PAIRED_KEY_ENCRYPTION ->
                sendSharingFrame(F.SF_TYPE_PAIRED_KEY_RESULT, F.SV1_PAIRED_KEY_RESULT, PB().int(1, 3 /* UNABLE */))
            F.SF_TYPE_PAIRED_KEY_RESULT -> {}
            F.SF_TYPE_INTRODUCTION -> onIntroduction(v1.msg(F.SV1_INTRODUCTION) ?: return)
            F.SF_TYPE_CANCEL -> { fail("השולח ביטל את השליחה"); disconnect() }
            else -> events.log("ignored sharing frame ${v1.int(F.SV1_TYPE)}")
        }
    }

    private fun onIntroduction(intro: PM) {
        val fileList = intro.msgs(1).map {
            FileMeta(it.long(3), cleanName(it.str(1) ?: "קובץ"), it.long(4), it.str(5) ?: "application/octet-stream")
        }
        val text = intro.msgs(2).firstOrNull()?.let { TextMeta(it.long(4), it.str(2) ?: "", it.int(3), it.long(5)) }
        if (fileList.isEmpty() && text == null) {
            // Wi-Fi passwords, apps, streams: not supported
            respond(F.UNSUPPORTED)
            disconnect()
            return
        }
        val inc = Incoming(id, senderName, senderType, pin, fileList, text)
        info = inc
        state = State.WAITING_FOR_USER
        events.onIncoming(this, inc)
    }

    private fun respond(status: Int) =
        sendSharingFrame(F.SF_TYPE_RESPONSE, F.SV1_RESPONSE, PB().int(1, status))

    /** Called from the UI (any thread). */
    fun accept() {
        val inc = info ?: return
        if (state != State.WAITING_FOR_USER) return
        try {
            if (inc.totalBytes > 0 && storage.freeBytes() in 0 until inc.totalBytes) {
                respond(F.NOT_ENOUGH_SPACE); fail("אין מספיק מקום פנוי"); disconnect(); return
            }
            synchronized(this) {
                for (f in inc.files) files[f.payloadId] = FileRx(f, storage.create(f.name, f.mime, f.size))
                textPayloadId = inc.text?.payloadId
                state = State.RECEIVING
            }
            respond(F.ACCEPT)
        } catch (e: Throwable) {
            events.log("accept failed: $e")
            try { respond(F.REJECT) } catch (_: Throwable) {}
            fail("לא ניתן לשמור: ${e.message}")
            disconnect()
        }
    }

    fun reject() {
        if (state != State.WAITING_FOR_USER) return
        try { respond(F.REJECT) } catch (_: Throwable) {}
        state = State.DONE
        disconnect()
    }

    private fun onFileChunk(payloadId: Long, offset: Long, body: ByteArray, last: Boolean) {
        val rx = synchronized(this) { files[payloadId] } ?: throw ProtoException("unknown file payload")
        if (offset != rx.got) throw ProtoException("bad file offset")
        if (rx.got + body.size > rx.meta.size && rx.meta.size > 0) throw ProtoException("file larger than announced")
        if (body.isNotEmpty()) {
            rx.sink.out.write(body)
            rx.got += body.size
            received += body.size
            val now = System.currentTimeMillis()
            if (now - lastProgress > 700) { lastProgress = now; info?.let { events.onProgress(this, it, received) } }
        }
        if (last) {
            synchronized(this) { files.remove(payloadId) }
            saved += rx.sink.finish()
            maybeFinish()
        }
    }

    private fun maybeFinish() {
        val textPending = textPayloadId != null && receivedText == null
        if (synchronized(this) { files.isEmpty() } && !textPending && state == State.RECEIVING) {
            state = State.DONE
            events.onDone(this, info!!, saved.toList(), receivedText)
            disconnect()
        }
    }

    private fun fail(reason: String) {
        if (state == State.DONE) return
        state = State.DONE
        synchronized(this) {
            files.values.forEach { runCatching { it.sink.abort() } }
            files.clear()
        }
        events.onFailed(this, info, reason)
    }

    fun cancel() {
        if (state == State.DONE) return
        fail("בוטל")
        disconnect()
    }

    private fun disconnect() {
        sendDisconnection()
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        if (state != State.DONE) fail("החיבור נסגר")
        runCatching { socket.close() }
    }

    companion object {
        const val MAX_FRAME = 5 * 1024 * 1024
        private val nextId = AtomicInteger(0)

        /** Keep only the file name; never let a sender write outside the folder. */
        fun cleanName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
                .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            return if (base.isEmpty() || base == "." || base == "..") "קובץ" else base.take(200)
        }
    }
}
