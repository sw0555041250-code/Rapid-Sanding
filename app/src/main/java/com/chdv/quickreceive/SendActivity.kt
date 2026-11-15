package com.chdv.quickreceive

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The share sheet: what is being sent, and the devices around that can receive it.
 * Opened from "Share" in any app, or from the Send button.
 */
class SendActivity : BaseActivity() {
    private var items: List<SendItem> = emptyList()
    private var discovery: Discovery? = null
    private var peers: List<Peer> = emptyList()
    private var hiddenCount = 0
    private lateinit var devicesBox: LinearLayout
    private lateinit var hintBox: LinearLayout
    private val refresh: () -> Unit = { renderDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = readItems(intent)
        if (items.isEmpty() && Sends.jobs.values.none { it.active }) {
            pickFiles(); return
        }
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val more = readItems(intent)
        if (more.isNotEmpty()) { items = more; build() }
    }

    override fun onStart() {
        super.onStart()
        Live.listen(refresh)
        discovery = Discovery(this, Live.endpointId) { list, hidden -> peers = list; hiddenCount = hidden; if (::devicesBox.isInitialized) renderDevices() }
            .also { it.start() }
    }

    override fun onStop() {
        discovery?.stop(); discovery = null
        Live.unlisten(refresh)
        super.onStop()
    }

    private fun readItems(i: Intent): List<SendItem> {
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION") (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
                if (uris.isEmpty()) i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return listOf(SendItem.text(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            }
        }
        i.clipData?.let { c -> if (uris.isEmpty()) for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } }
        i.data?.let { if (uris.isEmpty() && i.action != Intent.ACTION_VIEW) uris += it }
        return uris.mapNotNull { itemFor(it) }
    }

    private fun itemFor(uri: Uri): SendItem? = runCatching {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "קובץ"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getString(0)?.let { n -> name = n }
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        if (size < 0) size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0
        val mime = mimeFor(name, contentResolver.getType(uri) ?: "")
        SendItem(name, size, mime, { contentResolver.openInputStream(uri)!! })
    }.getOrNull()

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { startActivityForResult(i, REQ_PICK) }.onFailure { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        if (resultCode != RESULT_OK || data == null) { if (items.isEmpty()) finish(); return }
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        items = uris.mapNotNull { itemFor(it) }
        if (items.isEmpty()) finish() else build()
    }

    private fun build() {
        val c = page("שיתוף מהיר", back = true)

        // what is being sent
        val what = card()
        val files = items.filter { !it.isText }
        val row = hbox()
        row.addView(text(if (items.any { it.isText }) "✎" else "▤", 26f, bold = true, color = Ink.WHITE).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = rounded(Ink.BLACK, radius = dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        val t = vbox().apply { setPadding(dp(14), 0, 0, 0) }
        when {
            items.isEmpty() -> t.addView(text("שליחות פעילות", 18f, bold = true))
            files.isEmpty() -> { t.addView(text("טקסט", 18f, bold = true)); t.addView(text(items[0].text!!.take(80), 14f, color = Ink.GRAY).apply { maxLines = 2 }) }
            files.size == 1 -> { t.addView(text(files[0].name, 18f, bold = true).apply { maxLines = 2 }); t.addView(text(formatSize(files[0].size), 14f, color = Ink.GRAY)) }
            else -> { t.addView(text("${files.size} קבצים", 18f, bold = true)); t.addView(text("${formatSize(files.sumOf { it.size })} · ${files.take(3).joinToString(", ") { it.name }}", 14f, color = Ink.GRAY).apply { maxLines = 2 }) }
        }
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        what.addView(row)
        c.addView(what)

        c.addView(text("מכשירים בסביבה", 20f, bold = true).apply { lp(top = 24, bottom = 10) })
        devicesBox = vbox(); c.addView(devicesBox)
        hintBox = vbox(); c.addView(hintBox)
        renderDevices()
    }

    private fun renderDevices() {
        if (!::devicesBox.isInitialized) return
        devicesBox.removeAllViews()
        // devices we already sent to stay on screen even if they stop announcing themselves
        val shown = LinkedHashMap<String, Peer>()
        Sends.jobs.values.forEach { shown[it.peer.serviceName] = it.peer }
        peers.forEach { shown[it.serviceName] = it }
        if (shown.isEmpty()) {
            val waiting = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            waiting.addView(text("◌", 40f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp() })
            waiting.addView(text("מחפש מכשירים…", 18f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp() })
            waiting.addView(text("בטלפון: לפתוח את המסך, ושהשיתוף המהיר יהיה גלוי ל\"כולם\"", 14f, color = Ink.GRAY).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp(top = 4) })
            devicesBox.addView(waiting)
        } else {
            val grid = FlowGrid(this, dp(12))
            shown.values.forEach { grid.addView(deviceTile(it)) }
            devicesBox.addView(grid)
        }
        renderHints()
    }

    private fun deviceTile(p: Peer): View {
        val job = Sends.jobs[p.serviceName]
        val tile = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutParams = android.view.ViewGroup.LayoutParams(dp(112), WRAP)
        }
        val busy = job?.active == true
        val glyph = when (p.type) { 1, 5 -> "▯"; 2 -> "▭"; 3 -> "⌨"; else -> "◉" }
        tile.addView(text(glyph, 30f, bold = true, color = if (busy) Ink.WHITE else Ink.BLACK).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = if (busy) rounded(Ink.BLACK, radius = dp(36).toFloat()) else rounded(Ink.WHITE, Ink.BLACK, dp(3), dp(36).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        tile.addView(text(p.name, 15f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; maxLines = 2; lp(top = 6) })
        val status: String? = when (val s = job?.status) {
            null -> null
            is SendStatus.Connecting -> "מתחבר…"
            is SendStatus.WaitingForAccept -> "ממתין לאישור\nקוד ${s.pin}"
            is SendStatus.Sending -> "${(s.sent * 100 / s.total.coerceAtLeast(1))}%"
            is SendStatus.Done -> "נשלח ✓"
            is SendStatus.Failed -> s.reason
        }
        if (status != null) tile.addView(text(status, 13f, bold = job?.status is SendStatus.WaitingForAccept, color = if (job?.status is SendStatus.Failed) Ink.GRAY else Ink.BLACK).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER; maxLines = 3; lp(top = 2)
        })
        tile.isClickable = true
        tile.setOnClickListener {
            when {
                busy -> askCancel(p)
                items.isEmpty() -> info("אין מה לשלוח", "פתחו קובץ מאפליקציה אחרת › שיתוף › שיתוף מהיר, או לחצו \"שליחה\" במסך הראשי.")
                p.serviceName !in peers.map { it.serviceName } -> info("המכשיר לא נמצא כרגע", "ודאו שהמסך בטלפון דולק ושהוא על אותה רשת Wi-Fi.")
                else -> SendService.send(this, p, items)
            }
        }
        return tile
    }

    private fun askCancel(p: Peer) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("לבטל את השליחה ל-${p.name}?")
            .setPositiveButton("ביטול השליחה") { _, _ -> SendService.cancel(this, p) }
            .setNegativeButton("המשך", null).show()
    }

    private fun renderHints() {
        hintBox.removeAllViews()
        val tips = ArrayList<String>()
        if (hiddenCount > 0) tips += "יש $hiddenCount מכשירים שגלויים רק לאנשי הקשר שלהם. כדי שיופיעו כאן – בטלפון: שיתוף מהיר › מי יכול לשתף איתך › כולם."
        when (discovery?.bleState) {
            "off" -> tips += "Bluetooth כבוי – טלפונים מופיעים רק כשהוא דולק."
            "unsupported", "failed" -> tips += "המכשיר לא יכול לשדר אות Bluetooth לטלפונים. בטלפון פותחים שיתוף מהיר › \"קבלה\" (או גלוי לכולם) כדי שיופיע."
        }
        tips += "שני המכשירים צריכים להיות באותה רשת Wi-Fi."
        tips.forEach { hintBox.addView(text("• $it", 13f, color = Ink.GRAY).apply { lp(top = 10) }) }
        if (discovery?.bleState == "off") {
            hintBox.addView(button("הדלקת Bluetooth", primary = false, small = true) {
                runCatching {
                    @Suppress("DEPRECATION") startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }.apply { lp(WRAP, top = 8) })
        }
    }

    companion object { const val REQ_PICK = 31 }
}

/** Wraps square tiles into rows, right to left. */
class FlowGrid(ctx: android.content.Context, private val gap: Int) : android.view.ViewGroup(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val maxW = MeasureSpec.getSize(w)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams
            c.measure(MeasureSpec.makeMeasureSpec(if (lp != null && lp.width > 0) lp.width else maxW, if (lp != null && lp.width > 0) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap; rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(maxW - x - c.measuredWidth, y, maxW - x, y + c.measuredHeight)
            x += c.measuredWidth + gap; rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
