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
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : BaseActivity() {
    private lateinit var prefs: Prefs
    private lateinit var hero: LinearLayout
    private lateinit var activeBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val c = page("קבלה מהירה", back = false)
        hero = vbox(); c.addView(hero)
        activeBox = vbox(); c.addView(activeBox)
        c.addView(sectionTitle("התקבלו לאחרונה"))
        historyBox = vbox(); c.addView(historyBox)
        c.addView(sectionTitle("הגדרות"))
        c.addView(settingsCard())
        c.addView(sectionTitle("איך שולחים?"))
        c.addView(helpCard())
        if (prefs.enabled) ReceiverService.start(this)
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

    private fun sectionTitle(s: String) = text(s, 20f, bold = true).apply { lp(top = 26, bottom = 10) }

    private fun render() {
        renderHero()
        renderActive()
        renderHistory()
    }

    private fun renderHero() {
        hero.removeAllViews()
        val on = prefs.enabled
        val card = card(filled = on)
        val fg = if (on) Ink.WHITE else Ink.BLACK
        val status = when {
            !on -> "הקבלה כבויה"
            Live.error != null -> Live.error!!
            Live.running -> "מוכן לקבל"
            else -> "מתחבר לרשת…"
        }
        card.addView(text(if (on && Live.running) "●  פעיל" else "○  ממתין", 14f, bold = true, color = fg))
        card.addView(text(status, 30f, bold = true, color = fg).apply { lp(top = 6) })
        card.addView(text(
            if (on) "בטלפון: שיתוף › Quick Share (שיתוף מהיר) › בחר \"${prefs.deviceName}\""
            else "הפעל כדי שהמכשיר יופיע ברשימת השיתוף המהיר של הטלפון",
            15f, color = fg).apply { lp(top = 6) })
        val row = hbox().apply { lp(top = 16) }
        val toggle = text(if (on) "כיבוי" else "הפעלה", 17f, bold = true, color = if (on) Ink.BLACK else Ink.WHITE).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = rounded(if (on) Ink.WHITE else Ink.BLACK, radius = dp(30).toFloat())
            setPadding(dp(26), dp(12), dp(26), dp(12))
            setOnClickListener {
                prefs.enabled = !on
                if (prefs.enabled) ReceiverService.start(this@MainActivity) else ReceiverService.stop(this@MainActivity)
                Live.error = null
                render()
            }
        }
        row.addView(toggle)
        row.addView(text("שם: ${prefs.deviceName}", 15f, color = fg).apply {
            setPadding(dp(16), 0, 0, 0)
            setOnClickListener { renameDevice() }
        })
        card.addView(row)
        hero.addView(card)
    }

    private fun renderActive() {
        activeBox.removeAllViews()
        for ((id, pair) in Live.pending) {
            val info = pair.second
            val got = Live.progress[id]
            val card = card().apply { lp(top = 14) }
            card.addView(text(info.sender, 13f, color = Ink.GRAY))
            card.addView(text(ReceiverService.describe(info), 18f, bold = true))
            if (got == null) {
                card.addView(text("קוד אימות: ${info.pin}", 15f).apply { lp(top = 4) })
                val row = hbox().apply { lp(top = 12) }
                row.addView(button("קבלה") { ReceiverService.acceptFromUi(this, id) })
                row.addView(space(1).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
                row.addView(button("דחייה", primary = false) { ReceiverService.rejectFromUi(this, id) })
                card.addView(row)
            } else {
                val pct = (got * 100 / info.totalBytes.coerceAtLeast(1)).toInt()
                card.addView(progressBar(pct).apply { lp(top = 10) })
                card.addView(text("$pct% · ${formatSize(got)} מתוך ${formatSize(info.totalBytes)}", 14f, color = Ink.GRAY).apply { lp(top = 6) })
            }
            activeBox.addView(card)
        }
    }

    private fun progressBar(pct: Int): View {
        val outer = LinearLayout(this).apply {
            background = rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(8).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(18))
        }
        val inner = View(this).apply { background = rounded(Ink.BLACK, radius = dp(6).toFloat()) }
        outer.addView(inner, LinearLayout.LayoutParams(0, MATCH, pct.coerceIn(1, 100).toFloat()))
        outer.addView(View(this), LinearLayout.LayoutParams(0, MATCH, (100 - pct).coerceIn(0, 99).toFloat()))
        return outer
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val items = prefs.history()
        if (items.isEmpty()) {
            historyBox.addView(text("עדיין לא התקבל כלום. קבצים שיתקבלו יופיעו כאן.", 15f, color = Ink.GRAY))
            return
        }
        val card = card()
        items.take(25).forEachIndexed { i, o ->
            if (i > 0) card.addView(divider())
            card.addView(historyRow(o))
        }
        historyBox.addView(card)
        historyBox.addView(text("ניקוי הרשימה", 14f, color = Ink.GRAY).apply {
            lp(top = 8)
            setOnClickListener { prefs.clearHistory(); render() }
        })
    }

    private fun historyRow(o: JSONObject): View {
        val row = hbox()
        val texts = vbox()
        val isText = o.optString("kind") == "text"
        val when_ = DateUtils.getRelativeTimeSpanString(o.optLong("time"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        if (isText) {
            texts.addView(text(o.optString("text").take(140), 16f, bold = true).apply { maxLines = 3 })
            texts.addView(text("טקסט · ${o.optString("from")} · $when_", 13f, color = Ink.GRAY))
        } else {
            texts.addView(text(o.optString("name"), 16f, bold = true).apply { maxLines = 2 })
            texts.addView(text("${formatSize(o.optLong("size"))} · ${o.optString("from")} · $when_", 13f, color = Ink.GRAY))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(button(if (isText) "העתקה" else "פתיחה", primary = false, small = true) {
            if (isText) copy(o.optString("text")) else open(o.optString("uri"), o.optString("mime"))
        })
        row.isClickable = true
        row.setOnClickListener { if (isText) copy(o.optString("text")) else open(o.optString("uri"), o.optString("mime")) }
        return row
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("קבלה מהירה", s))
        Toast.makeText(this, "הועתק", Toast.LENGTH_SHORT).show()
        if (s.startsWith("http://") || s.startsWith("https://")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.trim()))) }
        }
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
        card.addView(toggleRow("קבלה בלי לשאול", "קבצים מתקבלים מיד, בלי חלון אישור", prefs.autoAccept) { prefs.autoAccept = it })
        card.addView(divider())
        card.addView(toggleRow("פתיחה אוטומטית", "כשמגיע קובץ אחד – לפתוח אותו מיד", prefs.openAfter) { prefs.openAfter = it })
        card.addView(divider())
        card.addView(toggleRow("הפעלה עם הדלקת המכשיר", null, prefs.startOnBoot) { prefs.startOnBoot = it })
        card.addView(divider())
        card.addView(clickRow("פעולה ברקע", "לאפשר לאפליקציה לרוץ ברקע בלי הגבלת סוללה") { askBattery() })
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

    private fun helpCard(): View {
        val card = card()
        val steps = listOf(
            "1" to "הטלפון והמכשיר מחוברים לאותה רשת Wi-Fi.",
            "2" to "בטלפון: בוחרים קובץ › שיתוף › Quick Share (שיתוף מהיר).",
            "3" to "ברשימה מופיע \"${prefs.deviceName}\" – לוחצים עליו.",
            "4" to "במכשיר מופיע חלון עם קוד – מוודאים שזה אותו קוד בטלפון ולוחצים \"קבלה\".",
        )
        steps.forEach { (n, s) ->
            val row = hbox().apply { setPadding(0, dp(5), 0, dp(5)) }
            row.addView(text(n, 15f, bold = true, color = Ink.WHITE).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                background = rounded(Ink.BLACK, radius = dp(14).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            row.addView(text(s, 15f).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(row)
        }
        card.addView(text("לא מופיע בטלפון? ודאו שהקבלה פעילה כאן, ושב-BOOX האפליקציה לא \"מוקפאת\" (ניהול אפליקציות › הקפאה). לפעמים צריך לסגור ולפתוח שוב את חלון השיתוף בטלפון.", 13f, color = Ink.GRAY).apply { lp(top = 10) })
        return card
    }

    private fun renameDevice() {
        askText("השם שיופיע בטלפון", prefs.deviceName) {
            if (it.isNotEmpty()) {
                prefs.deviceName = it
                if (prefs.enabled) ReceiverService.start(this, ReceiverService.ACTION_REFRESH)
                recreate()
            }
        }
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, REQ_FOLDER) } catch (e: ActivityNotFoundException) {
            info("לא נתמך", "אין במכשיר בורר תיקיות.")
        }
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
        prefs.folderLabel = folderLabel(uri)
        recreate()
    }

    private fun folderLabel(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)  // e.g. "primary:Books/New" or "1234-ABCD:ספרים"
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "").replace("/", " › ")
        val root = if (vol == "primary") "זיכרון פנימי" else "כרטיס זיכרון"
        return if (path.isEmpty()) root else "$root › $path"
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            info("הכול מוכן", "האפליקציה כבר רשאית לפעול ברקע.\n\nב-BOOX כדאי גם לוודא שהיא לא מוקפאת: אפליקציות › ניהול אפליקציות › קבלה מהירה › לבטל הקפאה.")
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
