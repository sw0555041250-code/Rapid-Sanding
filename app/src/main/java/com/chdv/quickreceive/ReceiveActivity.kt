package com.chdv.quickreceive

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/** "Ready to receive": visible to everyone while this screen is open, like opening Receive on a phone. */
class ReceiveActivity : BaseActivity() {
    private val refresh: () -> Unit = { render() }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Live.receiveScreen = true
        ReceiverService.refresh(this)
        Live.listen(refresh)
        render()
    }

    override fun onPause() {
        Live.unlisten(refresh)
        Live.receiveScreen = false
        ReceiverService.refresh(this)
        super.onPause()
    }

    private fun render() {
        val p = Prefs(this)
        val c = page("קבלה", back = true, key = "receive")
        val hero = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; lp(top = 20) }
        val ring = text("↓", 64f, bold = true, color = Ink.WHITE).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = rounded(Ink.BLACK, radius = dp(90).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
        }
        hero.addView(ring)
        hero.addView(text(if (Live.running) "מוכן לקבל" else (Live.error ?: "מתחבר לרשת…"), 28f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp(top = 20) })
        hero.addView(text("גלוי לכולם בשם", 15f, color = Ink.GRAY).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp(top = 10) })
        hero.addView(text(p.deviceName, 24f, bold = true).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; lp() })
        c.addView(hero)

        for ((id, pair) in Live.pending) {
            val info = pair.second
            val got = Live.progress[id]
            val card = card().apply { lp(top = 20) }
            card.addView(text("מ-${info.sender}", 13f, color = Ink.GRAY))
            card.addView(text(ReceiverService.describe(info), 18f, bold = true))
            if (got == null) {
                card.addView(text("קוד אימות: ${info.pin}", 15f).apply { lp(top = 4) })
                val row = hbox().apply { lp(top = 12) }
                row.addView(button("קבלה") { ReceiverService.acceptFromUi(this, id) }, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
                row.addView(button("דחייה", primary = false) { ReceiverService.rejectFromUi(this, id) }, LinearLayout.LayoutParams(0, WRAP, 1f))
                card.addView(row)
            } else {
                val pct = (got * 100 / info.totalBytes.coerceAtLeast(1)).toInt()
                card.addView(text("מקבל · $pct%", 16f, bold = true).apply { lp(top = 6) })
            }
            c.addView(card)
        }

        c.addView(text("בטלפון: בוחרים קובץ › שיתוף › Quick Share › \"${p.deviceName}\".\nשני המכשירים באותה רשת Wi-Fi. כשיוצאים מהמסך הזה חוזרים להגדרה \"מי יכול לשתף איתך\".", 14f, color = Ink.GRAY).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER; lp(top = 28)
        })
    }
}
