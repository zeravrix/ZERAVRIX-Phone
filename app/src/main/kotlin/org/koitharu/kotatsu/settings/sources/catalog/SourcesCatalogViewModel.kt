package org.zeravrix.kotatsu.settings.sources.catalog

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import androidx.room.invalidationTrackerFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.db.MangaDatabase
import org.zeravrix.kotatsu.core.db.TABLE_SOURCES
import org.zeravrix.kotatsu.core.prefs.AppSettings
import org.zeravrix.kotatsu.core.ui.BaseViewModel
import org.zeravrix.kotatsu.core.ui.util.ReversibleAction
import org.zeravrix.kotatsu.core.util.ext.MutableEventFlow
import org.zeravrix.kotatsu.core.util.ext.call
import org.zeravrix.kotatsu.core.util.ext.mapSortedByCount
import org.zeravrix.kotatsu.explore.data.MangaSourcesRepository
import org.zeravrix.kotatsu.explore.data.SourcesSortOrder
import org.zeravrix.kotatsu.settings.sources.extensions.ExtensionCatalogRepository
import org.zeravrix.kotatsu.settings.sources.extensions.ExtensionSourceCatalogEntry
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import org.zeravrix.kotatsu.list.ui.model.ListModel
import org.zeravrix.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repository: MangaSourcesRepository,
	db: MangaDatabase,
	private val settings: AppSettings,
	private val extensionCatalog: ExtensionCatalogRepository,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val locales: Set<String?>
		get() {
			val set = LinkedHashSet<String?>()
			set.add(null)
			repository.allMangaSources.mapNotNullTo(set) { it.locale?.takeIf { l -> l.isNotBlank() } }
			extensionCatalog.entries.value.mapNotNullTo(set) { it.language.takeIf { l -> l.isNotBlank() && l != "all" } }
			org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.sources.value.mapNotNullTo(set) { it.language.takeIf { l -> l.isNotBlank() && l != "all" } }
			return set
		}

	private val searchQuery = MutableStateFlow<String?>(null)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			types = emptySet(),
			locale = Locale.getDefault().language.takeIf { it in locales },
			isNewOnly = false,
		),
	)

	val hasNewSources = repository.observeHasNewSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val contentTypes = MutableStateFlow<List<ContentType>>(emptyList())

	val installingPackages = MutableStateFlow<Set<String>>(emptySet())

	@Suppress("UNCHECKED_CAST")
	val content: StateFlow<List<ListModel>> = combine(
		searchQuery,
		appliedFilter,
		db.invalidationTrackerFlow(TABLE_SOURCES),
		extensionCatalog.entries,
		repository.observeMihonSources(),
		installingPackages,
	) { args: Array<Any?> ->
		val q = args[0] as String?
		val f = args[1] as SourcesCatalogFilter
		val extensions = args[3] as List<ExtensionSourceCatalogEntry>
		val mihonSources = args[4] as List<MihonMangaSource>
		val installing = args[5] as Set<String>
		buildSourcesList(f, q, extensions, mihonSources, installing)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		repository.clearNewSourcesBadge()
		launchJob(Dispatchers.Default) {
			contentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
		}
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun setLocale(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(locale = value)
	}

	fun addSource(source: MangaSource) {
		launchJob(Dispatchers.Default) {
			val rollback = repository.setSourcesEnabled(setOf(source), true)
			onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
		}
	}

	fun removeSource(source: MangaSource) {
		launchJob(Dispatchers.Default) {
			val rollback = repository.setSourcesEnabled(setOf(source), false)
			onActionDone.call(ReversibleAction(R.string.source_disabled, rollback))
		}
	}

	fun setContentType(value: ContentType, isAdd: Boolean) {
		val filter = appliedFilter.value
		val types = EnumSet.noneOf(ContentType::class.java)
		types.addAll(filter.types)
		if (isAdd) {
			types.add(value)
		} else {
			types.remove(value)
		}
		appliedFilter.value = filter.copy(types = types)
	}

	fun setNewOnly(value: Boolean) {
		appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
	}

	fun installExtension(item: SourceCatalogItem.Extension, onFinished: ((Boolean) -> Unit)? = null) {
		val pkg = item.packageName
		if (pkg in installingPackages.value) return
		installingPackages.update { it + pkg }
		launchJob(Dispatchers.IO) {
			try {
				val extManager = org.zeravrix.kotatsu.extension.ExtensionManager.getInstance(context)
				var success = false
				extManager.installer.downloadAndInstall(pkg, item.apkUrl).collect { step ->
					if (step.isCompleted()) {
						success = true
						val newSources = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.refreshNow(context)
						val installed = newSources.find { it.packageName == pkg }
						if (installed != null) {
							repository.setSourcesEnabled(setOf(installed), true)
						}
					}
				}
				withContext(Dispatchers.Main) {
					onFinished?.invoke(success)
				}
			} catch (e: Exception) {
				Log.e("SourcesCatalog", "Error installing extension $pkg", e)
				withContext(Dispatchers.Main) {
					onFinished?.invoke(false)
				}
			} finally {
				installingPackages.update { it - pkg }
			}
		}
	}

	private suspend fun buildSourcesList(
		filter: SourcesCatalogFilter,
		query: String?,
		extensions: List<ExtensionSourceCatalogEntry>,
		mihonSources: List<MihonMangaSource>,
		installing: Set<String>,
	): List<SourceCatalogItem> {
		val sources = repository.queryParserSources(
			isDisabledOnly = true,
			isNewOnly = filter.isNewOnly,
			excludeBroken = false,
			types = filter.types,
			query = query,
			locale = filter.locale,
			sortOrder = SourcesSortOrder.ALPHABETIC,
		)
		val normalizedQuery = query?.trim().orEmpty()
		val runtimeItems = mihonSources.asSequence()
			.filter { source ->
				(filter.locale.isNullOrBlank() || source.language.equals(filter.locale, ignoreCase = true)) &&
				(normalizedQuery.isBlank() || source.sourceName.contains(normalizedQuery, ignoreCase = true))
			}
			.sortedWith(compareBy({ extensionLanguageSortKey(it.language) }, { it.sourceName.lowercase() }))
			.map { source ->
				SourceCatalogItem.Source(
					source = source,
					isEnabled = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime.isEnabled(context, source),
				)
			}
			.toList()
		val hideNsfwExtensions = settings.isNsfwContentDisabled
		val loadedPackages = mihonSources.mapTo(HashSet<String>()) { it.packageName }
		val extensionItems = extensions.asSequence()
			.filterNot { it.packageName in loadedPackages }
			.filter { entry -> !hideNsfwExtensions || entry.nsfw == 0 }
			.filter { entry ->
				(filter.locale.isNullOrBlank() || entry.language.equals(filter.locale, ignoreCase = true)) &&
				(normalizedQuery.isBlank() || listOf(entry.sourceName, entry.extensionName, entry.packageName).any { it.contains(normalizedQuery, ignoreCase = true) })
			}
			.sortedWith(compareBy({ extensionLanguageSortKey(it.language) }, { it.sourceName.lowercase() }, { it.extensionName.lowercase() }))
			.map { entry ->
				val isInstalled = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager.isPackageInstalled(entry.packageName)
				val isEnabled = if (isInstalled) {
					org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime.isPackageEnabled(context, entry.packageName)
				} else {
					false
				}
				SourceCatalogItem.Extension(
					sourceName = entry.sourceName,
					language = entry.language,
					extensionName = entry.extensionName,
					packageName = entry.packageName,
					versionName = entry.versionName,
					apkUrl = entry.apkUrl,
					iconRes = extensionIconRes(entry.packageName),
					iconUrl = entry.iconUrl,
					isInstalling = entry.packageName in installing,
					isInstalled = isInstalled,
					isEnabled = isEnabled,
				)
			}
			.toList()
		val nativeItems = sources.map { SourceCatalogItem.Source(source = it, isEnabled = false) }
		val items = runtimeItems + nativeItems + extensionItems
			return if (items.isEmpty()) {

			listOf(
				if (query == null) {
					SourceCatalogItem.Hint(
						icon = R.drawable.zeravrix_chibi,
						title = R.string.no_manga_sources,
						text = R.string.no_manga_sources_catalog_text,
					)
				} else {
					SourceCatalogItem.Hint(
						icon = R.drawable.zeravrix_chibi,
						title = R.string.nothing_found,
						text = R.string.no_manga_sources_found,
					)
				},
			)
					} else {
				items
			}

	}

	@WorkerThread
	private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
		val result = repository.allMangaSources.mapSortedByCount { it.contentType }
		return if (isNsfwDisabled) {
			result.filterNot { it == ContentType.HENTAI }
		} else {
			result
		}
	}
}

internal fun extensionIconRes(packageName: String): Int = when (packageName.substringAfterLast('.')) {
    "arabshentai" -> R.drawable.extension_arabshentai_icon
    "arabtoons" -> R.drawable.extension_arabtoons_icon
    "azora" -> R.drawable.extension_azora_icon
    "dilar" -> R.drawable.extension_dilar_icon
    "hijala" -> R.drawable.extension_hijala_icon
    "mangalek" -> R.drawable.extension_mangalek_icon
    "mangaspark" -> R.drawable.extension_mangaspark_icon
    "mangaswat" -> R.drawable.extension_mangaswat_icon
    "teamx" -> R.drawable.extension_teamx_icon
    else -> R.drawable.zeravrix_chibi
}

private fun extensionLanguageSortKey(language: String): String = language.trim().lowercase().let {
	if (it.isBlank() || it == "all") "zzzz" else it
}
