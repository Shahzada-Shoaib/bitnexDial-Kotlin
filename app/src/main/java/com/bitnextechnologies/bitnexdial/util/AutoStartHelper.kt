package com.bitnextechnologies.bitnexdial.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper class to handle manufacturer-specific auto-start permissions.
 * Required for phones from Xiaomi, Oppo, Vivo, Huawei, Samsung, Infinix, Tecno, etc.
 * These manufacturers have aggressive battery optimization that prevents apps
 * from starting on boot or running in background.
 */
object AutoStartHelper {
    private const val TAG = "AutoStartHelper"

    // List of known auto-start manager intents for various manufacturers
    private val AUTO_START_INTENTS = listOf(
        // Infinix, Tecno, itel (Transsion)
        Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.home.HomeActivity")),
        Intent().setComponent(ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.home.HomeActivity")),

        // Xiaomi
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),

        // Oppo
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")),

        // Vivo
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),

        // Huawei
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),

        // Samsung
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),

        // OnePlus
        Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),

        // Letv
        Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),

        // Asus
        Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")),

        // Nokia
        Intent().setComponent(ComponentName("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")),

        // HTC
        Intent().setComponent(ComponentName("com.htc.pitroad", "com.htc.pitroad.landingpage.activity.LandingPageActivity")),

        // Meizu
        Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")),
    )

    /**
     * Check if app needs to request battery optimization exemption
     */
    fun needsBatteryOptimizationExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return false
    }

    /**
     * Request battery optimization exemption (standard Android)
     */
    fun requestBatteryOptimizationExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization exemption", e)
            }
        }
        return false
    }

    /**
     * Open battery optimization settings
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
            return false
        }
    }

    /**
     * Try to open manufacturer-specific auto-start settings
     */
    fun openAutoStartSettings(context: Context): Boolean {
        for (intent in AUTO_START_INTENTS) {
            if (isIntentAvailable(context, intent)) {
                try {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    Log.d(TAG, "Opened auto-start settings: ${intent.component}")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start intent: ${intent.component}", e)
                }
            }
        }
        Log.d(TAG, "No manufacturer-specific auto-start settings found")
        return false
    }

    /**
     * Check if there's a manufacturer-specific auto-start manager available
     */
    fun hasAutoStartManager(context: Context): Boolean {
        return AUTO_START_INTENTS.any { isIntentAvailable(context, it) }
    }

    /**
     * Get the manufacturer name for display
     */
    fun getManufacturerName(): String {
        return Build.MANUFACTURER
    }

    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        return try {
            val list = context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            list.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open app info settings page
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }
}
