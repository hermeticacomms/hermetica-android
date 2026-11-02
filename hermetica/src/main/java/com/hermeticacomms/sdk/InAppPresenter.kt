package com.hermeticacomms.sdk

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The default renderer: plain Android views in a Dialog, so apps need neither Compose nor AppCompat. */
internal class InAppPresenter(
    private val activity: Activity,
    private val m: InAppMessage,
    private val report: (type: String, button: String?) -> Unit,
    private val onButton: (InAppButton) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val s = m.style
    private val textColor = color(s.textColor, Color.BLACK)
    private val accent = color(s.accentColor, Color.BLACK)
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun show() {
        val banner = m.layout.startsWith("banner")
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(if (s.dismissible) 32 else 20), dp(20), dp(20))
        }
        for (b in m.blocks) content.addView(block(b, dialog))
        val card = FrameLayout(activity).apply {
            background = GradientDrawable().apply { setColor(color(s.background, Color.WHITE)); cornerRadii = radii() }
            addView(ScrollView(activity).apply { addView(content) })
            if (s.dismissible) addView(Button(activity).apply {
                text = "×"
                textSize = 22f
                contentDescription = "Close"
                setTextColor(textColor)
                plain()
                setOnClickListener { dialog.cancel() }
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        // A modal floats with a margin; the other layouts span the screen width.
        val root = FrameLayout(activity).apply { if (m.layout == "modal") setPadding(dp(24), 0, dp(24), 0); addView(card) }
        dialog.setContentView(root)
        dialog.setCancelable(s.dismissible)
        dialog.setCanceledOnTouchOutside(s.dismissible && !banner)
        dialog.setOnCancelListener { report("dismiss", null) }
        dialog.setOnDismissListener { onClosed() }
        dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(MATCH_PARENT, if (m.layout == "fullscreen") MATCH_PARENT else WRAP_CONTENT)
            w.setGravity(when (m.layout) { "banner_top" -> Gravity.TOP; "banner_bottom", "bottom_sheet" -> Gravity.BOTTOM; else -> Gravity.CENTER })
            if (banner) { // the app stays usable around a banner
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            }
        }
        report("impression", null)
    }

    private fun radii(): FloatArray {
        val r = dp(s.radius).toFloat()
        return when (m.layout) {
            "modal" -> FloatArray(8) { r }
            "bottom_sheet" -> floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            else -> FloatArray(8)
        }
    }

    private fun block(b: InAppBlock, dialog: Dialog): View = when (b.type) {
        "heading" -> text(b.text, 20f, bold = true, gap = 8)
        "text" -> text(b.text, 16f, bold = false, gap = 12)
        "image" -> image(b)
        else -> LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for (x in b.buttons) addView(button(x, dialog), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    private fun text(value: String?, size: Float, bold: Boolean, gap: Int) = TextView(activity).apply {
        text = value ?: ""
        textSize = size
        setTextColor(textColor)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(gap))
    }

    private fun image(b: InAppBlock): View {
        val v = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = b.alt ?: ""
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(160)).apply { bottomMargin = dp(12) }
        }
        val url = b.url ?: return v
        InAppImages.cached(url)?.let { v.setImageBitmap(it); return v }
        val width = activity.resources.displayMetrics.widthPixels
        Thread {
            val bmp = InAppImages.load(url, width, dp(160))
            if (bmp != null) v.post { v.setImageBitmap(bmp) }
        }.start()
        return v
    }

    private fun button(x: InAppButton, dialog: Dialog) = Button(activity).apply {
        val primary = x.style != "secondary"
        text = x.label
        setTextColor(if (primary) Color.WHITE else accent)
        setTypeface(typeface, Typeface.BOLD)
        plain()
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            setColor(if (primary) accent else Color.TRANSPARENT)
            setStroke(dp(1), accent)
            cornerRadius = dp(minOf(s.radius, 10)).toFloat()
        }
        setOnClickListener {
            report("click", x.label)
            dialog.dismiss()
            onButton(x)
        }
    }

    /** Drop the platform button look (caps, minimum size, grey background, elevation). */
    private fun Button.plain() {
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        background = ColorDrawable(Color.TRANSPARENT)
    }

    private fun color(hex: String, fallback: Int): Int = try {
        // #rgb is valid CSS but not for parseColor
        Color.parseColor(if (hex.length == 4 && hex[0] == '#') "#" + hex.drop(1).map { "$it$it" }.joinToString("") else hex)
    } catch (_: IllegalArgumentException) {
        fallback
    }
}
