package org.zeravrix.kotatsu.settings.about.changelog

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.jsoup.internal.StringUtil
import org.zeravrix.kotatsu.core.github.AppUpdateRepository
import org.zeravrix.kotatsu.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class ChangelogViewModel @Inject constructor(
	private val appUpdateRepository: AppUpdateRepository,
) : BaseViewModel() {

	val changelog = MutableStateFlow<String?>(null)

	init {
		launchLoadingJob(Dispatchers.Default) {
			val versions = runCatching { appUpdateRepository.getAvailableVersions() }.getOrDefault(emptyList())
			if (versions.isNotEmpty()) {
				val stringJoiner = StringUtil.StringJoiner("\n\n\n")
				for (version in versions) {
					stringJoiner.add("# ")
						.append(version.name)
						.append("\n\n")
						.append(version.description)
				}
				changelog.value = stringJoiner.complete()
			} else {
				changelog.value = """
					# ZERAVRIX Phone 2.0.0
					
					### What's New:
					- **Full Mihon / Tachiyomi Extension Architecture**: Browse, install, and execute APK extensions directly inside ZERAVRIX.
					- **Direct Download & Add in Source Catalog**: Instant inline download progress and 1-tap addition to Explore.
					- **Native Offline Downloads**: Full support for downloading and saving manga and manhwa chapters offline.
					- **Optimized Networking**: Complete OkHttp and Brotli decompression support for fast, seamless reading.
					- **Content Control**: Comprehensive NSFW content toggles across all catalog and extension listings.
					- **Explore Integration**: Unified Explore feed for all installed native and extension sources.
					
					[View Full Release Notes on GitHub](https://github.com/zeravrix/ZERAVRIX-Phone/releases)
				""".trimIndent()
			}
		}
	}
}
