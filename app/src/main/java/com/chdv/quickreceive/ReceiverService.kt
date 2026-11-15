package com.chdv.quickreceive

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Shared state between the service and the screens (same process). */
object Live {
    /** our 4-character id on the network, the same for receiving and sending */
    val endpointId: String = run {
        val abc = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        (1..4).map { abc[Crypto.random.nextInt(abc.length)] }.joinToString("")
    }
    /** the "ready to receive" screen is open: visible to everyone meanwhile */
    @Volatile var receiveScreen = false
    @Volatile var running = false
    @Volatile var visibleAs = ""
    @Volatile var error: String? = null
    val pending = ConcurrentHashMap<Int, Pair<InboundConnection, Incoming>>()
    val progress = ConcurrentHashMap<Int, Long>()
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun unlisten(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }
}

class ReceiverService : Service(), Events {
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val connections = ConcurrentHashMap<Int, InboundConnection>()
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private val visibilityCheck = Runnable { checkVisibility() }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_STATUS, statusNotification("מתחיל…"))
        startServer()
        watchNetwork()
        checkVisibility()
    }

    /** Stop when nobody should see us any more (e.g. the 10 minutes are over). */
    private fun checkVisibility() {
        main.removeCallbacks(visibilityCheck)
        val p = Prefs(this)
        if (!p.visible && connections.isEmpty()) {
            if (p.visibility == "off") p.visibility = "off"
            stopSelf(); return
        }
        if (p.visibility == "temp") main.postDelayed(visibilityCheck, (p.tempUntil - System.currentTimeMillis()).coerceIn(1000, 60_000))
        else if (!p.visible) main.postDelayed(visibilityCheck, 5_000)
        updateStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_ACCEPT -> accept(id)
            ACTION_REJECT -> reject(id)
            ACTION_CANCEL -> connections[id]?.cancel()
            ACTION_REFRESH -> { unregisterNsd(); registerNsd(); checkVisibility() }
            ACTION_STOP -> {
                Prefs(this).visibility = "off"
                Live.changed()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Live.running = false
        Live.changed()
        unregisterNsd()
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        runCatching { server?.close() }
        connections.values.forEach { it.cancel() }
        releaseLocks()
        super.onDestroy()
    }

    // ---------- server + discovery ----------

    private fun startServer() {
        try {
            val s = ServerSocket(0)
            server = s
            thread(name = "qs-accept") {
                while (!s.isClosed) {
                    val sock = try { s.accept() } catch (e: Exception) { break }
                    val conn = InboundConnection(sock, storageFor(this), this)
                    connections[conn.id] = conn
                    holdLocks()
                    thread(name = "qs-conn-${conn.id}") {
                        try { conn.run() } finally {
                            connections.remove(conn.id)
                            if (connections.isEmpty()) releaseLocks()
                        }
                    }
                }
            }
            registerNsd()
        } catch (e: Exception) {
            Live.error = "לא ניתן להפעיל את הקבלה: ${e.message}"
            Live.changed()
        }
    }

    private fun registerNsd() {
        val port = server?.localPort ?: return
        val name = Prefs(this).deviceName
        // Service name: 0x23, 4-byte endpoint id, service id hash FC 9F 5E, two zero bytes
        val nameBytes = byteArrayOf(0x23) + Live.endpointId.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xFC.toByte(), 0x9F.toByte(), 0x5E, 0, 0)
        val info = NsdServiceInfo().apply {
            serviceName = b64(nameBytes)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("n", b64(endpointInfo(name)))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Live.running = true; Live.visibleAs = name; Live.error = null
                updateStatus(); Live.changed()
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) {
                Live.error = "הרישום ברשת נכשל (קוד $code)"
                updateStatus(); Live.changed()
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        val m = getSystemService(NsdManager::class.java)
        nsd = m
        registration = listener
        try { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) {
            Live.error = "הרישום ברשת נכשל: ${e.message}"; Live.changed()
        }
    }

    private fun unregisterNsd() {
        val l = registration ?: return
        registration = null
        runCatching { nsd?.unregisterService(l) }
        Live.running = false
    }

    /** When Wi-Fi comes back (or changes), announce ourselves again. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.postDelayed({ unregisterNsd(); registerNsd() }, 1500)
            }
            override fun onLost(network: Network) {
                main.post { Live.running = false; Live.error = "אין חיבור ל-Wi-Fi"; updateStatus(); Live.changed() }
            }
        }
        netCallback = cb
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun holdLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "quickreceive:transfer").apply { acquire(60 * 60 * 1000L) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "quickreceive:transfer").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null; wifiLock = null
    }

    // ---------- events from connections (background threads) ----------

    override fun onIncoming(conn: InboundConnection, info: Incoming) {
        Live.pending[conn.id] = conn to info
        Live.changed()
        val prefs = Prefs(this)
        if (prefs.autoAccept || info.sender in prefs.trusted) {
            accept(conn.id)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        val consent = PendingIntent.getActivity(this, conn.id,
            Intent(this, ConsentActivity::class.java).putExtra(EXTRA_ID, conn.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, App.CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${info.sender} רוצה לשלוח לך")
            .setContentText(describe(info) + " · קוד ${info.pin}")
            .setStyle(Notification.BigTextStyle().bigText(describe(info) + "\nקוד אימות: ${info.pin}"))
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(consent)
            .setFullScreenIntent(consent, true)
            .addAction(Notification.Action.Builder(null, "קבלה", serviceIntent(ACTION_ACCEPT, conn.id)).build())
            .addAction(Notification.Action.Builder(null, "דחייה", serviceIntent(ACTION_REJECT, conn.id)).build())
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_TRANSFER + conn.id, n)
        // If the device is awake, show the question straight away
        runCatching { startActivity(Intent(this, ConsentActivity::class.java).putExtra(EXTRA_ID, conn.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onProgress(conn: InboundConnection, info: Incoming, received: Long) {
        Live.progress[conn.id] = received
        Live.changed()
        val total = info.totalBytes.coerceAtLeast(1)
        val pct = (received * 100 / total).toInt().coerceIn(0, 100)
        val n = Notification.Builder(this, App.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("מקבל מ-${info.sender} · $pct%")
            .setContentText("${formatSize(received)} מתוך ${formatSize(info.totalBytes)}")
            .setProgress(100, pct, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "ביטול", serviceIntent(ACTION_CANCEL, conn.id)).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_TRANSFER + conn.id, n)
    }

    override fun onDone(conn: InboundConnection, info: Incoming, saved: List<Saved>, text: String?) {
        Live.pending.remove(conn.id); Live.progress.remove(conn.id)
        val prefs = Prefs(this)
        val entries = saved.map { savedToJson(it, info.sender) }.toMutableList()
        if (text != null) {
            entries.add(textToJson(text, info.sender))
            main.post {
                runCatching {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("שיתוף מהיר", text))
                }
            }
        }
        prefs.addHistory(entries)
        Live.changed()

        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_TRANSFER + conn.id)
        val title = when {
            text != null && saved.isEmpty() -> "התקבל טקסט מ-${info.sender} (הועתק)"
            saved.size == 1 -> "התקבל: ${saved[0].name}"
            else -> "התקבלו ${saved.size} קבצים מ-${info.sender}"
        }
        val tap = if (saved.size == 1) openIntent(this, saved[0].uri, saved[0].mime)
        else Intent(this, MainActivity::class.java)
        val n = Notification.Builder(this, App.CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text ?: saved.joinToString(" · ") { it.name })
            .setContentIntent(PendingIntent.getActivity(this, 5000 + conn.id, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_DONE + conn.id, n)
        if (prefs.openAfter && saved.size == 1) {
            runCatching { startActivity(openIntent(this, saved[0].uri, saved[0].mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onFailed(conn: InboundConnection, info: Incoming?, reason: String) {
        Live.pending.remove(conn.id); Live.progress.remove(conn.id)
        Live.changed()
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_TRANSFER + conn.id)
        if (info == null) return  // never got as far as asking: nothing to report
        val n = Notification.Builder(this, App.CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("הקבלה מ-${info.sender} לא הושלמה")
            .setContentText(reason)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_DONE + conn.id, n)
    }

    override fun log(msg: String) { Log.i("QuickReceive", msg) }

    private fun accept(id: Int) {
        val (conn, info) = Live.pending[id] ?: return
        getSystemService(NotificationManager::class.java).cancel(NOTIF_TRANSFER + id)
        thread { conn.accept() }
        onProgress(conn, info, 0)
    }

    private fun reject(id: Int) {
        val (conn, _) = Live.pending.remove(id) ?: return
        getSystemService(NotificationManager::class.java).cancel(NOTIF_TRANSFER + id)
        thread { conn.reject() }
        Live.changed()
    }

    // ---------- helpers ----------

    private fun serviceIntent(action: String, id: Int): PendingIntent =
        PendingIntent.getService(this, action.hashCode() + id,
            Intent(this, ReceiverService::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun statusNotification(text: String): Notification =
        Notification.Builder(this, App.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("שיתוף מהיר")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "כיבוי", serviceIntent(ACTION_STOP, 0)).build())
            .setOngoing(true)
            .build()

    private fun updateStatus() {
        val p = Prefs(this)
        val mins = ((p.tempUntil - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(1)
        val text = Live.error ?: when {
            Live.receiveScreen || p.visibility == "everyone" -> "גלוי לכולם בשם \"${p.deviceName}\""
            p.visibility == "temp" -> "גלוי לכולם עוד $mins דק׳ בשם \"${p.deviceName}\""
            else -> "לא זמין"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, statusNotification(text))
    }

    companion object {
        const val SERVICE_TYPE = "_FC9F5ED42C8A._tcp"
        const val ACTION_ACCEPT = "accept"
        const val ACTION_REJECT = "reject"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_STOP = "stop"
        const val ACTION_REFRESH = "refresh"
        const val EXTRA_ID = "id"
        const val NOTIF_STATUS = 1
        const val NOTIF_TRANSFER = 1000
        const val NOTIF_DONE = 3000

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, ReceiverService::class.java)
            if (action != null) i.action = action
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ReceiverService::class.java))

        /** Start or stop receiving according to the visibility setting. */
        fun refresh(ctx: Context) {
            if (Prefs(ctx).visible) start(ctx, ACTION_REFRESH) else stop(ctx)
            Live.changed()
        }

        fun acceptFromUi(ctx: Context, id: Int) = ctx.startService(Intent(ctx, ReceiverService::class.java).setAction(ACTION_ACCEPT).putExtra(EXTRA_ID, id))
        fun rejectFromUi(ctx: Context, id: Int) = ctx.startService(Intent(ctx, ReceiverService::class.java).setAction(ACTION_REJECT).putExtra(EXTRA_ID, id))

        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** 1 byte flags (device type 2 = tablet, visible), 16 random bytes, name with 1-byte length. */
        fun endpointInfo(name: String): ByteArray {
            var nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 255) nameBytes = nameBytes.copyOf(255)
            return byteArrayOf((2 shl 1).toByte()) + Crypto.randomBytes(16) + byteArrayOf(nameBytes.size.toByte()) + nameBytes
        }


        fun describe(info: Incoming): String = when {
            info.files.isEmpty() && info.text != null -> if (info.text.type == 2) "קישור" else "טקסט"
            info.files.size == 1 -> "${info.files[0].name} (${formatSize(info.files[0].size)})"
            else -> "${info.files.size} קבצים (${formatSize(info.totalBytes)})"
        }

        fun openIntent(ctx: Context, uri: String, mime: String): Intent =
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
