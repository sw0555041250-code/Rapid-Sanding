package com.chdv.quickreceive

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** "X wants to send you…" — shown full screen, also over the lock screen. */
class ConsentActivity : BaseActivity() {
    private var id = -1
    private val refresh: () -> Unit = { if (!Live.pending.containsKey(id) || Live.progress.containsKey(id)) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    override fun onResume() { super.onResume(); Live.listen(refresh); refresh() }
    override fun onPause() { Live.unlisten(refresh); super.onPause() }

    private fun show(i: Intent) {
        id = i.getIntExtra(ReceiverService.EXTRA_ID, -1)
        val info = Live.pending[id]?.second ?: run { finish(); return }
        val c = page("בקשת קבלה", back = false)
        c.addView(text(info.sender, 32f, bold = true).apply { lp(top = 10) })
        c.addView(text("רוצה לשלוח לך:", 18f, color = Ink.GRAY))

        val list = card().apply { lp(top = 18) }
        if (info.files.isEmpty() && info.text != null) {
            list.addView(text(if (info.text.type == 2) "קישור" else "טקסט", 18f, bold = true))
            if (info.text.title.isNotBlank()) list.addView(text(info.text.title, 15f, color = Ink.GRAY))
        }
        info.files.take(8).forEachIndexed { n, f ->
            if (n > 0) list.addView(divider())
            list.addView(text(f.name, 17f, bold = true).apply { maxLines = 2 })
            list.addView(text(formatSize(f.size), 14f, color = Ink.GRAY))
        }
        if (info.files.size > 8) list.addView(text("ועוד ${info.files.size - 8} קבצים…", 15f, color = Ink.GRAY).apply { lp(top = 8) })
        if (info.files.size > 1) list.addView(text("סה\"כ ${formatSize(info.totalBytes)}", 15f, bold = true).apply { lp(top = 10) })
        c.addView(list)

        val pin = card(filled = true).apply { lp(top = 16); gravity = Gravity.CENTER_HORIZONTAL }
        pin.addView(text("קוד אימות", 15f, color = Ink.WHITE).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp() })
        pin.addView(text(info.pin.toCharArray().joinToString("  "), 40f, bold = true, color = Ink.WHITE).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER; textDirection = View.TEXT_DIRECTION_LTR; lp()
        })
        pin.addView(text("ודא שזה הקוד שמופיע בטלפון", 13f, color = Ink.WHITE).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp() })
        c.addView(pin)

        val row = hbox().apply { lp(top = 22) }
        row.addView(button("קבלה") { ReceiverService.acceptFromUi(this, id); finish() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        row.addView(button("דחייה", primary = false) { ReceiverService.rejectFromUi(this, id); finish() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(row)
        c.addView(button("קבלה תמיד מ-${info.sender}", primary = false, small = true) {
            val prefs = Prefs(this)
            prefs.trusted = prefs.trusted + info.sender
            ReceiverService.acceptFromUi(this, id); finish()
        }.apply { lp(top = 12) })
        c.addView(text("יתווסף ל\"המכשירים שלי\" – מהם מקבלים בלי לשאול", 12f, color = Ink.GRAY).apply { lp(top = 4) })
    }
}
