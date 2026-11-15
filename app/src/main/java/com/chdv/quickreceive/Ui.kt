package com.chdv.quickreceive

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Colors tuned for E-ink: pure black and white, one mid gray for secondary text. */
object Ink {
    const val BLACK = Color.BLACK
    const val WHITE = Color.WHITE
    val GRAY = Color.rgb(0x55, 0x55, 0x55)
    val LINE = Color.rgb(0xB0, 0xB0, 0xB0)
    val FILL = Color.rgb(0xEE, 0xEE, 0xEE)
}

fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.text(s: CharSequence, size: Float = 17f, bold: Boolean = false, color: Int = Ink.BLACK): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        textDirection = View.TEXT_DIRECTION_RTL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setLineSpacing(0f, 1.15f)
    }

fun rounded(fill: Int, stroke: Int = 0, strokeWidth: Int = 0, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (pad > 0) setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** A card: white, thick black outline, large radius. */
fun Context.card(filled: Boolean = false): LinearLayout = vbox().apply {
    background = if (filled) rounded(Ink.BLACK, radius = dp(22).toFloat())
    else rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(22).toFloat())
    setPadding(dp(18), dp(16), dp(18), dp(16))
}

fun Context.button(label: String, primary: Boolean = true, small: Boolean = false, onClick: () -> Unit): TextView =
    text(label, if (small) 15f else 18f, bold = true, color = if (primary) Ink.WHITE else Ink.BLACK).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = if (primary) rounded(Ink.BLACK, radius = dp(30).toFloat())
        else rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(30).toFloat())
        val v = if (small) 8 else 14
        setPadding(dp(18), dp(v), dp(18), dp(v))
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(Ink.LINE)
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) }
}

fun Context.space(h: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

fun View.lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0, weight: Float = 0f): View {
    layoutParams = LinearLayout.LayoutParams(w, h, weight).apply {
        topMargin = context.dp(top); bottomMargin = context.dp(bottom)
    }
    return this
}

/** A label + value row that toggles; drawn as a pill so nothing animates on E-ink. */
fun Context.toggleRow(title: String, subtitle: String?, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val row = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
    val texts = vbox()
    texts.addView(text(title, 17f, bold = true))
    if (subtitle != null) texts.addView(text(subtitle, 14f, color = Ink.GRAY))
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
    var state = initial
    val pill = text("", 14f, bold = true).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        minWidth = dp(64)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }
    fun paint() {
        pill.text = if (state) "פועל" else "כבוי"
        pill.setTextColor(if (state) Ink.WHITE else Ink.BLACK)
        pill.background = if (state) rounded(Ink.BLACK, radius = dp(20).toFloat())
        else rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(20).toFloat())
    }
    paint()
    row.addView(pill)
    row.isClickable = true
    row.setOnClickListener { state = !state; paint(); onChange(state) }
    return row
}

abstract class BaseActivity : Activity() {
    lateinit var content: LinearLayout
    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.decorView.setBackgroundColor(Ink.WHITE)
        window.setFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Ink.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    fun page(title: String, back: Boolean): LinearLayout {
        val root = vbox().apply { setBackgroundColor(Ink.WHITE); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val bar = hbox().apply { setPadding(dp(20), dp(18), dp(20), dp(8)) }
        if (back) {
            bar.addView(text("→", 26f, bold = true).apply {
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { finish() }
            })
        }
        titleView = text(title, 28f, bold = true)
        bar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(bar)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        content = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(28)) }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        return content
    }

    fun setPageTitle(t: String) { titleView.text = t }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    fun askText(title: String, initial: String, hint: String = "", onOk: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(initial); setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT
            textDirection = View.TEXT_DIRECTION_RTL
            setSelection(initial.length)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(title).setView(box)
            .setPositiveButton("שמירה") { _, _ -> onOk(edit.text.toString().trim()) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    fun info(title: String, message: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(title).setMessage(message).setPositiveButton("הבנתי", null).show()
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes בתים"
    bytes < 1024 * 1024 -> String.format("%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
