package com.chdv.quickreceive

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self update from GitHub Releases. Every push to the repository builds a signed APK and
 * publishes release v1.0.<build>; all builds share one signing key, so an update installs
 * over the existing app and keeps all data.
 */
object Updater {
    const val REPO = "sw0555041250-code/Rapid-Sanding"
    const val APP_TO = "לשיתוף מהיר"
    const val APP = "שיתוף מהיר"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var busy = false

    private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun autoEnabled(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

    fun version(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"
    private fun build(v: String) = v.trim().removePrefix("v").substringAfterLast('.').toIntOrNull() ?: 0

    /** Once a day, quietly; asks only when there is something new. */
    fun dailyCheck(a: Activity) {
        if (!autoEnabled(a)) return
        val last = prefs(a).getLong("last", 0)
        if (System.currentTimeMillis() - last < 20L * 3600 * 1000) return
        prefs(a).edit().putLong("last", System.currentTimeMillis()).apply()
        check(a, silent = true)
    }

    fun check(a: Activity, silent: Boolean) {
        if (busy) return
        busy = true
        if (!silent) Toast.makeText(a, "בודק אם יש עדכון…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val c = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = o.optString("tag_name")
                val notes = o.optString("body").take(600)
                val assets = o.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val u = assets.getJSONObject(i).optString("browser_download_url")
                    if (u.endsWith(".apk")) { apk = u; break }
                }
                val local = version(a)
                main.post {
                    busy = false
                    if (a.isFinishing) return@post
                    if (apk != null && build(tag) > build(local)) offer(a, tag.removePrefix("v"), local, notes, apk)
                    else if (!silent) dialog(a, "אין עדכון חדש", "הגרסה שלך ($local) היא העדכנית ביותר.")
                }
            } catch (e: Exception) {
                main.post {
                    busy = false
                    if (!silent && !a.isFinishing) dialog(a, "לא ניתן לבדוק עדכונים", "ודאו שהמכשיר מחובר לאינטרנט ונסו שוב.")
                }
            }
        }.start()
    }

    private fun dialog(a: Activity, title: String, msg: String) {
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(title).setMessage(msg).setPositiveButton("אישור", null).show()
    }

    private fun offer(a: Activity, newV: String, oldV: String, notes: String, url: String) {
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("יש עדכון $APP_TO")
            .setMessage("גרסה חדשה: $newV\nהגרסה שלך: $oldV\n\n$notes\n\nכל הנתונים וההגדרות נשמרים.")
            .setPositiveButton("הורדה והתקנה") { _, _ -> install(a, url) }
            .setNegativeButton("לא עכשיו", null)
            .show()
    }

    private fun install(a: Activity, url: String) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("צריך אישור חד-פעמי")
                .setMessage("כדי להתקין עדכונים צריך לאשר $APP_TO להתקין אפליקציות. אחרי האישור חוזרים ולוחצים שוב על \"בדיקת עדכונים\".")
                .setPositiveButton("פתיחת ההגדרה") { _, _ ->
                    runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) }
                }
                .setNegativeButton("ביטול", null).show()
            return
        }
        Toast.makeText(a, "מוריד את העדכון…", Toast.LENGTH_SHORT).show()
        val ctx = a.applicationContext
        Thread {
            try {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(ctx.packageName) }
                val sessionId = pi.createSession(params)
                pi.openSession(sessionId).use { session ->
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                    val total = c.contentLengthLong
                    var got = 0L; var lastStep = -1
                    c.inputStream.use { ins ->
                        session.openWrite("update.apk", 0, if (total > 0) total else -1).use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); got += n
                                if (total > 0) {
                                    val step = (got * 4 / total).toInt()
                                    if (step != lastStep && step < 4) { lastStep = step; val p = step * 25; main.post { Toast.makeText(ctx, "מוריד… $p%", Toast.LENGTH_SHORT).show() } }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    // the installer adds its result to this intent, so it must be mutable (Android 12+)
                    val mutable = if (android.os.Build.VERSION.SDK_INT >= 31) 0x02000000 else 0
                    val result = PendingIntent.getBroadcast(ctx, sessionId, Intent(ctx, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(ctx, "ההורדה נכשלה – בדקו את החיבור ונסו שוב", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}

/** The system installer reports back here: ask the user to confirm, or show the result. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(ctx, "העדכון הותקן", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> Toast.makeText(ctx, "ההתקנה נכשלה: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}", Toast.LENGTH_LONG).show()
        }
    }
}
