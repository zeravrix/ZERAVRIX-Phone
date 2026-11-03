package org.zeravrix.kotatsu.extension.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.zeravrix.kotatsu.extension.model.InstallStep

class ExtensionInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkgName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        Log.i(TAG, "Install result received for package: $pkgName, session: $sessionId, status: $status")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch package confirmation intent", e)
                        if (sessionId != -1) {
                            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                        }
                        ExtensionInstaller.updateInstallStep(pkgName, InstallStep.Error)
                    }
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION without confirmation intent")
                    if (sessionId != -1) {
                        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                    }
                    ExtensionInstaller.updateInstallStep(pkgName, InstallStep.Error)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package $pkgName installed successfully via PackageInstaller")
                ExtensionInstaller.updateInstallStep(pkgName, InstallStep.Installed)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Installation of $pkgName was cancelled by user")
                ExtensionInstaller.updateInstallStep(pkgName, InstallStep.Idle)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e(TAG, "Package install failed ($status): $message")
                if (sessionId != -1) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                }
                ExtensionInstaller.updateInstallStep(pkgName, InstallStep.Error)
            }
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val ACTION_INSTALL_RESULT = "org.zeravrix.kotatsu.extension.ACTION_INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}
