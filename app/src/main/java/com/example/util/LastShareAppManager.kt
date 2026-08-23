package com.example.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log

data class LastSharedApp(
    val packageName: String,
    val className: String?,
    val appName: String,
    val iconBitmap: Bitmap?
)

class ShareTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        try {
            val chosenComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
            }

            if (chosenComponent != null) {
                LastShareAppManager.saveLastSharedApp(context, chosenComponent)
            }
        } catch (e: Exception) {
            Log.w("ShareTargetReceiver", "Failed to record chosen component: ${e.message}")
        }
    }
}

object LastShareAppManager {
    private const val PREFS_NAME = "pdf_share_prefs"
    private const val KEY_PACKAGE = "last_share_pkg"
    private const val KEY_CLASS = "last_share_cls"
    private const val KEY_NAME = "last_share_name"
    private const val ACTION_SHARE_CHOSEN = "com.example.ACTION_SHARE_CHOSEN"

    fun saveLastSharedApp(context: Context, componentName: ComponentName) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(componentName.packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PACKAGE, componentName.packageName)
                .putString(KEY_CLASS, componentName.className)
                .putString(KEY_NAME, label)
                .apply()
        } catch (e: Exception) {
            Log.w("LastShareAppManager", "Error saving last shared app: ${e.message}")
        }
    }

    fun getLastSharedApp(context: Context): LastSharedApp? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null) ?: return null
        val cls = prefs.getString(KEY_CLASS, null)
        val name = prefs.getString(KEY_NAME, null) ?: "アプリ"

        return try {
            val pm = context.packageManager
            val iconDrawable = if (cls != null) {
                try {
                    pm.getActivityIcon(ComponentName(pkg, cls))
                } catch (e: Exception) {
                    pm.getApplicationIcon(pkg)
                }
            } else {
                pm.getApplicationIcon(pkg)
            }
            val bitmap = drawableToBitmap(iconDrawable)
            LastSharedApp(
                packageName = pkg,
                className = cls,
                appName = name,
                iconBitmap = bitmap
            )
        } catch (e: Exception) {
            // App might have been uninstalled
            null
        }
    }

    fun createShareChooserIntent(context: Context, text: String, title: String = "テキストを共有"): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        val receiverIntent = Intent(context, ShareTargetReceiver::class.java).apply {
            action = ACTION_SHARE_CHOSEN
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            receiverIntent,
            flags
        )

        return Intent.createChooser(sendIntent, title, pendingIntent.intentSender)
    }

    fun directShare(context: Context, text: String, lastApp: LastSharedApp): Boolean {
        return try {
            val directIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (!lastApp.className.isNullOrBlank()) {
                    component = ComponentName(lastApp.packageName, lastApp.className)
                } else {
                    setPackage(lastApp.packageName)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(directIntent)
            true
        } catch (e: Exception) {
            Log.w("LastShareAppManager", "Direct share failed: ${e.message}")
            false
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val bmp = drawable.bitmap
            // Return software copy if hardware config
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
                return bmp.copy(Bitmap.Config.ARGB_8888, false)
            }
            return bmp
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width.coerceIn(48, 192), height.coerceIn(48, 192), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
