package com.doard.screentranslator

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
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
    // 重ね表示のウィンドウは、Service の Context ではなくウィンドウコンテキストから扱うのが Android の作法。
    // Service の Context から currentWindowMetrics を読むと、Android 12 以降で StrictMode の
    // IncorrectContextUseViolation になり、分割画面や折りたたみでは値そのものもずれ得る。
    private val windowContext: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
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
        val screen = screenSize()
        // 横画面の右端に置いたまま終了し、次に縦画面で復元すると画面外に出たきり触れなくなる。
        // onScreenChanged は回転したときしか呼ばれないので、復元時にも画面内へ収める。
        x = (saved?.first ?: (screen.x - sizePx)).coerceIn(0, (screen.x - sizePx).coerceAtLeast(0))
        y = (saved?.second ?: (screen.y / 3)).coerceIn(0, (screen.y - sizePx).coerceAtLeast(0))
    }

    val isShown get() = view != null

    /**
     * 画面の大きさ。Service の `resources.displayMetrics` は画面を回しても更新されないことがあり、
     * 縦画面の幅のままスナップ先を計算してボタンが画面外に飛ぶ。ウィンドウ側の値を見る。
     */
    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            Point().also {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(it)
            }
        }

    /**
     * @return 表示できたか。`Settings.canDrawOverlays` が true でも `addView` を拒否するメーカーがあり、
     *   投げっぱなしにするとキャプチャのセッションごと道連れになる。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (view != null) return true
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
        return try {
            windowManager.addView(button, params)
            view = button
            true
        } catch (e: Exception) {
            Log.w(TAG, "overlay addView was rejected", e)
            false
        }
    }

    fun remove() {
        setBusy(false)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    /** 回転などで画面の大きさが変わったとき、ボタンを画面内に戻す。 */
    fun onScreenChanged() {
        val v = view ?: return
        val screen = screenSize()
        params.x = params.x.coerceIn(0, (screen.x - sizePx).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.y - sizePx).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(v, params) }
        Prefs.setButtonPosition(context, params.x, params.y)
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
            val screen = screenSize()
            params.x = if (params.x + sizePx / 2 < screen.x / 2) 0 else screen.x - sizePx
            params.y = params.y.coerceIn(0, (screen.y - sizePx).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(v, params) }
            Prefs.setButtonPosition(context, params.x, params.y)
        }
    }

    companion object {
        private const val TAG = "FloatingButton"
    }
}
