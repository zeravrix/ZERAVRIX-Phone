package org.zeravrix.kotatsu.core.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.zeravrix.kotatsu.BuildConfig
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.network.BaseHttpClient
import org.zeravrix.kotatsu.core.prefs.AppSettings
import org.zeravrix.kotatsu.core.util.ext.asArrayList
import org.zeravrix.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"
private const val BUILD_TYPE_RELEASE = "release"

@Singleton
class AppUpdateRepository @Inject constructor(
	private val settings: AppSettings,
	@BaseHttpClient private val okHttp: OkHttpClient,
	@ApplicationContext context: Context,
) {

	private val availableUpdate = MutableStateFlow<AppVersion?>(null)
	private val releasesUrl = buildString {
		append("https://api.github.com/repos/")
		append(context.getString(R.string.github_updates_repo))
		append("/releases?page=1&per_page=10")
	}

	val isUpdateAvailable: Boolean
		get() = availableUpdate.value != null

	fun observeAvailableUpdate() = availableUpdate.asStateFlow()

	suspend fun getAvailableVersions(): List<AppVersion> {
		val request = Request.Builder()
			.get()
			.url(releasesUrl)
		val jsonArray = okHttp.newCall(request.build()).await().parseJsonArray()
		return jsonArray.mapJSONNotNull { json ->
			val asset = json.optJSONArray("assets")?.find { jo ->
				jo.optString("content_type") == CONTENT_TYPE_APK || jo.optString("name").endsWith(".apk", ignoreCase = true)
			} ?: return@mapJSONNotNull null
			val tagName = json.optString("tag_name").removePrefix("v").trim()
			val versionName = if (tagName.isNotBlank()) tagName else json.getString("name").removePrefix("v").trim()
			AppVersion(
				id = json.getLong("id"),
				url = json.getString("html_url"),
				name = versionName,
				apkSize = asset.getLong("size"),
				apkUrl = asset.getString("browser_download_url"),
				description = json.optString("body"),
			)
		}
	}

	suspend fun fetchUpdate(): AppVersion? = withContext(Dispatchers.Default) {
		if (!isUpdateSupported()) {
			return@withContext null
		}
		runCatchingCancellable {
			val currentVersion = VersionId(BuildConfig.VERSION_NAME)
			val available = getAvailableVersions().asArrayList()
			available.sortBy { it.versionId }
			if (currentVersion.isStable && !settings.isUnstableUpdatesAllowed) {
				available.retainAll { it.versionId.isStable }
			}
			available.maxByOrNull { it.versionId }
				?.takeIf { it.versionId > currentVersion }
		}.onFailure {
			it.printStackTraceDebug()
		}.onSuccess {
			availableUpdate.value = it
		}.getOrNull()
	}

	suspend fun isUpdateSupported(): Boolean {
		return true
	}

	private inline fun JSONArray.find(predicate: (JSONObject) -> Boolean): JSONObject? {
		val size = length()
		for (i in 0 until size) {
			val jo = getJSONObject(i)
			if (predicate(jo)) {
				return jo
			}
		}
		return null
	}
}
