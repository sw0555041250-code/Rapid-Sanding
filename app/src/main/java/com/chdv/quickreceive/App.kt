package com.chdv.quickreceive

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "מצב קבלה", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "בקשות קבלה", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "קבצים שהתקבלו", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_INCOMING = "incoming"
        const val CH_DONE = "done"
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val cr = ctx.contentResolver

    var deviceName: String
        get() = sp.getString("deviceName", null)
            ?: (Settings.Global.getString(cr, "device_name") ?: Build.MODEL ?: "BOOX")
        set(v) = sp.edit().putString("deviceName", v).apply()

    /** "everyone", "temp" (everyone for 10 minutes) or "off" */
    var visibility: String
        get() {
            val v = sp.getString("visibility", null) ?: if (sp.getBoolean("enabled", true)) "everyone" else "off"
            return if (v == "temp" && System.currentTimeMillis() > tempUntil) "off" else v
        }
        set(v) = sp.edit().putString("visibility", v).apply()

    var tempUntil: Long
        get() = sp.getLong("tempUntil", 0)
        set(v) = sp.edit().putLong("tempUntil", v).apply()

    /** Visible right now (also while the receive screen is open). */
    val visible: Boolean get() = visibility != "off" || Live.receiveScreen

    /** "My devices": incoming from these names is accepted without asking. */
    var trusted: Set<String>
        get() = sp.getStringSet("trusted", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("trusted", v).apply()

    var autoAccept: Boolean
        get() = sp.getBoolean("autoAccept", false)
        set(v) = sp.edit().putBoolean("autoAccept", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", true)
        set(v) = sp.edit().putBoolean("startOnBoot", v).apply()

    var openAfter: Boolean
        get() = sp.getBoolean("openAfter", false)
        set(v) = sp.edit().putBoolean("openAfter", v).apply()

    /** A folder chosen by the user (SAF tree), or null for Download/קבלה מהירה. */
    var folderUri: String?
        get() = sp.getString("folderUri", null)
        set(v) = sp.edit().putString("folderUri", v).apply()

    var folderLabel: String
        get() = sp.getString("folderLabel", null) ?: "הורדות › $DEFAULT_FOLDER"
        set(v) = sp.edit().putString("folderLabel", v).apply()

    fun history(): List<JSONObject> {
        val arr = JSONArray(sp.getString("history", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun addHistory(items: List<JSONObject>) {
        val all = items + history()
        val arr = JSONArray()
        all.take(60).forEach { arr.put(it) }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() = sp.edit().putString("history", "[]").apply()

    companion object {
        const val DEFAULT_FOLDER = "קבלה מהירה"
    }
}

fun mimeFor(name: String, given: String): String {
    if (given.isNotBlank() && given != "application/octet-stream" && given != "*/*") return given
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
        "epub" -> "application/epub+zip"
        "fb2" -> "application/x-fictionbook+xml"
        "mobi" -> "application/x-mobipocket-ebook"
        "djvu" -> "image/vnd.djvu"
        else -> "application/octet-stream"
    }
}

/** Saves into Download/קבלה מהירה through MediaStore (no permission needed on Android 10+). */
class DownloadsStorage(private val ctx: Context) : Storage {
    override fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(-1)

    override fun create(name: String, mime: String, size: Long): Sink {
        val cr = ctx.contentResolver
        val type = mimeFor(name, mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.DEFAULT_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("לא ניתן ליצור קובץ")
        val out = cr.openOutputStream(uri) ?: throw IllegalStateException("לא ניתן לכתוב לקובץ")
        return object : Sink {
            override val out: OutputStream = out.buffered(256 * 1024)
            override fun finish(): Saved {
                this.out.close()
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val finalName = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: name
                return Saved(finalName, size, uri.toString(), type, "הורדות › ${Prefs.DEFAULT_FOLDER}")
            }
            override fun abort() {
                runCatching { this.out.close() }
                runCatching { cr.delete(uri, null, null) }
            }
        }
    }
}

/** Saves into a folder the user picked (works on the memory card too). */
class TreeStorage(private val ctx: Context, private val tree: Uri, private val label: String) : Storage {
    override fun freeBytes(): Long = -1

    override fun create(name: String, mime: String, size: Long): Sink {
        val cr = ctx.contentResolver
        val type = mimeFor(name, mime)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, type, name) ?: throw IllegalStateException("לא ניתן ליצור קובץ בתיקייה")
        val out = cr.openOutputStream(doc) ?: throw IllegalStateException("לא ניתן לכתוב לקובץ")
        return object : Sink {
            override val out: OutputStream = out.buffered(256 * 1024)
            override fun finish(): Saved {
                this.out.close()
                val finalName = cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: name
                return Saved(finalName, size, doc.toString(), type, label)
            }
            override fun abort() {
                runCatching { this.out.close() }
                runCatching { DocumentsContract.deleteDocument(cr, doc) }
            }
        }
    }
}

fun storageFor(ctx: Context): Storage {
    val p = Prefs(ctx)
    val tree = p.folderUri?.let { Uri.parse(it) }
    if (tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }) {
        return TreeStorage(ctx, tree, p.folderLabel)
    }
    return DownloadsStorage(ctx)
}

fun savedToJson(s: Saved, sender: String) = JSONObject().apply {
    put("kind", "file"); put("name", s.name); put("size", s.size); put("uri", s.uri)
    put("mime", s.mime); put("where", s.location); put("from", sender); put("time", System.currentTimeMillis())
}

fun sentToJson(names: List<String>, to: String, size: Long) = JSONObject().apply {
    put("kind", "sent"); put("name", if (names.size == 1) names[0] else "${names.size} קבצים"); put("to", to); put("size", size)
    put("time", System.currentTimeMillis())
}

fun textToJson(text: String, sender: String) = JSONObject().apply {
    put("kind", "text"); put("text", text); put("from", sender); put("time", System.currentTimeMillis())
}
