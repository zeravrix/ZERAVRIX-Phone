package org.zeravrix.kotatsu.extension.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.InstallStep
import org.zeravrix.kotatsu.extension.trust.TrustExtension
import org.zeravrix.kotatsu.extension.util.ExtensionInstallReceiver
import org.zeravrix.kotatsu.extension.util.ExtensionInstallService
import org.zeravrix.kotatsu.extension.util.ExtensionLoader
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class ExtensionInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = runCatching {
        Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
    }.getOrNull() ?: OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSessions = ConcurrentHashMap<String, Int>()

    fun getStepFlow(pkgName: String): Flow<InstallStep>? {
        return activeSteps[pkgName]?.asStateFlow()
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun promptManageUnknownAppSources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, R.string.enable_unknown_sources, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot launch unknown app sources settings", e)
            }
        }
    }

    fun downloadAndInstall(
        extension: Extension.Available,
    ): Flow<InstallStep> {
        val pkgName = extension.pkgName
        cancelInstall(pkgName)

        val stepFlow = MutableStateFlow<InstallStep>(InstallStep.Pending)
        activeSteps[pkgName] = stepFlow

        if (!canRequestPackageInstalls()) {
            promptManageUnknownAppSources()
            stepFlow.value = InstallStep.Idle
            return stepFlow.asStateFlow()
        }

        runCatching { ExtensionInstallService.start(context) }

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "ext_${pkgName}.apk")
            try {
                stepFlow.value = InstallStep.Downloading
                val request = Request.Builder()
                    .url(extension.apkUrl)
                    .header("User-Agent", "ZERAVRIX/2.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Failed to download APK: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    runInterruptible {
                        body.byteStream().use { input ->
                            FileOutputStream(tmpFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                // Verify package info
                val packageInfo = ExtensionLoader.getArchivePackageInfo(context, tmpFile)
                    ?: throw IllegalStateException("Invalid extension APK archive")

                // Verify signature against store key or trusted keys
                val signatures = ExtensionLoader.getSignatures(packageInfo).orEmpty()
                val storeKey = extension.store.signingKey
                val hasStoreKey = storeKey.isNotBlank() && storeKey != "NO_SIGNING_KEY"
                if (hasStoreKey && signatures.none { it.equals(storeKey, ignoreCase = true) }) {
                    // Store publishes a key and the APK does not match it: refuse.
                    tmpFile.delete()
                    throw SecurityException("APK signature does not match the key published by ${extension.store.name}")
                }
                // Stores without a key (or unknown keys) install fine: the extension is then
                // listed as "Untrusted" and the user decides in the trust dialog, like Mihon.

                stepFlow.value = InstallStep.Installing

                // Install through PackageInstaller session
                installViaPackageInstaller(context, tmpFile, packageInfo.packageName)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    stepFlow.value = InstallStep.Idle
                    throw e
                }
                Log.e(TAG, "Failed to download and install $pkgName", e)
                showError(e.message ?: e.javaClass.simpleName)
                stepFlow.value = InstallStep.Error
            } finally {
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
                if (activeJobs.size <= 1) {
                    runCatching { ExtensionInstallService.stop(context) }
                }
            }
        }

        activeJobs[pkgName] = job

        return stepFlow.asStateFlow().onCompletion {
            activeJobs.remove(pkgName)
        }
    }

    fun installFromFile(file: File): Boolean {
        if (!canRequestPackageInstalls()) {
            promptManageUnknownAppSources()
            return false
        }

        return try {
            val packageInfo = ExtensionLoader.getArchivePackageInfo(context, file)
                ?: return false
            installViaPackageInstaller(context, file, packageInfo.packageName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from file ${file.name}", e)
            false
        }
    }

    fun installFromUri(uri: Uri): Boolean {
        if (!canRequestPackageInstalls()) {
            promptManageUnknownAppSources()
            return false
        }

        val tmpFile = File(context.cacheDir, "ext_import_${System.currentTimeMillis()}.apk")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            installFromFile(tmpFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from URI $uri", e)
            false
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    fun installViaPackageInstaller(context: Context, apkFile: File, packageName: String): Int {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val fileSize = apkFile.length()
        params.setSize(fileSize)

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        activeSessions[packageName] = sessionId

        session.use { s ->
            apkFile.inputStream().use { input ->
                s.openWrite(packageName, 0, fileSize).use { output ->
                    input.copyTo(output)
                    s.fsync(output)
                }
            }

            val intent = Intent(context, ExtensionInstallResultReceiver::class.java).apply {
                `package` = context.packageName
                action = ExtensionInstallResultReceiver.ACTION_INSTALL_RESULT
                putExtra(ExtensionInstallResultReceiver.EXTRA_PACKAGE_NAME, packageName)
                putExtra(ExtensionInstallResultReceiver.EXTRA_SESSION_ID, sessionId)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            s.commit(pendingIntent.intentSender)
        }
        return sessionId
    }

    fun uninstall(pkgName: String) {
        cancelInstall(pkgName)
        ExtensionLoader.uninstallPrivateExtension(context, pkgName)
        try {
            val pm = context.packageManager
            if (isPackageInstalled(pm, pkgName)) {
                @Suppress("DEPRECATION")
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking or uninstalling system package $pkgName", e)
        }
        // Do not notify removal here: the system uninstall dialog may be cancelled.
        // The package-removed broadcast updates the list when it is really gone.
    }

    private fun showError(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, "Install failed: $message", Toast.LENGTH_LONG).show()
        }
    }

    fun cancelInstall(pkgName: String) {
        activeJobs.remove(pkgName)?.cancel()
        activeSessions.remove(pkgName)?.let { sessionId ->
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        }
        updateInstallStep(pkgName, InstallStep.Idle)
    }

    private fun isPackageInstalled(pm: PackageManager, pkgName: String): Boolean {
        return try {
            pm.getPackageInfo(pkgName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        private const val TAG = "ExtensionInstaller"
        private val activeSteps = ConcurrentHashMap<String, MutableStateFlow<InstallStep>>()

        fun updateInstallStep(pkgName: String, step: InstallStep) {
            activeSteps[pkgName]?.value = step
            if (step.isFinished()) {
                activeSteps.remove(pkgName)
            }
        }
    }
}
