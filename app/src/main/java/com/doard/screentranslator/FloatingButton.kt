package com.doard.screentranslator

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.roundToInt

/** どのアプリの上にも表示される、ドラッグ可能な丸い翻訳ボタン。 */
class FloatingButton(private val context: Context, private val onTap: () -> Unit) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val sizePx = (52 * density).roundToInt()
    private var view: ImageView? = null
    private var pulse: ObjectAnimator? = null

    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val saved = Prefs.buttonPosition(context)
        x = saved?.first ?: (context.resources.displayMetrics.widthPixels - sizePx)
        y = saved?.second ?: (context.resources.displayMetrics.heightPixels / 3)
    }

    val isShown get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val padding = (13 * density).roundToInt()
        val button = ImageView(context).apply {
            setImageResource(R.drawable.ic_translate)
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61A73E8.toInt())
            }
            elevation = 6 * density
            contentDescription = context.getString(R.string.tile_label)
            setOnTouchListener(DragListener())
        }
        windowManager.addView(button, params)
        view = button
    }

    fun remove() {
        setBusy(false)
        view?.let { windowManager.removeView(it) }
        view = null
    }

    /** キャプチャに写り込まないよう一時的に隠す */
    fun setHidden(hidden: Boolean) {
        view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun setBusy(busy: Boolean) {
        pulse?.cancel()
        pulse = null
        val v = view ?: return
        if (busy) {
            pulse = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.35f).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            v.alpha = 1f
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge(v)
                    } else {
                        v.performClick()
                        onTap()
                    }
                }
            }
            return true
        }

        private fun snapToEdge(v: View) {
            val metrics = context.resources.displayMetrics
            params.x = if (params.x + sizePx / 2 < metrics.widthPixels / 2) 0 else metrics.widthPixels - sizePx
            params.y = params.y.coerceIn(0, (metrics.heightPixels - sizePx).coerceAtLeast(0))
            windowManager.updateViewLayout(v, params)
            Prefs.setButtonPosition(context, params.x, params.y)
        }
    }
}
