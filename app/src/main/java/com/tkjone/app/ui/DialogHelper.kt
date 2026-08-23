package com.tkjone.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tkjone.app.R

object DialogHelper {

    fun showToast(context: Context, message: String, duration: Int = 2000) {
        try {
            val activity = context as? android.app.Activity ?: return
            // Remove existing pill if any
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val existing = root.findViewWithTag<android.view.View>("toast_pill")
            if (existing != null) root.removeView(existing)

            // Inflate pill banner
            val inflater = android.view.LayoutInflater.from(context)
            val pill = inflater.inflate(R.layout.toast_pill, null)
            pill.tag = "toast_pill"
            val tv = pill.findViewById<TextView>(R.id.toast_text)
            tv.text = message

            // Add to root with layout params
            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = (48 * context.resources.displayMetrics.density).toInt()
            }
            root.addView(pill, lp)

            // Animate in
            pill.alpha = 0f; pill.translationY = 20f
            pill.animate().alpha(1f).translationY(0f).setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

            // Auto remove after duration
            pill.postDelayed({
                pill.animate().alpha(0f).translationY(20f).setDuration(200)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { try { root.removeView(pill) } catch (_: Exception) {} }
                    .start()
            }, duration.toLong())
        } catch (_: Exception) {}
    }

    fun showConfirmDialog(
        context: Context,
        title: String,
        content: String,
        confirmLabel: String,
        cancelLabel: String,
        confirmColor: Int = ThemeHelper.ACCENT_RED,
        onConfirm: () -> Unit
    ) {
        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) return
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val isDark = true

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 16f)
            setPadding(dp(24), dp(24), dp(24), 0)
        }

        container.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(ThemeHelper.getLabelColor(isDark))
            typeface = resources.getFont(R.font.torus_bold)
            gravity = Gravity.CENTER
        })

        container.addView(TextView(context).apply {
            text = content
            textSize = 13f
            setTextColor(ThemeHelper.getSecLabelColor(isDark))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })

        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(ThemeHelper.getSepColor(isDark))
        })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }

        buttonRow.addView(TextView(context).apply {
            text = cancelLabel
            textSize = 15f
            setTextColor(ThemeHelper.getSecLabelColor(isDark))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { dialog.dismiss() }
        })

        buttonRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(ThemeHelper.getSepColor(isDark))
        })

        buttonRow.addView(TextView(context).apply {
            text = confirmLabel
            textSize = 15f
            setTextColor(confirmColor)
            typeface = resources.getFont(R.font.torus_bold)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { dialog.dismiss(); onConfirm() }
        })

        container.addView(buttonRow)

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(280), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setWindowAnimations(R.style.DialogAnimation)
        }
        dialog.show()
    }

    fun showInfoDialog(
        context: Context,
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) return
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val isDark = true

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 16f)
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }

        // Title
        container.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(ThemeHelper.getLabelColor(isDark))
            typeface = resources.getFont(R.font.torus_bold)
            gravity = Gravity.CENTER
        })

        // Scrollable message area with fixed max height
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(350) // Fixed max height — prevents dialog from growing to full screen
            ).apply { setMargins(0, dp(12), 0, dp(12)) }
            isFillViewport = true
        }

        val messageTv = TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(ThemeHelper.getSecLabelColor(isDark))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setTextIsSelectable(true)
        }

        scrollView.addView(messageTv)
        container.addView(scrollView)

        // Separator
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(ThemeHelper.getSepColor(isDark))
        })

        // OK button
        container.addView(TextView(context).apply {
            text = context.getString(R.string.dialog_ok)
            textSize = 15f
            setTextColor(ThemeHelper.ACCENT_BLUE)
            typeface = resources.getFont(R.font.torus_bold)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener { dialog.dismiss(); onDismiss?.invoke() }
        })

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setWindowAnimations(R.style.DialogAnimation)
        }
        dialog.show()
    }

    fun dp(value: Int): Int {
        return (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }

    fun dpFloat(value: Float): Float {
        return value * android.content.res.Resources.getSystem().displayMetrics.density
    }
}
