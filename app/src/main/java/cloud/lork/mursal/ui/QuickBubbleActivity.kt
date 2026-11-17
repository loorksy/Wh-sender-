package cloud.lork.mursal.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.lork.mursal.R
import cloud.lork.mursal.service.BulkAccessibilityService
import cloud.lork.mursal.service.FloatingBubbleService
import cloud.lork.mursal.util.ShortcutHelper

/**
 * Transparent launcher — the app's only entry point ("popup-only").
 *
 * Tapping the app icon starts the floating bubble directly when the required
 * permissions are granted. If a permission is missing, the setup screen
 * (HomeActivity) opens so the user can grant it. No license check of any kind.
 */
class QuickBubbleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.accessibility_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, HomeActivity::class.java))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, HomeActivity::class.java))
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            finish()
            return
        }

        startBubbleAndExit()
    }

    private fun startBubbleAndExit() {
        FloatingBubbleService.start(this)
        ShortcutHelper.ensureDynamicShortcut(this)
        Toast.makeText(this, R.string.bubble_started, Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
        finish()
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

    companion object {
        const val ACTION_QUICK_BUBBLE = "cloud.lork.mursal.action.QUICK_BUBBLE"
    }
}
