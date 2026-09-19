package com.doard.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 画面キャプチャのセッションを保持する前景サービス。
 * フローティングボタン・タイル・通知からのキャプチャ要求を受けて「取得 → OCR → 翻訳 → 重ね表示」を行う。
 */
class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    private var floatingButton: FloatingButton? = null
    private lateinit var overlay: ResultOverlay
    private val recognizer by lazy { TextRecognizer() }
    private val translator by lazy { ScreenTranslator() }
    private var captureJob: Job? = null
    private var stoppedByUser = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = ResultOverlay(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_CAPTURE -> capture(intent.getLongExtra(EXTRA_DELAY_MS, 0))
            ACTION_UPDATE_BUTTON -> updateFloatingButton()
            ACTION_STOP -> {
                stoppedByUser = true
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // Android 14 以降は getMediaProjection より前に前景サービス化が必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (projection != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it) }
        if (mp == null) {
            stopSelf()
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            // ステータスバーのチップからの停止や、Android 15 QPR1 以降の画面ロックによる自動停止
            override fun onStop() {
                if (!stoppedByUser) postResumeNotification()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        getSystemService(NotificationManager::class.java).cancel(RESUME_NOTIFICATION_ID)
        projection = mp
        grabber = ScreenGrabber(this, mp)
        setRunning(true)
        updateFloatingButton()

        if (intent.getBooleanExtra(EXTRA_CAPTURE_AFTER_START, false)) {
            capture(intent.getLongExtra(EXTRA_DELAY_MS, 0))
        }
    }

    private fun updateFloatingButton() {
        if (projection != null && Prefs.showFloatingButton(this)) {
            val button = floatingButton ?: FloatingButton(this) { capture(0) }.also { floatingButton = it }
            // 重ね表示を拒否されてもセッションは残す。通知とタイルからは翻訳できる。
            if (!button.show()) toast(R.string.error_overlay)
        } else {
            floatingButton?.remove()
            floatingButton = null
        }
    }

    private fun capture(delayMs: Long) {
        val grabber = grabber ?: return
        if (captureJob?.isActive == true) return
        captureJob = scope.launch {
            val button = floatingButton
            val mark = grabber.frameMark()
            overlay.dismiss()
            button?.setHidden(true)
            if (delayMs > 0) delay(delayMs)

            val bitmap = try {
                grabber.grab(mark)
            } finally {
                button?.setHidden(false)
            }
            if (bitmap == null) {
                toast(R.string.error_no_frame)
                return@launch
            }

            // ボタンが「ある」だけでは駄目で、実際に画面に出ていないと点滅が見えない。
            // 重ね表示を拒否されたときは代わりにトーストで知らせる。
            if (button?.isShown == true) button.setBusy(true) else toast(R.string.translating)
            try {
                val blocks = recognizer.recognize(bitmap, excludeTop = statusBarHeight())
                if (blocks.isEmpty()) {
                    toast(R.string.no_text_found)
                    return@launch
                }
                val translated = translator.translate(blocks) { lang ->
                    val name = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.JAPANESE)
                    Toast.makeText(this@CaptureService, getString(R.string.downloading_model, name), Toast.LENGTH_LONG).show()
                }
                if (translated.isEmpty()) {
                    toast(R.string.nothing_to_translate)
                } else if (!overlay.show(translated)) {
                    toast(R.string.error_overlay)
                }
            } catch (e: Exception) {
                Log.e(TAG, "translation failed", e)
                Toast.makeText(this@CaptureService, getString(R.string.error_translate, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                bitmap.recycle()
                floatingButton?.setBusy(false)
            }
        }
    }

    /** 画面を回すとボタンが画面外に出ることがあるので、位置を入れ直す。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingButton?.onScreenChanged()
    }

    /** 時計や通知アイコンを翻訳しないよう、ステータスバー内の文字は対象外にする */
    private fun statusBarHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).currentWindowMetrics
                .windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** システム側で共有が終わったとき、ワンタップで再開できるようにする */
    private fun postResumeNotification() {
        createChannel()
        val resume = PendingIntent.getActivity(
            this, 3, CaptureActivity.startIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.resume_title))
            .setContentText(getString(R.string.resume_text))
            .setContentIntent(resume)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        createChannel()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val translate = PendingIntent.getActivity(this, 1, CaptureActivity.captureIntent(this), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_translate), translate).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()
    }

    override fun onDestroy() {
        stoppedByUser = true
        scope.cancel()
        overlay.dismiss()
        floatingButton?.remove()
        floatingButton = null
        grabber?.release()
        grabber = null
        projection?.stop()
        projection = null
        recognizer.close()
        translator.close()
        setRunning(false)
        super.onDestroy()
    }

    private fun setRunning(running: Boolean) {
        runningState.value = running
        TileService.requestListeningState(this, ComponentName(this, TranslateTileService::class.java))
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val RESUME_NOTIFICATION_ID = 2

        const val ACTION_START = "com.doard.screentranslator.START"
        const val ACTION_CAPTURE = "com.doard.screentranslator.CAPTURE"
        const val ACTION_UPDATE_BUTTON = "com.doard.screentranslator.UPDATE_BUTTON"
        const val ACTION_STOP = "com.doard.screentranslator.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CAPTURE_AFTER_START = "capture_after_start"
        const val EXTRA_DELAY_MS = "delay_ms"

        private val runningState = MutableStateFlow(false)
        val running: StateFlow<Boolean> = runningState

        fun send(context: Context, action: String) {
            if (!running.value) return
            context.startService(Intent(context, CaptureService::class.java).setAction(action))
        }
    }
}
