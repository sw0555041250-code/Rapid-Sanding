package com.chdv.quickreceive

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** One transfer to one device. */
class SendJob(val peer: Peer, val items: List<SendItem>) {
    @Volatile var status: SendStatus = SendStatus.Connecting
    @Volatile var conn: OutboundConnection? = null
    val total: Long = items.sumOf { it.size }
    val active get() = status !is SendStatus.Done && status !is SendStatus.Failed
}

object Sends {
    /** by peer service name */
    val jobs = ConcurrentHashMap<String, SendJob>()
}

/** Runs outgoing transfers in the foreground, so they continue when the screen changes. */
class SendService : Service(), SendEvents {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF, notification("שולח…", null))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "quickshare:send").apply { acquire(60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra("peer")
        when (intent?.action) {
            "cancel" -> key?.let { k -> Sends.jobs[k]?.conn?.let { c -> thread { c.cancel() } } }
            else -> key?.let { start(it) }
        }
        maybeStop()
        return START_NOT_STICKY
    }

    private fun start(key: String) {
        val job = Sends.jobs[key] ?: return
        if (job.conn != null) return
        val p = Prefs(this)
        val conn = OutboundConnection(job.peer.host, job.peer.port, Live.endpointId, p.deviceName, 2, job.items, this)
        job.conn = conn
        thread(name = "qs-out-${conn.id}") { conn.run() }
    }

    override fun onStatus(conn: OutboundConnection, status: SendStatus) {
        val job = Sends.jobs.values.firstOrNull { it.conn === conn } ?: return
        job.status = status
        Live.changed()
        if (status is SendStatus.Done) {
            Prefs(this).addHistory(listOf(sentToJson(job.items.map { if (it.isText) "טקסט" else it.name }, job.peer.name, job.total)))
        }
        val text = when (status) {
            is SendStatus.Connecting -> "מתחבר ל-${job.peer.name}…"
            is SendStatus.WaitingForAccept -> "ממתין לאישור ב-${job.peer.name} · קוד ${status.pin}"
            is SendStatus.Sending -> "שולח ל-${job.peer.name} · ${pct(status.sent, status.total)}%"
            is SendStatus.Done -> "נשלח ל-${job.peer.name} ✓"
            is SendStatus.Failed -> "לא נשלח ל-${job.peer.name}: ${status.reason}"
        }
        val progress = (status as? SendStatus.Sending)?.let { pct(it.sent, it.total) }
        getSystemService(NotificationManager::class.java).notify(NOTIF, notification(text, progress, if (job.active) job.peer.serviceName else null))
        if (!job.active) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_RESULT, Notification.Builder(this, App.CH_DONE)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(text).setAutoCancel(true).build())
            maybeStop()
        }
    }

    override fun log(msg: String) { android.util.Log.i("QuickShareSend", msg) }

    private fun pct(a: Long, b: Long) = (a * 100 / b.coerceAtLeast(1)).toInt().coerceIn(0, 100)

    private fun maybeStop() {
        if (Sends.jobs.values.none { it.active }) {
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            stopForeground(true)
            stopSelf()
        }
    }

    private fun notification(text: String, progress: Int?, cancelKey: String? = null): Notification {
        val b = Notification.Builder(this, App.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("שיתוף מהיר")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, SendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        if (progress != null) b.setProgress(100, progress, false)
        if (cancelKey != null) b.addAction(Notification.Action.Builder(null, "ביטול",
            PendingIntent.getService(this, cancelKey.hashCode(), Intent(this, SendService::class.java).setAction("cancel").putExtra("peer", cancelKey),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        return b.build()
    }

    companion object {
        const val NOTIF = 5
        const val NOTIF_RESULT = 6

        fun send(ctx: Context, peer: Peer, items: List<SendItem>) {
            Sends.jobs[peer.serviceName]?.conn?.let { if (Sends.jobs[peer.serviceName]!!.active) return }
            Sends.jobs[peer.serviceName] = SendJob(peer, items)
            Live.changed()
            ctx.startForegroundService(Intent(ctx, SendService::class.java).putExtra("peer", peer.serviceName))
        }

        fun cancel(ctx: Context, peer: Peer) =
            ctx.startService(Intent(ctx, SendService::class.java).setAction("cancel").putExtra("peer", peer.serviceName))
    }
}
