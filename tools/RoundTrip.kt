// Round trip: OutboundConnection (sender) against InboundConnection (receiver): files, text, reject.
import com.chdv.quickreceive.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

fun main() {
    for (case in listOf("files", "text", "reject")) {
        val server = ServerSocket(0)
        val got = HashMap<String, ByteArrayOutputStream>()
        var recvText: String? = null
        var serverPin = ""; var clientPin = ""
        var final: SendStatus? = null
        val storage = object : Storage {
            override fun freeBytes() = 1L shl 40
            override fun create(name: String, mime: String, size: Long): Sink {
                val b = ByteArrayOutputStream(); got[name] = b
                return object : Sink { override val out: OutputStream = b; override fun finish() = Saved(name, size, "", mime, ""); override fun abort() {} }
            }
        }
        val rx = object : Events {
            override fun onIncoming(conn: InboundConnection, info: Incoming) { serverPin = info.pin; println("  rx: from ${info.sender}, files=${info.files.map { it.name + "/" + it.mime }}, text=${info.text?.title}"); thread { if (case == "reject") conn.reject() else conn.accept() } }
            override fun onProgress(conn: InboundConnection, info: Incoming, received: Long) {}
            override fun onDone(conn: InboundConnection, info: Incoming, saved: List<Saved>, text: String?) { recvText = text }
            override fun onFailed(conn: InboundConnection, info: Incoming?, reason: String) { println("  rx failed: $reason") }
        }
        thread { InboundConnection(server.accept(), storage, rx).run() }
        val a = ByteArray(1_700_001) { (it * 7 % 256).toByte() }
        val b = ByteArray(10) { it.toByte() }
        val items = when (case) {
            "text" -> listOf(SendItem.text("https://example.com/x"))
            else -> listOf(SendItem("תניא.pdf", a.size.toLong(), "application/pdf", { a.inputStream() }), SendItem("b.txt", b.size.toLong(), "text/plain", { b.inputStream() }))
        }
        val tx = object : SendEvents {
            override fun onStatus(conn: OutboundConnection, status: SendStatus) {
                if (status is SendStatus.WaitingForAccept) clientPin = status.pin
                if (status is SendStatus.Done || status is SendStatus.Failed) final = status
            }
            override fun log(msg: String) { println("  tx log: $msg") }
        }
        val t = thread { OutboundConnection(InetAddress.getLoopbackAddress(), server.localPort, "WXYZ", "Go6", 2, items, tx).run() }
        t.join(20000)
        val res = when (val f = final) { is SendStatus.Done -> "Done"; is SendStatus.Failed -> "Failed(${f.reason})"; else -> "none" }
        val ok = when (case) {
            "files" -> res == "Done" && got["תניא.pdf"]?.toByteArray()?.contentEquals(a) == true && got["b.txt"]?.toByteArray()?.contentEquals(b) == true
            "text" -> res == "Done" && recvText == "https://example.com/x"
            else -> res.startsWith("Failed")
        }
        println("$case: status=$res pins ${clientPin}/${serverPin} -> ${if (ok && clientPin == serverPin) "OK" else "FAIL"}")
        server.close()
    }
}
