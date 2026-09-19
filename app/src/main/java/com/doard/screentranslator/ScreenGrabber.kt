package com.doard.screentranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * MediaProjection の VirtualDisplay をセッション中ずっと保持し、最新フレームを1枚だけ手元に置いておく。
 * Android 14 以降は同意1回につき VirtualDisplay を1つしか作れないため、キャプチャのたびに作り直さない。
 */
class ScreenGrabber(private val context: Context, projection: MediaProjection) {
    private val thread = HandlerThread("ScreenGrabber").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Object()

    private var reader: ImageReader
    private val virtualDisplay: VirtualDisplay
    private var latest: Image? = null
    private var frameCount = 0L

    /**
     * Bitmap へ写すときの作業バッファ。WQHD 級だと1枚あたり十数MB あり、キャプチャのたびに
     * `allocateDirect` すると連続翻訳でネイティブメモリを食い潰す。足りている限り使い回す。
     * `lock` の中からのみ触ること。
     */
    private var copyBuffer: ByteBuffer? = null

    init {
        val (width, height, dpi) = displaySize()
        reader = newReader(width, height)
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenTranslator", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    /** これ以降に届いたフレームを待つための目印。自分の UI を隠す直前に取る。 */
    fun frameMark(): Long = synchronized(lock) { frameCount }

    /**
     * [mark] 以降の新しいフレームを最大 [timeoutMs] 待ち、Bitmap にして返す。
     * 画面に変化がなく新フレームが来なければ、手元の最新フレームを使う。
     */
    suspend fun grab(mark: Long, timeoutMs: Long = 600): Bitmap? = withContext(Dispatchers.Default) {
        ensureSize()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (synchronized(lock) { frameCount } == mark && SystemClock.uptimeMillis() < deadline) {
            delay(16)
        }
        // 非表示アニメーションの途中フレームを避けるため少しだけ待つ
        delay(50)
        synchronized(lock) { latest?.let(::toBitmap) }
    }

    fun release() {
        virtualDisplay.release()
        synchronized(lock) {
            latest?.close()
            latest = null
            copyBuffer = null
            reader.close()
        }
        thread.quitSafely()
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    null
                } ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    if (r !== reader) {
                        image.close()
                        return@synchronized
                    }
                    latest?.close()
                    latest = image
                    frameCount++
                }
            }, handler)
        }

    /** 画面回転などで解像度が変わっていたら VirtualDisplay を追従させる。 */
    private fun ensureSize() {
        val (width, height, dpi) = displaySize()
        synchronized(lock) {
            if (reader.width == width && reader.height == height) return
            val old = reader
            reader = newReader(width, height)
            virtualDisplay.resize(width, height, dpi)
            virtualDisplay.surface = reader.surface
            latest?.close()
            latest = null
            old.close()
        }
    }

    private fun displaySize(): Triple<Int, Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return Triple(size.x, size.y, context.resources.displayMetrics.densityDpi)
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val stridedWidth = plane.rowStride / pixelStride
        // 最終行は rowStride 分の余白が無いことがあるため、必要サイズのバッファにコピーしてから読む
        val source = plane.buffer.duplicate().apply { rewind() }
        val needed = maxOf(stridedWidth * image.height * pixelStride, source.remaining())
        val buffer = copyBuffer?.takeIf { it.capacity() >= needed }
            ?: ByteBuffer.allocateDirect(needed).also { copyBuffer = it }
        buffer.clear()
        buffer.put(source)
        buffer.rewind()
        val padded = Bitmap.createBitmap(stridedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (stridedWidth == image.width) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }
}
