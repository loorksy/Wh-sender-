package cloud.lork.mursal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cloud.lork.mursal.BuildConfig
import cloud.lork.mursal.R
import cloud.lork.mursal.data.UpdateClient
import cloud.lork.mursal.databinding.ActivityHomeBinding
import cloud.lork.mursal.service.BulkAccessibilityService
import cloud.lork.mursal.service.FloatingBubbleService
import cloud.lork.mursal.util.AppUpdateManager
import cloud.lork.mursal.util.ShortcutHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup / control panel. Reached from the launcher only when a permission is
 * missing, from the notification, or from the pinned shortcut. No license gate.
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var updateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateManager = AppUpdateManager(this)

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnStartBubble.setOnClickListener { startBubbleAndMinimize() }
        binding.btnAddShortcut.setOnClickListener {
            ShortcutHelper.requestPinShortcut(this)
        }
        binding.btnCheckUpdate.setOnClickListener { checkUpdateManual() }

        requestNotificationPermission()
        ShortcutHelper.ensureDynamicShortcut(this)
        handleUpdateIntent(intent)
        updateManager.check(showDialogIfAvailable = true, notifyIfAvailable = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        if (::updateManager.isInitialized) {
            updateManager.unregister()
        }
        super.onDestroy()
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AppUpdateManager.EXTRA_SHOW_UPDATE, false) == true) {
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { UpdateClient().fetchUpdateInfo() }
                if (info != null && UpdateClient().isNewer(info)) {
                    updateManager.showUpdateDialog(info)
                }
            }
        }
    }

    private fun checkUpdateManual() {
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { UpdateClient().fetchUpdateInfo() }
            binding.btnCheckUpdate.isEnabled = true
            if (info == null) {
                Toast.makeText(this@HomeActivity, R.string.network_error, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (UpdateClient().isNewer(info)) {
                updateManager.showUpdateDialog(info)
            } else {
                Toast.makeText(
                    this@HomeActivity,
                    getString(R.string.update_latest) + " (${BuildConfig.VERSION_NAME})",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun refreshStatus() {
        val accessOn = isAccessibilityEnabled()
        binding.textAccessStatus.text = if (accessOn) {
            getString(R.string.accessibility_on)
        } else {
            getString(R.string.accessibility_off)
        }
        binding.btnAccessibility.visibility = if (accessOn) View.GONE else View.VISIBLE

        val overlayOn = Settings.canDrawOverlays(this)
        binding.textOverlayStatus.text = if (overlayOn) {
            getString(R.string.overlay_on)
        } else {
            getString(R.string.overlay_off)
        }
        binding.btnOverlay.visibility = if (overlayOn) View.GONE else View.VISIBLE
    }

    private fun startBubbleAndMinimize() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.accessibility_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_required, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        FloatingBubbleService.start(this)
        ShortcutHelper.ensureDynamicShortcut(this)
        Toast.makeText(this, R.string.bubble_started, Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (BulkAccessibilityService.isEnabled()) return true
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val expected = "$packageName/${BulkAccessibilityService::class.java.canonicalName}"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            1001,
        )
    }
}
