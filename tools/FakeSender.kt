// Protocol self-test: plays the sending phone against InboundConnection over a local socket.
// Run: kotlinc app/src/main/java/com/chdv/quickreceive/{Proto,Crypto,Connection}.kt tools/FakeSender.kt -d t.jar && java -cp t.jar:<kotlin-stdlib.jar> FakeSenderKt
import com.chdv.quickreceive.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

// Plays the phone (sender/client) against InboundConnection over a local socket.
fun main() {
    val server = ServerSocket(0)
    val savedData = ByteArrayOutputStream()
    var serverPin = ""
    var done = false
    var savedName = ""
    val storage = object : Storage {
        override fun freeBytes() = 1L shl 40
        override fun create(name: String, mime: String, size: Long): Sink {
            savedName = name
            return object : Sink {
                override val out: OutputStream = savedData
                override fun finish() = Saved(name, size, "mem://$name", mime, "mem")
                override fun abort() { println("abort") }
            }
        }
    }
    val events = object : Events {
        override fun onIncoming(conn: InboundConnection, info: Incoming) {
            println("SERVER: incoming from ${info.sender} pin=${info.pin} files=${info.files}")
            serverPin = info.pin
            thread { conn.accept() }
        }
        override fun onProgress(conn: InboundConnection, info: Incoming, received: Long) {}
        override fun onDone(conn: InboundConnection, info: Incoming, saved: List<Saved>, text: String?) { println("SERVER: done $saved"); done = true }
        override fun onFailed(conn: InboundConnection, info: Incoming?, reason: String) { println("SERVER: failed $reason") }
        override fun log(msg: String) { println("SERVER log: $msg") }
    }
    thread { InboundConnection(server.accept(), storage, events).run() }

    val s = Socket("127.0.0.1", server.localPort)
    val inp = DataInputStream(s.getInputStream()); val out = DataOutputStream(s.getOutputStream())
    fun send(b: ByteArray) { out.writeInt(b.size); out.write(b); out.flush() }
    fun recv(): ByteArray { val n = inp.readInt(); return ByteArray(n).also { inp.readFully(it) } }

    val name = "Pixel של בדיקה".toByteArray()
    val ei = byteArrayOf(2) + Crypto.randomBytes(16) + byteArrayOf(name.size.toByte()) + name
    send(PB().int(1, 1).msg(2, PB().int(1, 1).msg(2, PB().str(1, "ABCD").bytes(6, ei))).build())

    val kp = Crypto.newP256KeyPair(); val (x, y) = Crypto.publicXY(kp)
    val gpk = PB().int(1, 1).msg(2, PB().bytes(1, x).bytes(2, y)).build()
    val finish = PB().int(1, 4).bytes(2, PB().bytes(1, gpk).build()).build()
    val ci = PB().int(1, 1).bytes(2, Crypto.randomBytes(32))
        .msg(3, PB().int(1, 200).bytes(2, Crypto.randomBytes(64)))
        .msg(3, PB().int(1, 100).bytes(2, Crypto.sha512(finish)))
        .str(4, "AES_256_CBC-HMAC_SHA256").build()
    val clientInit = PB().int(1, 2).bytes(2, ci).build()
    send(clientInit)
    val serverInit = recv()
    val si = PM(PM(serverInit).bytes(2)!!)
    val sk = PM(si.bytes(4)!!).msg(2)!!
    send(finish)
    val shared = Crypto.ecdh(kp, sk.bytes(1)!!, sk.bytes(2)!!)
    val k = SessionKeys.derive(shared, clientInit, serverInit)
    val clientPin = Crypto.pinCode(k.authKey)
    // client side: swap
    val enc = k.decrypt; val sendMac = k.recvHmac; val dec = k.encrypt; val recvMac = k.sendHmac

    send(PB().int(1, 1).msg(2, PB().int(1, 2).msg(3, PB().int(1, 0).int(3, 1))).build())
    val resp = PM(recv()); println("CLIENT: server conn response type=${resp.msg(2)!!.int(1)}")

    var seq = 0; var rseq = 0
    fun sendEnc(frame: ByteArray) {
        seq++
        val d2d = PB().bytes(1, frame).int(2, seq).build()
        val iv = Crypto.randomBytes(16)
        val hb = PB().msg(1, PB().int(1, 1).int(2, 2).bytes(5, iv)).bytes(2, Crypto.aesEncrypt(enc, iv, d2d)).build()
        send(PB().bytes(1, hb).bytes(2, Crypto.hmac(sendMac, hb)).build())
    }
    fun recvEnc(): PM {
        val sm = PM(recv()); val hb = sm.bytes(1)!!
        check(Crypto.hmac(recvMac, hb).contentEquals(sm.bytes(2)!!)) { "client: bad mac" }
        val h = PM(hb); val d2d = PM(Crypto.aesDecrypt(dec, h.msg(1)!!.bytes(5)!!, h.bytes(2)!!))
        rseq++; check(d2d.int(2) == rseq)
        return PM(d2d.bytes(1)!!).msg(2)!!
    }
    fun recvSharing(): PM {
        val buf = ByteArrayOutputStream()
        while (true) {
            val v1 = recvEnc(); check(v1.int(1) == 3) { "expected payload, got ${v1.int(1)}" }
            val c = v1.msg(4)!!.msg(3)!!
            c.bytes(3)?.let { buf.write(it) }
            if (c.int(1) and 1 == 1) return PM(buf.toByteArray()).msg(2)!!
        }
    }
    fun sendBytes(data: ByteArray, id: Long) {
        fun f(flags: Int, off: Long, body: ByteArray?) = PB().int(1, 1).msg(2, PB().int(1, 3).msg(4,
            PB().int(1, 1).msg(2, PB().int(1, id).int(2, 1).int(3, data.size.toLong()))
                .msg(3, PB().int(1, flags).int(2, off).also { if (body != null) it.bytes(3, body) }))).build()
        sendEnc(f(0, 0, data)); sendEnc(f(1, data.size.toLong(), null))
    }
    fun sharing(type: Int, field: Int, inner: PB) = PB().int(1, 1).msg(2, PB().int(1, type).msg(field, inner)).build()

    println("CLIENT: got sharing type ${recvSharing().int(1)} (expect 3 paired key encryption)")
    sendBytes(sharing(3, 4, PB().bytes(1, Crypto.randomBytes(72)).bytes(2, Crypto.randomBytes(6))), 11)
    println("CLIENT: got sharing type ${recvSharing().int(1)} (expect 4 paired key result)")
    sendBytes(sharing(4, 5, PB().int(1, 3)), 12)

    val content = ByteArray(1_300_000) { (it % 251).toByte() }
    val fileId = -1234567890123L
    sendBytes(sharing(1, 2, PB().msg(1, PB().str(1, "../../ספר חדש.pdf").int(2, 5).int(3, fileId).int(4, content.size.toLong()).str(5, "application/pdf"))), 13)
    val r = recvSharing(); println("CLIENT: response type=${r.int(1)} status=${r.msg(3)!!.int(1)} (expect 2 / 1)")
    var off = 0
    while (true) {
        val n = minOf(512 * 1024, content.size - off)
        val body = content.copyOfRange(off, off + n)
        sendEnc(PB().int(1, 1).msg(2, PB().int(1, 3).msg(4, PB().int(1, 1)
            .msg(2, PB().int(1, fileId).int(2, 2).int(3, content.size.toLong()))
            .msg(3, PB().int(1, 0).int(2, off.toLong()).bytes(3, body)))).build())
        off += n
        if (off >= content.size) break
    }
    sendEnc(PB().int(1, 1).msg(2, PB().int(1, 3).msg(4, PB().int(1, 1)
        .msg(2, PB().int(1, fileId).int(2, 2).int(3, content.size.toLong()))
        .msg(3, PB().int(1, 1).int(2, off.toLong())))).build())
    val last = recvEnc(); println("CLIENT: final frame type ${last.int(1)} (expect 6 disconnection)")
    Thread.sleep(300)
    println("PIN client=$clientPin server=$serverPin  name=$savedName  data ok=${savedData.toByteArray().contentEquals(content)} done=$done")
    check(clientPin == serverPin && savedData.toByteArray().contentEquals(content) && done && savedName == "ספר חדש.pdf")
    println("ALL OK")
}
