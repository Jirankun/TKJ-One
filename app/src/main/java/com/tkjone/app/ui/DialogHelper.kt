package com.tkjone.app.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.tkjone.app.R

object DialogHelper {

    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
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

        val isDark = ThemeHelper.isDarkMode(context as? android.app.Activity ?: return, com.tkjone.app.service.AppThemeMode.DARK)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 16f)
            setPadding(dp(24), dp(24), dp(24), 0)
        }

        container.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(ThemeHelper.getLabelColor(isDark))
            setTypeface(null, Typeface.BOLD)
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(ThemeHelper.getSepColor(isDark))
        })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
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
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        })

        container.addView(buttonRow)

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(280), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
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

        val isDark = ThemeHelper.isDarkMode(context as? android.app.Activity ?: return, com.tkjone.app.service.AppThemeMode.DARK)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeHelper.createRoundedBackground(ThemeHelper.getBg2Color(isDark), 16f)
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }

        container.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(ThemeHelper.getLabelColor(isDark))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        container.addView(TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(ThemeHelper.getSecLabelColor(isDark))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(16))
        })

        container.addView(TextView(context).apply {
            text = context.getString(R.string.dialog_ok)
            textSize = 15f
            setTextColor(ThemeHelper.ACCENT_BLUE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                dialog.dismiss()
                onDismiss?.invoke()
            }
        })

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(280), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
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
