package com.chdv.quickreceive

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject

class MainActivity : BaseActivity() {
    private lateinit var prefs: Prefs
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ReceiverService.refresh(this)
    }

    override fun onResume() {
        super.onResume()
        Live.listen(refresh)
        render()
    }

    override fun onPause() {
        Live.unlisten(refresh)
        super.onPause()
    }

    private fun section(s: String) = text(s, 20f, bold = true).apply { lp(top = 26, bottom = 10) }

    private fun render() {
        val c = page("שיתוף מהיר", back = false, key = "main")

        // ---- the two big actions ----
        val actions = hbox()
        actions.addView(bigAction("↑", "שליחה", true) { startActivity(Intent(this, SendActivity::class.java)) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        actions.addView(bigAction("↓", "קבלה", false) { startActivity(Intent(this, ReceiveActivity::class.java)) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(actions)

        // ---- status ----
        val status = when {
            Live.error != null && prefs.visible -> Live.error!!
            !prefs.visible -> "לא זמין – אף אחד לא רואה את המכשיר"
            Live.running -> "גלוי בשם \"${prefs.deviceName}\""
            else -> "מתחבר לרשת…"
        }
        c.addView(text((if (prefs.visible && Live.running) "●  " else "○  ") + status, 15f, bold = true).apply { lp(top = 14) })

        // ---- incoming transfers ----
        for ((id, pair) in Live.pending) c.addView(incomingCard(id, pair.second))

        // ---- who can share with you ----
        c.addView(section("מי יכול לשתף איתך"))
        val vis = card()
        val mins = ((prefs.tempUntil - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(1)
        vis.addView(radio("כולם", "כל מכשיר בסביבה יכול לראות אותך ולשלוח אליך", prefs.visibility == "everyone") { setVisibility("everyone") })
        vis.addView(divider())
        vis.addView(radio("כולם – ל-10 דקות", if (prefs.visibility == "temp") "פעיל – עוד $mins דק׳, ואז חוזר ל\"לא זמין\"" else "אחר כך חוזר ל\"לא זמין\"", prefs.visibility == "temp") { setVisibility("temp") })
        vis.addView(divider())
        vis.addView(radio("לא זמין", "אף אחד לא רואה אותך. אפשר עדיין לשלוח, ולקבל דרך מסך \"קבלה\"", prefs.visibility == "off") { setVisibility("off") })
        c.addView(vis)
        c.addView(text("\"אנשי קשר\" ו\"המכשירים שלך\" בשיתוף של גוגל דורשים חשבון גוגל ולכן לא קיימים כאן. במקומם: \"המכשירים שלי\" בהגדרות – מהם מקבלים בלי לשאול.", 13f, color = Ink.GRAY).apply { lp(top = 8) })

        // ---- history ----
        c.addView(section("לאחרונה"))
        renderHistory(c)

        // ---- settings ----
        c.addView(section("הגדרות"))
        c.addView(settingsCard())
        c.addView(section("איך זה עובד"))
        c.addView(helpCard())
    }

    private fun bigAction(glyph: String, label: String, filled: Boolean, onClick: () -> Unit): View {
        val fg = if (filled) Ink.WHITE else Ink.BLACK
        val box = vbox().apply {
            gravity = Gravity.CENTER
            background = if (filled) rounded(Ink.BLACK, radius = dp(24).toFloat()) else rounded(Ink.WHITE, Ink.BLACK, dp(3), dp(24).toFloat())
            setPadding(dp(10), dp(18), dp(10), dp(18))
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(text(glyph, 34f, bold = true, color = fg).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp() })
        box.addView(text(label, 20f, bold = true, color = fg).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp() })
        return box
    }

    private fun radio(title: String, sub: String, on: Boolean, onClick: () -> Unit): View {
        val row = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
        row.addView(text(if (on) "◉" else "○", 24f, bold = true).apply { setPadding(0, 0, dp(14), 0) })
        val t = vbox()
        t.addView(text(title, 17f, bold = true))
        t.addView(text(sub, 13f, color = Ink.GRAY))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    private fun setVisibility(v: String) {
        if (v == "temp") prefs.tempUntil = System.currentTimeMillis() + 10 * 60 * 1000
        prefs.visibility = v
        Live.error = null
        ReceiverService.refresh(this)
        render()
    }

    private fun incomingCard(id: Int, info: Incoming): View {
        val got = Live.progress[id]
        val card = card().apply { lp(top = 14) }
        card.addView(text("מ-${info.sender}", 13f, color = Ink.GRAY))
        card.addView(text(ReceiverService.describe(info), 18f, bold = true))
        if (got == null) {
            card.addView(text("קוד אימות: ${info.pin}", 15f).apply { lp(top = 4) })
            val row = hbox().apply { lp(top = 12) }
            row.addView(button("קבלה") { ReceiverService.acceptFromUi(this, id) })
            row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            row.addView(button("דחייה", primary = false) { ReceiverService.rejectFromUi(this, id) })
            card.addView(row)
        } else {
            val pct = (got * 100 / info.totalBytes.coerceAtLeast(1)).toInt()
            card.addView(progressBar(pct).apply { lp(top = 10) })
            card.addView(text("מקבל · $pct% · ${formatSize(got)} מתוך ${formatSize(info.totalBytes)}", 14f, color = Ink.GRAY).apply { lp(top = 6) })
        }
        return card
    }

    private fun progressBar(pct: Int): View {
        val outer = LinearLayout(this).apply {
            background = rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(8).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(18))
        }
        outer.addView(View(this).apply { background = rounded(Ink.BLACK, radius = dp(6).toFloat()) }, LinearLayout.LayoutParams(0, MATCH, pct.coerceIn(1, 100).toFloat()))
        outer.addView(View(this), LinearLayout.LayoutParams(0, MATCH, (100 - pct).coerceIn(0, 99).toFloat()))
        return outer
    }

    private fun renderHistory(c: LinearLayout) {
        val items = prefs.history()
        if (items.isEmpty()) {
            c.addView(text("עדיין לא נשלח ולא התקבל כלום.", 15f, color = Ink.GRAY)); return
        }
        val card = card()
        items.take(20).forEachIndexed { i, o ->
            if (i > 0) card.addView(divider())
            card.addView(historyRow(o))
        }
        c.addView(card)
        c.addView(text("ניקוי הרשימה", 14f, color = Ink.GRAY).apply { lp(top = 8); setOnClickListener { prefs.clearHistory(); render() } })
    }

    private fun historyRow(o: JSONObject): View {
        val row = hbox()
        val t = vbox()
        val kind = o.optString("kind")
        val whenStr = DateUtils.getRelativeTimeSpanString(o.optLong("time"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        when (kind) {
            "text" -> {
                t.addView(text("↓ " + o.optString("text").take(140), 16f, bold = true).apply { maxLines = 3 })
                t.addView(text("טקסט מ-${o.optString("from")} · $whenStr", 13f, color = Ink.GRAY))
            }
            "sent" -> {
                t.addView(text("↑ " + o.optString("name"), 16f, bold = true).apply { maxLines = 2 })
                t.addView(text("נשלח ל-${o.optString("to")} · ${formatSize(o.optLong("size"))} · $whenStr", 13f, color = Ink.GRAY))
            }
            else -> {
                t.addView(text("↓ " + o.optString("name"), 16f, bold = true).apply { maxLines = 2 })
                t.addView(text("מ-${o.optString("from")} · ${formatSize(o.optLong("size"))} · $whenStr", 13f, color = Ink.GRAY))
            }
        }
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        when (kind) {
            "text" -> row.addView(button("העתקה", primary = false, small = true) { copy(o.optString("text")) })
            "file", "" -> row.addView(button("פתיחה", primary = false, small = true) { open(o.optString("uri"), o.optString("mime")) })
        }
        return row
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("שיתוף מהיר", s))
        Toast.makeText(this, "הועתק", Toast.LENGTH_SHORT).show()
        if (s.trim().startsWith("http://") || s.trim().startsWith("https://")) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.trim()))) }
    }

    private fun open(uri: String, mime: String) {
        try {
            startActivity(Intent.createChooser(ReceiverService.openIntent(this, uri, mime), "פתיחה באמצעות"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "אין אפליקציה שפותחת את הקובץ", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "הקובץ כבר לא נמצא", Toast.LENGTH_SHORT).show()
        }
    }

    private fun settingsCard(): View {
        val card = card()
        card.addView(clickRow("שם המכשיר", prefs.deviceName) { renameDevice() })
        card.addView(divider())
        card.addView(clickRow("תיקיית הקבלה", prefs.folderLabel) { pickFolder() })
        card.addView(divider())
        val trusted = prefs.trusted
        card.addView(clickRow("המכשירים שלי", if (trusted.isEmpty()) "מהם מקבלים בלי לשאול. מוסיפים מחלון הקבלה" else trusted.joinToString(" · ")) { manageTrusted() })
        card.addView(divider())
        card.addView(toggleRow("קבלה מכולם בלי לשאול", "לא מומלץ כשגלויים לכולם", prefs.autoAccept) { prefs.autoAccept = it })
        card.addView(divider())
        card.addView(toggleRow("פתיחה אוטומטית", "קובץ אחד שמתקבל נפתח מיד", prefs.openAfter) { prefs.openAfter = it })
        card.addView(divider())
        card.addView(toggleRow("להתחיל עם הדלקת המכשיר", "כשמוגדר \"כולם\"", prefs.startOnBoot) { prefs.startOnBoot = it })
        card.addView(divider())
        card.addView(clickRow("פעולה ברקע", "בלי הגבלת סוללה – כדי לקבל גם כשהמסך כבוי") { askBattery() })
        return card
    }

    private fun clickRow(title: String, value: String, onClick: () -> Unit): View {
        val row = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
        val t = vbox()
        t.addView(text(title, 17f, bold = true))
        t.addView(text(value, 14f, color = Ink.GRAY))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(text("‹", 24f, bold = true))
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    private fun manageTrusted() {
        val list = prefs.trusted.sorted()
        if (list.isEmpty()) {
            info("המכשירים שלי", "כשמגיעה בקשת קבלה, לוחצים \"קבלה תמיד מהמכשיר הזה\" – ומאז הקבצים ממנו מתקבלים בלי לשאול.\n\nהזיהוי הוא לפי שם המכשיר.")
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("להסיר מהמכשירים שלי")
            .setItems(list.toTypedArray()) { _, w -> prefs.trusted = prefs.trusted - list[w]; render() }
            .setNegativeButton("סגירה", null).show()
    }

    private fun helpCard(): View {
        val card = card()
        listOf(
            "↑" to "שליחה: בכל אפליקציה › שיתוף › שיתוף מהיר, או כפתור \"שליחה\" כאן. בטלפון שמקבל – המסך דולק והשיתוף המהיר גלוי.",
            "↓" to "קבלה: בטלפון › שיתוף › Quick Share › בוחרים \"${prefs.deviceName}\". כאן קופץ חלון עם קוד – מאשרים.",
            "≋" to "שני המכשירים באותה רשת Wi-Fi, ו-Bluetooth דולק (כדי שטלפונים יתעוררו ויופיעו).",
        ).forEach { (g, s) ->
            val row = hbox().apply { setPadding(0, dp(5), 0, dp(5)) }
            row.addView(text(g, 15f, bold = true, color = Ink.WHITE).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                background = rounded(Ink.BLACK, radius = dp(14).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            row.addView(text(s, 14f).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(row)
        }
        card.addView(text("ב-BOOX: אפליקציות › ניהול אפליקציות › שיתוף מהיר › לבטל \"הקפאה\".", 13f, color = Ink.GRAY).apply { lp(top = 8) })
        return card
    }

    private fun renameDevice() {
        askText("השם שיופיע במכשירים אחרים", prefs.deviceName) {
            if (it.isNotEmpty()) { prefs.deviceName = it; ReceiverService.refresh(this); render() }
        }
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, REQ_FOLDER) } catch (e: ActivityNotFoundException) { info("לא נתמך", "אין במכשיר בורר תיקיות.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.folderUri?.let { old ->
            if (old != uri.toString()) runCatching {
                contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        prefs.folderUri = uri.toString()
        val id = DocumentsContract.getTreeDocumentId(uri)
        val path = id.substringAfter(':', "").replace("/", " › ")
        val root = if (id.substringBefore(':') == "primary") "זיכרון פנימי" else "כרטיס זיכרון"
        prefs.folderLabel = if (path.isEmpty()) root else "$root › $path"
        render()
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            info("הכול מוכן", "האפליקציה כבר רשאית לפעול ברקע.\n\nב-BOOX כדאי גם לוודא שהיא לא מוקפאת: אפליקציות › ניהול אפליקציות › שיתוף מהיר › לבטל הקפאה.")
            return
        }
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    companion object { const val REQ_FOLDER = 7 }
}
