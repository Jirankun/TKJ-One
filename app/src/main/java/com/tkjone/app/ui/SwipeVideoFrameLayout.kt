package com.tkjone.app.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

class SwipeVideoFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    var onSwipe: ((direction: Int) -> Unit)? = null
    var excludedView: android.view.View? = null

    private var downX = 0f
    private var downY = 0f
    private var interceptingSwipe = false
    private val touchSlop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }
    private val excludedTouchArea by lazy {
        excludedView?.let { Rect(it.left, it.top, it.right, it.bottom) }
            ?: Rect(0, 0, 0, 0)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                interceptingSwipe = false
                excludedTouchArea.set(excludedView?.left ?: 0, excludedView?.top ?: 0, excludedView?.right ?: 0, excludedView?.bottom ?: 0)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                // Jika sentuhan berada di area terkecuali, abaikan swipe detection
                if (excludedTouchArea.contains(event.x.toInt(), event.y.toInt())) {
                    interceptingSwipe = false
                    return false
                }
                if (!interceptingSwipe && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                    interceptingSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> interceptingSwipe = false
        }
        return interceptingSwipe
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!excludedTouchArea.contains(event.x.toInt(), event.y.toInt())) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        onSwipe?.invoke(if (dx < 0) 1 else -1)
                    }
                }
                interceptingSwipe = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                interceptingSwipe = false
                return true
            }
        }
        return true
    }
}