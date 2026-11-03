package org.zeravrix.kotatsu.settings.about

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zeravrix.kotatsu.BuildConfig
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.github.VersionId
import java.util.regex.Pattern

class WebsiteUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            var remoteVersion: String? = null
            runCatching {
                val githubRequest = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("User-Agent", "ZERAVRIX-Phone")
                    .build()
                client.newCall(githubRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = org.json.JSONObject(response.body?.string().orEmpty())
                        val tag = json.optString("tag_name").removePrefix("v").trim()
                        if (tag.isNotBlank()) {
                            remoteVersion = tag
                        }
                    }
                }
            }
            if (remoteVersion == null) {
                val request = Request.Builder().url(WEBSITE_URL).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string().orEmpty()
                        remoteVersion = RELEASE_PATTERN.matcher(html).let { matcher ->
                            if (matcher.find()) matcher.group(1) else null
                        }
                    }
                }
            }
            val version = remoteVersion ?: return@runCatching Result.success()
            if (VersionId(version) <= VersionId(BuildConfig.VERSION_NAME)) return@runCatching Result.success()
            val prefs = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_LAST_NOTIFIED, null) == version) return@runCatching Result.success()
            prefs.edit().putString(KEY_LAST_NOTIFIED, version).apply()
            showNotification(version)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun showNotification(remoteVersion: String) {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.website_updates_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = applicationContext.getString(R.string.website_updates_channel_description) },
            )
        }
        val openUpdate = PendingIntent.getActivity(
            applicationContext,
            4100,
            Intent(applicationContext, AppUpdateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.website_update_available))
            .setContentText(applicationContext.getString(R.string.website_update_available_message, remoteVersion))
            .setStyle(NotificationCompat.BigTextStyle().bigText(applicationContext.getString(R.string.website_update_available_message, remoteVersion)))
            .setContentIntent(openUpdate)
            .setAutoCancel(true)
            .build()
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) }
        }
    }

    private companion object {
        const val GITHUB_API_URL = "https://api.github.com/repos/zeravrix/ZERAVRIX-Phone/releases/latest"
        const val WEBSITE_URL = "https://zeravrix.pages.dev/"
        const val PREFERENCES_NAME = "zeravrix_website_updates"
        const val KEY_LAST_NOTIFIED = "last_notified_version"
        const val CHANNEL_ID = "zeravrix_website_updates"
        const val NOTIFICATION_ID = 1400
        val RELEASE_PATTERN = Pattern.compile("LATEST\\s+RELEASE\\s*/\\s*([0-9]+(?:\\.[0-9]+){2}(?:-[A-Za-z0-9.-]+)?)", Pattern.CASE_INSENSITIVE)
    }
}
