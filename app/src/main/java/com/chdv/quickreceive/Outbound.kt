package com.chdv.quickreceive

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Something to send: a file (opened lazily) or a piece of text / a link. */
class SendItem(val name: String, val size: Long, val mime: String, val open: () -> InputStream, val text: String? = null) {
    val isText get() = text != null
    companion object {
        fun text(t: String) = SendItem("", t.toByteArray(Charsets.UTF_8).size.toLong(), "text/plain", { t.byteInputStream() }, t)
    }
}

interface SendEvents {
    fun onStatus(conn: OutboundConnection, status: SendStatus)
    fun log(msg: String) {}
}

sealed class SendStatus {
    object Connecting : SendStatus()
    class WaitingForAccept(val pin: String) : SendStatus()
    class Sending(val sent: Long, val total: Long) : SendStatus()
    object Done : SendStatus()
    class Failed(val reason: String) : SendStatus()
}

/**
 * Sends files to a Quick Share receiver (we are the "client"). Mirror image of [InboundConnection].
 */
class OutboundConnection(
    private val host: java.net.InetAddress,
    private val port: Int,
    private val myEndpointId: String,
    private val myName: String,
    private val myDeviceType: Int,
    private val items: List<SendItem>,
    private val events: SendEvents,
) : Runnable {
    val id = nextId.incrementAndGet()
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    @Volatile private var closed = false
    @Volatile private var finished = false
    @Volatile private var accepted = false
    @Volatile private var cancelled = false
    @Volatile private var peerDisconnected = false

    private var keys: SessionKeys? = null
    private var sendSeq = 0
    private var recvSeq = 0
    private val buffers = HashMap<Long, java.io.ByteArrayOutputStream>()

    private val textItem = items.firstOrNull { it.isText }
    private val files = items.filter { !it.isText }
    private val payloadIds = files.map { Crypto.random.nextLong() }
    private val textPayloadId = Crypto.random.nextLong()
    private val total = items.sumOf { it.size }

    override fun run() {
        try {
            status(SendStatus.Connecting)
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.tcpNoDelay = true
            socket.soTimeout = 90_000
            input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
            output = DataOutputStream(socket.getOutputStream().buffered(256 * 1024))
            handshake()
            while (!closed) {
                val len = input.readInt()
                if (len < 0 || len > InboundConnection.MAX_FRAME) throw ProtoException("bad frame length")
                val f = ByteArray(len); input.readFully(f)
                onSecureMessage(PM(f))
            }
        } catch (e: Throwable) {
            if (!finished && !cancelled) {
                events.log("send failed: $e")
                fail(when (e) {
                    is java.net.ConnectException, is java.net.NoRouteToHostException -> "לא ניתן להתחבר למכשיר"
                    is java.net.SocketTimeoutException -> if (accepted) "החיבור נותק" else "המכשיר לא ענה"
                    is java.io.EOFException -> if (accepted) "המכשיר ניתק את החיבור" else "המכשיר דחה או ביטל"
                    else -> e.message ?: "השליחה נכשלה"
                })
            }
        } finally {
            close()
        }
    }

    fun cancel() {
        if (finished) return
        cancelled = true
        runCatching { if (keys != null) sendSharing(SF_CANCEL, null, null) }
        runCatching { sendDisconnection() }
        fail("השליחה בוטלה")
        close()
    }

    private fun status(s: SendStatus) = events.onStatus(this, s)

    private fun fail(reason: String) {
        if (finished) return
        finished = true
        status(SendStatus.Failed(reason))
    }

    private fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    // ---------- wire ----------

    private fun send(b: ByteArray) = synchronized(this) { output.writeInt(b.size); output.write(b); output.flush() }

    private fun read(): ByteArray { val n = input.readInt(); if (n < 0 || n > InboundConnection.MAX_FRAME) throw ProtoException("bad frame"); return ByteArray(n).also { input.readFully(it) } }

    private fun offline(type: Int, fill: (PB) -> Unit = {}) = PB().int(F.OF_VERSION, 1).msg(F.OF_V1, PB().int(F.V1_TYPE, type).also(fill)).build()

    private fun sendEncrypted(frame: ByteArray) {
        val k = keys!!
        synchronized(this) {
            sendSeq++
            val d2d = PB().bytes(1, frame).int(2, sendSeq).build()
            val iv = Crypto.randomBytes(16)
            val meta = PB().int(1, 13).int(2, 1).build()
            val header = PB().int(F.H_SIG_SCHEME, 1).int(F.H_ENC_SCHEME, 2).bytes(F.H_IV, iv).bytes(F.H_PUBLIC_METADATA, meta)
            // client side: we encrypt with the client keys (the server's "decrypt" keys)
            val hb = PB().msg(F.HB_HEADER, header).bytes(F.HB_BODY, Crypto.aesEncrypt(k.decrypt, iv, d2d)).build()
            send(PB().bytes(F.SM_HEADER_AND_BODY, hb).bytes(F.SM_SIGNATURE, Crypto.hmac(k.recvHmac, hb)).build())
        }
    }

    private fun payloadFrame(id: Long, type: Int, totalSize: Long, offset: Long, flags: Int, body: ByteArray?) = offline(F.T_PAYLOAD_TRANSFER) { v1 ->
        val header = PB().int(F.PH_ID, id).int(F.PH_TYPE, type).int(F.PH_TOTAL, totalSize).bool(F.PH_SENSITIVE, false)
        val chunk = PB().int(F.PC_FLAGS, flags).int(F.PC_OFFSET, offset)
        if (body != null) chunk.bytes(F.PC_BODY, body)
        v1.msg(F.V1_PAYLOAD, PB().int(F.PT_PACKET_TYPE, F.PACKET_DATA).msg(F.PT_HEADER, header).msg(F.PT_CHUNK, chunk))
    }

    private fun sendBytes(data: ByteArray, id: Long) {
        sendEncrypted(payloadFrame(id, F.PAYLOAD_BYTES, data.size.toLong(), 0, 0, data))
        sendEncrypted(payloadFrame(id, F.PAYLOAD_BYTES, data.size.toLong(), data.size.toLong(), 1, null))
    }

    private fun sendSharing(type: Int, field: Int?, inner: PB?) {
        val v1 = PB().int(F.SV1_TYPE, type)
        if (field != null && inner != null) v1.msg(field, inner)
        sendBytes(PB().int(1, 1).msg(2, v1).build(), Crypto.random.nextLong())
    }

    private fun sendDisconnection() {
        val f = offline(F.T_DISCONNECTION) { it.msg(F.V1_DISCONNECTION, PB()) }
        if (keys != null) sendEncrypted(f) else send(f)
    }

    // ---------- protocol ----------

    private fun handshake() {
        val nameBytes = myName.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val endpointInfo = byteArrayOf((myDeviceType shl 1).toByte()) + Crypto.randomBytes(16) + byteArrayOf(nameBytes.size.toByte()) + nameBytes
        send(offline(F.T_CONNECTION_REQUEST) {
            it.msg(F.V1_CONN_REQUEST, PB().str(F.CR_ENDPOINT_ID, myEndpointId).str(F.CR_ENDPOINT_NAME, myName)
                .bytes(F.CR_ENDPOINT_INFO, endpointInfo).int(5, 5 /* WIFI_LAN */))
        })

        val kp = Crypto.newP256KeyPair()
        val (x, y) = Crypto.publicXY(kp)
        val gpk = PB().int(1, 1).msg(2, PB().bytes(1, x).bytes(2, y)).build()
        val finish = PB().int(F.UK_TYPE, F.UK_CLIENT_FINISH).bytes(F.UK_DATA, PB().bytes(1, gpk).build()).build()
        val ci = PB().int(1, 1).bytes(2, Crypto.randomBytes(32))
            .msg(3, PB().int(1, F.P256_SHA512).bytes(2, Crypto.sha512(finish)))
            .str(4, "AES_256_CBC-HMAC_SHA256").build()
        val clientInit = PB().int(F.UK_TYPE, F.UK_CLIENT_INIT).bytes(F.UK_DATA, ci).build()
        send(clientInit)

        val serverInit = read()
        val sm = PM(serverInit)
        if (sm.int(F.UK_TYPE) != F.UK_SERVER_INIT) throw ProtoException("המכשיר לא ענה כמו שצריך")
        val si = PM(sm.bytes(F.UK_DATA) ?: throw ProtoException("empty server init"))
        if (si.int(3) != F.P256_SHA512) throw ProtoException("unsupported cipher")
        val ec = PM(si.bytes(4) ?: throw ProtoException("no key")).msg(2) ?: throw ProtoException("not EC")
        val k = SessionKeys.derive(Crypto.ecdh(kp, ec.bytes(1)!!, ec.bytes(2)!!), clientInit, serverInit)

        send(finish)
        send(offline(F.T_CONNECTION_RESPONSE) {
            it.msg(F.V1_CONN_RESPONSE, PB().int(1, 0).int(3, 1).msg(4, PB().int(1, 4)))
        })
        val resp = PM(read()).msg(F.OF_V1)
        if (resp?.int(F.V1_TYPE) != F.T_CONNECTION_RESPONSE) throw ProtoException("unexpected response")
        if (resp.msg(F.V1_CONN_RESPONSE)?.int(3) != 1) throw ProtoException("המכשיר דחה את החיבור")
        keys = k
        status(SendStatus.WaitingForAccept(Crypto.pinCode(k.authKey)))
        sendSharing(F.SF_TYPE_PAIRED_KEY_ENCRYPTION, F.SV1_PAIRED_KEY_ENCRYPTION,
            PB().bytes(1, Crypto.randomBytes(72)).bytes(2, Crypto.randomBytes(6)))
    }

    private fun onSecureMessage(sm: PM) {
        val k = keys!!
        val hb = sm.bytes(F.SM_HEADER_AND_BODY) ?: throw ProtoException("no body")
        if (!Crypto.constantTimeEquals(Crypto.hmac(k.sendHmac, hb), sm.bytes(F.SM_SIGNATURE) ?: ByteArray(0))) throw ProtoException("bad signature")
        val h = PM(hb)
        val d2d = PM(Crypto.aesDecrypt(k.encrypt, h.msg(F.HB_HEADER)?.bytes(F.H_IV) ?: throw ProtoException("no iv"), h.bytes(F.HB_BODY) ?: ByteArray(0)))
        recvSeq++
        if (d2d.int(2) != recvSeq) throw ProtoException("bad sequence")
        val v1 = PM(d2d.bytes(1) ?: return).msg(F.OF_V1) ?: return
        when (v1.int(F.V1_TYPE)) {
            F.T_PAYLOAD_TRANSFER -> onPayload(v1.msg(F.V1_PAYLOAD) ?: return)
            F.T_KEEP_ALIVE -> sendEncrypted(offline(F.T_KEEP_ALIVE) { it.msg(F.V1_KEEP_ALIVE, PB().bool(1, true)) })
            F.T_DISCONNECTION -> {
                peerDisconnected = true
                if (!finished) { if (sentAll) succeed() else fail(if (accepted) "המכשיר ניתק את החיבור" else "המכשיר דחה או ביטל") }
                close()
            }
            else -> {}
        }
    }

    private fun onPayload(pt: PM) {
        val header = pt.msg(F.PT_HEADER) ?: return
        if (header.int(F.PH_TYPE) != F.PAYLOAD_BYTES) return
        val chunk = pt.msg(F.PT_CHUNK) ?: return
        val id = header.long(F.PH_ID)
        val buf = buffers.getOrPut(id) { java.io.ByteArrayOutputStream() }
        chunk.bytes(F.PC_BODY)?.let { buf.write(it) }
        if (chunk.int(F.PC_FLAGS) and 1 == 0) return
        buffers.remove(id)
        val v1 = PM(buf.toByteArray()).msg(2) ?: return
        when (v1.int(F.SV1_TYPE)) {
            F.SF_TYPE_PAIRED_KEY_ENCRYPTION -> sendSharing(F.SF_TYPE_PAIRED_KEY_RESULT, F.SV1_PAIRED_KEY_RESULT, PB().int(1, 3))
            F.SF_TYPE_PAIRED_KEY_RESULT -> sendIntroduction()
            F.SF_TYPE_RESPONSE -> when (v1.msg(F.SV1_RESPONSE)?.int(1)) {
                F.ACCEPT -> { accepted = true; thread(name = "qs-send-$id") { sendAll() } }
                F.NOT_ENOUGH_SPACE -> { fail("אין מספיק מקום במכשיר השני"); disconnect() }
                F.UNSUPPORTED -> { fail("המכשיר השני לא תומך בסוג הזה"); disconnect() }
                5 -> { fail("לא אושר בזמן"); disconnect() }
                else -> { fail("המכשיר השני דחה את השליחה"); disconnect() }
            }
            F.SF_TYPE_CANCEL -> { fail("המכשיר השני ביטל"); disconnect() }
        }
    }

    private fun disconnect() { runCatching { sendDisconnection() }; close() }

    private fun sendIntroduction() {
        val intro = PB()
        files.forEachIndexed { i, f ->
            val type = when {
                f.mime.startsWith("image/") -> 1
                f.mime.startsWith("video/") -> 2
                f.mime.startsWith("audio/") -> 4
                f.name.lowercase().endsWith(".apk") -> 3
                else -> 5 // DOCUMENT
            }
            intro.msg(1, PB().str(1, f.name).int(2, type).int(3, payloadIds[i]).int(4, f.size).str(5, f.mime).int(6, Crypto.random.nextLong()))
        }
        textItem?.let { t ->
            val isUrl = t.text!!.trim().let { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains(' ') }
            val title = if (isUrl) runCatching { java.net.URI(t.text.trim()).host }.getOrNull() ?: "קישור" else t.text.take(40)
            intro.msg(2, PB().str(2, title).int(3, if (isUrl) 2 else 1).int(4, textPayloadId).int(5, t.size).int(6, Crypto.random.nextLong()))
        }
        sendSharing(F.SF_TYPE_INTRODUCTION, F.SV1_INTRODUCTION, intro)
    }

    @Volatile private var sentAll = false

    private fun sendAll() {
        try {
            var sent = 0L
            var lastReport = 0L
            status(SendStatus.Sending(0, total))
            files.forEachIndexed { i, f ->
                val id = payloadIds[i]
                var offset = 0L
                f.open().use { ins ->
                    val buf = ByteArray(512 * 1024)
                    while (!cancelled && !closed) {
                        var n = 0
                        while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                        if (n <= 0) break
                        sendEncrypted(payloadFrame(id, F.PAYLOAD_FILE, f.size, offset, 0, buf.copyOf(n)))
                        offset += n; sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 600) { lastReport = now; status(SendStatus.Sending(sent, total)) }
                        if (n < buf.size) break
                    }
                }
                if (cancelled || closed) return
                sendEncrypted(payloadFrame(id, F.PAYLOAD_FILE, f.size, offset, 1, null))
            }
            textItem?.let { sendBytes(it.text!!.toByteArray(Charsets.UTF_8), textPayloadId); sent += it.size }
            sentAll = true
            status(SendStatus.Sending(total, total))
            // give the receiver a moment to confirm, then say goodbye ourselves
            val until = System.currentTimeMillis() + 6000
            while (!peerDisconnected && !closed && System.currentTimeMillis() < until) Thread.sleep(100)
            succeed()
            disconnect()
        } catch (e: Throwable) {
            if (!finished && !cancelled) fail(e.message ?: "השליחה נכשלה")
            close()
        }
    }

    private fun succeed() {
        if (finished) return
        finished = true
        status(SendStatus.Done)
    }

    companion object {
        private const val SF_CANCEL = 6
        private val nextId = AtomicInteger(0)
    }
}
