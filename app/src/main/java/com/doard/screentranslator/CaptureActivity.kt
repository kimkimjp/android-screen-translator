package com.doard.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * 画面に何も出さない中継アクティビティ。
 * - セッション中ならキャプチャ要求をサービスへ渡す（シェードが閉じ切るのを待つため少し遅らせる）
 * - セッションが無ければ画面キャプチャの同意を取り、サービスを開始する
 */
class CaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val captureNow = intent.getBooleanExtra(EXTRA_CAPTURE, false)

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        if (CaptureService.running.value) {
            if (captureNow) {
                startService(
                    Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_CAPTURE)
                        .putExtra(CaptureService.EXTRA_DELAY_MS, SHADE_CLOSE_DELAY_MS)
                )
            }
            finish()
            return
        }

        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val consent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 「1つのアプリ」ではなく画面全体の共有を既定にする
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQUEST_CONSENT)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONSENT && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureService.EXTRA_CAPTURE_AFTER_START, intent.getBooleanExtra(EXTRA_CAPTURE, false))
                .putExtra(CaptureService.EXTRA_DELAY_MS, SHADE_CLOSE_DELAY_MS)
            startForegroundService(service)
        }
        finish()
    }

    companion object {
        private const val REQUEST_CONSENT = 1
        private const val EXTRA_CAPTURE = "capture"
        private const val SHADE_CLOSE_DELAY_MS = 450L

        /** セッションを開始（未開始なら）してすぐ翻訳する */
        fun captureIntent(context: Context): Intent =
            Intent(context, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_CAPTURE, true)

        /** セッションの開始のみ */
        fun startIntent(context: Context): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_CAPTURE, false)
    }
}
