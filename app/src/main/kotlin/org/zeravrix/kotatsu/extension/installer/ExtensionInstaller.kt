package org.zeravrix.kotatsu.extension.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.InstallStep
import org.zeravrix.kotatsu.extension.util.ExtensionInstallReceiver
import org.zeravrix.kotatsu.extension.util.ExtensionLoader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class ExtensionInstaller(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSteps = ConcurrentHashMap<String, MutableStateFlow<InstallStep>>()

    fun getStepFlow(pkgName: String): Flow<InstallStep>? {
        return activeSteps[pkgName]?.asStateFlow()
    }

    fun downloadAndInstall(
        extension: Extension.Available,
    ): Flow<InstallStep> = downloadAndInstall(extension.pkgName, extension.apkUrl)

    fun downloadAndInstall(
        pkgName: String,
        apkUrl: String,
    ): Flow<InstallStep> {
        cancelInstall(pkgName)

        val stepFlow = MutableStateFlow<InstallStep>(InstallStep.Pending)
        activeSteps[pkgName] = stepFlow

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "ext_${pkgName}.apk")
            try {
                stepFlow.value = InstallStep.Downloading
                val request = Request.Builder()
                    .url(apkUrl)
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

                // Verify package info and signature
                val packageInfo = ExtensionLoader.getArchivePackageInfo(context, tmpFile)
                    ?: throw IllegalStateException("Invalid extension APK")

                stepFlow.value = InstallStep.Installing
                ExtensionLoader.installPrivateExtensionFile(context, tmpFile, packageInfo)
                ExtensionInstallReceiver.notifyAdded(context, pkgName)

                // Launch system package installer prompt so user can approve the APK install
                try {
                    val installerDir = File(context.cacheDir, "installer").apply { mkdirs() }
                    val installApk = File(installerDir, "${pkgName}.apk")
                    tmpFile.copyTo(installApk, overwrite = true)
                    val apkUri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.files",
                        installApk,
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not launch system package installer intent", e)
                }

                stepFlow.value = InstallStep.Installed
            } catch (e: Exception) {
                if (e is CancellationException) {
                    stepFlow.value = InstallStep.Idle
                    throw e
                }
                Log.e(TAG, "Failed to download and install $pkgName", e)
                stepFlow.value = InstallStep.Error
            } finally {
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
        }

        activeJobs[pkgName] = job

        return stepFlow.asStateFlow().onCompletion {
            activeJobs.remove(pkgName)
            activeSteps.remove(pkgName)
            job.cancel()
        }
    }

    fun installFromFile(file: File): Boolean {
        return try {
            val packageInfo = ExtensionLoader.getArchivePackageInfo(context, file)
                ?: return false
            ExtensionLoader.installPrivateExtensionFile(context, file, packageInfo)
            ExtensionInstallReceiver.notifyAdded(context, packageInfo.packageName)

            try {
                val installerDir = File(context.cacheDir, "installer").apply { mkdirs() }
                val installApk = File(installerDir, "${packageInfo.packageName}.apk")
                file.copyTo(installApk, overwrite = true)
                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    installApk,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch system package installer intent", e)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from file ${file.name}", e)
            false
        }
    }

    fun installFromUri(uri: Uri): Boolean {
        val tmpFile = File(context.cacheDir, "ext_import_${System.currentTimeMillis()}.apk")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            val success = installFromFile(tmpFile)
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install from URI $uri", e)
            false
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    fun uninstall(pkgName: String) {
        cancelInstall(pkgName)
        ExtensionLoader.uninstallPrivateExtension(context, pkgName)
        try {
            val pm = context.packageManager
            if (isPackageInstalled(pm, pkgName)) {
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking or uninstalling system package $pkgName", e)
        }
        ExtensionInstallReceiver.notifyRemoved(context, pkgName)
    }

    fun cancelInstall(pkgName: String) {
        activeJobs.remove(pkgName)?.cancel()
        activeSteps[pkgName]?.value = InstallStep.Idle
        activeSteps.remove(pkgName)
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
    }
}
