package org.zeravrix.kotatsu.settings.about.changelog

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
				val changelogText = buildString {
					for (version in versions) {
						append("# ")
						append(version.name)
						append("\n\n")
						append(version.description)
						append("\n\n\n")
					}
				}.trim()
				changelog.value = changelogText
			} else {
				changelog.value = """
					# ZERAVRIX Phone 2.0.4
					
					### What's New:
					- **Universal Extension Architecture**: Browse, install, and execute APK extensions directly inside ZERAVRIX.
					- **Multi-Language Support**: Full language filter and localized language names in extension store.
					- **Direct Download & Add in Source Catalog**: Instant inline download progress and 1-tap addition to Explore.
					- **Pinned Source Ordering**: Pin and unpin sources with instant persistence in Explore.
					- **Native Offline Downloads**: Full support for downloading and saving manga and manhwa chapters offline.
					- **Optimized Networking & Caching**: Fast page loading, Brotli compression, and resilient chapter memo cache.
					- **Content Control**: Comprehensive NSFW content toggles across all catalog and extension listings.
					- **Explore Integration**: Unified Explore feed for all installed native and extension sources.
					
					[View Full Release Notes on GitHub](https://github.com/zeravrix/ZERAVRIX-Phone/releases)
				""".trimIndent()
			}
		}
	}
}
