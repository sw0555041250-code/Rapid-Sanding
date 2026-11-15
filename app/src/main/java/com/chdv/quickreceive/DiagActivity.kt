package com.chdv.quickreceive

import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.net.Inet4Address

/** Health check: what works and what doesn't, with a report that can be copied and sent. */
class DiagActivity : BaseActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var testing = false
    private var testResult: String? = null
    private var discovery: Discovery? = null
    private val refresh: () -> Unit = { render() }

    override fun onResume() { super.onResume(); Live.listen(refresh); render() }
    override fun onPause() { Live.unlisten(refresh); stopTest(); super.onPause() }

    private fun lines(): List<Pair<String, String>> {
        val p = Prefs(this)
        val out = ArrayList<Pair<String, String>>()
        out += "גרסה" to "${Updater.version(this)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ip = net?.let { cm.getLinkProperties(it) }?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.joinToString { it.hostAddress ?: "" } ?: ""
        out += "Wi-Fi" to (if (wifi) "מחובר · $ip" else "לא מחובר ל-Wi-Fi ✗")
        out += "נראות" to "${p.visibility}${if (Live.receiveScreen) " (מסך קבלה פתוח)" else ""} · ${if (p.visible) "אמור להיות גלוי" else "לא גלוי"}"
        out += "רישום ברשת" to (if (Live.running) "פעיל ✓ · ${Live.registeredName} · פורט ${Live.port}" else "לא פעיל ✗ ${Live.error ?: ""}")
        out += "Multicast" to (if (Live.multicast) "פעיל ✓" else "לא פעיל ✗")
        val bt = BluetoothAdapter.getDefaultAdapter()
        out += "Bluetooth" to when {
            bt == null -> "אין במכשיר"
            !bt.isEnabled -> "כבוי ✗"
            else -> "דולק ✓ · שידור LE: ${if (bt.isMultipleAdvertisementSupported) "נתמך ✓" else "לא נתמך ✗"}"
        }
        val loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locOn = runCatching { getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)
        out += "זיהוי טלפונים משתפים" to "${Live.bleScan} · הרשאת מיקום: ${if (loc) "יש" else "אין"} · מיקום: ${if (locOn) "דולק" else "כבוי"}"
        if (Live.lastPhoneNearby > 0) out += "טלפון משתף נראה לאחרונה" to android.text.format.DateUtils.getRelativeTimeSpanString(Live.lastPhoneNearby).toString()
        return out
    }

    private fun report(): String =
        lines().joinToString("\n") { "${it.first}: ${it.second}" } +
            (testResult?.let { "\n\nבדיקת רשת:\n$it" } ?: "") +
            "\n\nיומן:\n" + Live.logText()

    private fun render() {
        val c = page("בדיקת תקינות", back = true, key = "diag")
        val card = card()
        lines().forEachIndexed { i, (k, v) ->
            if (i > 0) card.addView(divider())
            card.addView(text(k, 13f, color = Ink.GRAY))
            card.addView(text(v, 16f, bold = true))
        }
        c.addView(card)

        val row = hbox().apply { lp(top = 14) }
        row.addView(button("העתקת הדוח", small = true) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("דוח שיתוף מהיר", report()))
            Toast.makeText(this, "הדוח הועתק – אפשר להדביק ולשלוח", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(button("הכרזה מחדש", primary = false, small = true) { ReceiverService.refresh(this, "ידני") }, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(row)

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            c.addView(button("לאפשר זיהוי טלפונים ב-Bluetooth", primary = false, small = true) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 5)
            }.apply { lp(top = 10) })
            c.addView(text("אנדרואיד דורש הרשאת \"מיקום\" כדי לקלוט אותות Bluetooth. ההרשאה משמשת רק כדי לדעת מתי טלפון בסביבה פותח שיתוף – ואז להכריז על המכשיר מחדש.", 12f, color = Ink.GRAY).apply { lp(top = 4) })
        }

        c.addView(text("בדיקת רשת", 20f, bold = true).apply { lp(top = 22, bottom = 6) })
        c.addView(text("מחפש 15 שניות את כל מכשירי השיתוף המהיר ברשת (ומשדר לטלפונים לחשוף את עצמם). בזמן הבדיקה פתחו בטלפון את השיתוף המהיר.", 13f, color = Ink.GRAY))
        c.addView(button(if (testing) "בודק…" else "התחלת בדיקה", small = true) { if (!testing) startTest() }.apply { lp(WRAP, top = 8) })
        testResult?.let { c.addView(text(it, 15f).apply { lp(top = 8) }) }

        c.addView(text("יומן", 20f, bold = true).apply { lp(top = 22, bottom = 6) })
        c.addView(text(Live.logText().ifEmpty { "ריק" }, 12f, color = Ink.GRAY).apply { textDirection = View.TEXT_DIRECTION_ANY_RTL })
    }

    private fun startTest() {
        testing = true; testResult = null
        var found: List<Peer> = emptyList(); var hidden = 0
        val d = Discovery(this, Live.endpointId) { list, h -> found = list; hidden = h }
        discovery = d
        d.start()
        render()
        main.postDelayed({
            val ble = d.bleState
            stopTest()
            val sb = StringBuilder()
            sb.append("שידור Bluetooth לטלפונים: ").append(when (ble) { "on" -> "פעל ✓"; "off" -> "Bluetooth כבוי ✗"; "unsupported" -> "לא נתמך במכשיר ✗"; else -> "נכשל ✗ (קוד ${d.bleError})" }).append('\n')
            if (found.isEmpty() && hidden == 0) sb.append("לא נמצא אף מכשיר ברשת.")
            else {
                sb.append("נמצאו: ${found.size} גלויים")
                if (hidden > 0) sb.append(", $hidden גלויים רק לאנשי קשר")
                found.forEach { sb.append("\n• ${it.name} (${it.host.hostAddress})") }
            }
            testResult = sb.toString()
            render()
        }, 15_000)
    }

    private fun stopTest() {
        discovery?.stop(); discovery = null
        if (testing) { testing = false; main.removeCallbacksAndMessages(null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ReceiverService.refresh(this, "הרשאה חדשה")
        render()
    }
}
