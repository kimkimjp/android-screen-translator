package com.doard.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager

/**
 * 訳文を元の文字ブロックの位置に重ねて表示する全画面オーバーレイ。
 * 短くタップ／戻る操作で閉じる。長押ししている間は訳文を隠して原文を確認できる。
 */
class ResultOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ResultView? = null

    val isShowing get() = view != null

    fun show(blocks: List<TranslatedBlock>) {
        dismiss()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val v = ResultView(context, blocks) { dismiss() }
        windowManager.addView(v, params)
        v.requestFocus()
        view = v
    }

    fun dismiss() {
        view?.let { windowManager.removeView(it) }
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class ResultView(
        context: Context,
        blocks: List<TranslatedBlock>,
        private val onClose: () -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB202124.toInt() }
        private val scrimPaint = Paint().apply { color = 0x33000000 }
        private val padding = 2 * density
        private val corner = 4 * density
        private val items = blocks.map { layoutBlock(it) }
        private val location = IntArray(2)
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private var peeking = false
        private val peekRunnable = Runnable {
            peeking = true
            invalidate()
        }

        private class Item(val box: RectF, val layout: StaticLayout)

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        /** ブロックの枠に収まるよう文字サイズを縮める。最小サイズでも収まらなければ枠を下に広げる。 */
        private fun layoutBlock(block: TranslatedBlock): Item {
            val src = block.source.box
            val width = (src.width() - padding * 2).toInt().coerceAtLeast((24 * density).toInt())
            val availableHeight = src.height() - padding * 2
            val lineHeight = src.height().toFloat() / block.source.lineCount
            val maxSize = (lineHeight * 0.8f).coerceIn(MIN_SP * density, MAX_SP * density)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

            var size = maxSize
            var layout: StaticLayout
            while (true) {
                paint.textSize = size
                layout = buildLayout(block.translation, paint, width)
                if (layout.height <= availableHeight || size <= MIN_SP * density) break
                size = (size - density).coerceAtLeast(MIN_SP * density)
            }
            val box = RectF(
                src.left.toFloat(),
                src.top.toFloat(),
                src.left + width + padding * 2,
                src.top + maxOf(src.height().toFloat(), layout.height + padding * 2),
            )
            return Item(box, layout)
        }

        private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()

        override fun onDraw(canvas: Canvas) {
            if (peeking) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            // キャプチャ座標は画面全体基準。このウィンドウの画面上の位置分ずらす
            getLocationOnScreen(location)
            canvas.save()
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            for (item in items) {
                canvas.drawRoundRect(item.box, corner, corner, boxPaint)
                canvas.save()
                canvas.translate(item.box.left + padding, item.box.top + padding)
                item.layout.draw(canvas)
                canvas.restore()
            }
            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> postDelayed(peekRunnable, longPressTimeout)
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(peekRunnable)
                    if (peeking) {
                        peeking = false
                        invalidate()
                    } else {
                        onClose()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(peekRunnable)
                    peeking = false
                    invalidate()
                }
            }
            return true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onClose()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        companion object {
            private const val MIN_SP = 9f
            private const val MAX_SP = 22f
        }
    }
}
