package com.doard.screentranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** セットアップ画面：権限 → 翻訳モデル → 開始/停止 の順に案内する。 */
class MainActivity : ComponentActivity() {
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var notificationStatus: TextView
    private lateinit var notificationButton: Button
    private lateinit var modelStatus: TextView
    private lateinit var modelButton: Button
    private lateinit var floatingSwitch: Switch
    private lateinit var sessionStatus: TextView
    private lateinit var sessionButton: Button
    private var downloading = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 (targetSdk 35) では edge-to-edge が強制されるため、全バージョンで揃えてインセットを自前で処理する
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        overlayStatus = findViewById(R.id.overlay_status)
        overlayButton = findViewById(R.id.overlay_button)
        notificationStatus = findViewById(R.id.notification_status)
        notificationButton = findViewById(R.id.notification_button)
        modelStatus = findViewById(R.id.model_status)
        modelButton = findViewById(R.id.model_button)
        floatingSwitch = findViewById(R.id.floating_switch)
        sessionStatus = findViewById(R.id.session_status)
        sessionButton = findViewById(R.id.session_button)

        overlayButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        notificationButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        modelButton.setOnClickListener { downloadModels() }

        floatingSwitch.isChecked = Prefs.showFloatingButton(this)
        floatingSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowFloatingButton(this, checked)
            CaptureService.send(this, CaptureService.ACTION_UPDATE_BUTTON)
        }

        sessionButton.setOnClickListener {
            if (CaptureService.running.value) {
                CaptureService.send(this, CaptureService.ACTION_STOP)
            } else {
                startActivity(CaptureActivity.startIntent(this))
            }
        }

        lifecycleScope.launch {
            CaptureService.running.collect { refreshSession() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlayGranted = Settings.canDrawOverlays(this)
        overlayStatus.setText(if (overlayGranted) R.string.status_granted else R.string.status_overlay_required)
        overlayButton.isEnabled = !overlayGranted

        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationStatus.setText(if (notificationGranted) R.string.status_granted else R.string.status_notification_recommended)
        notificationButton.isEnabled = !notificationGranted

        refreshModels()
        refreshSession()
    }

    private fun refreshSession() {
        val running = CaptureService.running.value
        sessionStatus.setText(if (running) R.string.session_running else R.string.session_stopped)
        sessionButton.setText(if (running) R.string.stop else R.string.start)
        sessionButton.isEnabled = running || Settings.canDrawOverlays(this)
    }

    private fun refreshModels() {
        if (downloading) return
        lifecycleScope.launch {
            val missing = missingModels()
            modelStatus.text = if (missing.isEmpty()) {
                getString(R.string.models_ready)
            } else {
                getString(R.string.models_missing, missing.joinToString("・") { displayName(it) })
            }
            modelButton.isEnabled = missing.isNotEmpty()
        }
    }

    private suspend fun missingModels(): List<String> {
        val downloaded = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }
            .toSet()
        return ScreenTranslator.PRELOAD_LANGUAGES.filter { it !in downloaded }
    }

    private fun downloadModels() {
        downloading = true
        modelButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val manager = RemoteModelManager.getInstance()
                for (lang in missingModels()) {
                    modelStatus.text = getString(R.string.models_downloading, displayName(lang))
                    manager.download(TranslateRemoteModel.Builder(lang).build(), DownloadConditions.Builder().build()).await()
                }
            } catch (e: Exception) {
                modelStatus.text = getString(R.string.models_error, e.message ?: "")
                downloading = false
                modelButton.isEnabled = true
                return@launch
            }
            downloading = false
            refreshModels()
        }
    }

    private fun displayName(lang: String) = Locale.forLanguageTag(lang).getDisplayLanguage(Locale.JAPANESE)
}
