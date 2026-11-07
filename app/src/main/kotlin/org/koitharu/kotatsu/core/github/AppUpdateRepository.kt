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
			.header("Accept", "application/vnd.github+json")
		val jsonArray = okHttp.newCall(request.build()).await().parseJsonArray()
		return jsonArray.mapJSONNotNull { json ->
			if (json.optBoolean("draft")) return@mapJSONNotNull null
			val assets = json.optJSONArray("assets") ?: return@mapJSONNotNull null
			val apks = ArrayList<JSONObject>()
			for (i in 0 until assets.length()) {
				val jo = assets.getJSONObject(i)
				if (jo.optString("content_type") == CONTENT_TYPE_APK || jo.optString("name").endsWith(".apk", ignoreCase = true)) {
					apks += jo
				}
			}
			// Prefer a non-debug APK when a release has several.
			val asset = apks.firstOrNull { !it.optString("name").contains("debug", ignoreCase = true) }
				?: apks.firstOrNull()
				?: return@mapJSONNotNull null
			val tagName = json.optString("tag_name").removePrefix("v").trim()
			val versionName = if (tagName.isNotBlank()) tagName else json.getString("name").removePrefix("v").trim()
			AppVersion(
				id = json.getLong("id"),
				url = json.getString("html_url"),
				name = versionName,
				apkSize = asset.getLong("size"),
				apkUrl = asset.getString("browser_download_url"),
				description = sanitizeNotes(json.optString("body")),
			)
		}
	}

	/** Last error of the most recent update check, or null if it succeeded. */
	@Volatile
	var lastCheckError: Throwable? = null
		private set

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
			lastCheckError = it
			it.printStackTraceDebug()
		}.onSuccess {
			lastCheckError = null
			availableUpdate.value = it
		}.getOrNull()
	}

	suspend fun isUpdateSupported(): Boolean {
		return true
	}

	/**
	 * Release notes are shown to the user as plain "what's new": links, bare URLs and
	 * auto-generated "Full Changelog" lines are removed so no repository addresses are displayed.
	 */
	private fun sanitizeNotes(raw: String): String {
		return raw.lineSequence()
			.filterNot { it.contains("Full Changelog", ignoreCase = true) || it.contains("/compare/") }
			.map { line ->
				line
					.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
					.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
					.replace(Regex("https?://\\S+"), "")
					.replace(Regex("\\s+by\\s+@\\S+(\\s+in)?\\s*$"), "")
					.replace(Regex("^#{1,6}\\s*"), "")
					.trimEnd()
			}
			.joinToString("\n")
			.replace(Regex("\n{3,}"), "\n\n")
			.trim()
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
