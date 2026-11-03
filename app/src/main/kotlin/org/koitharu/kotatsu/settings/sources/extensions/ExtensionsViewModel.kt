package org.zeravrix.kotatsu.settings.sources.extensions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import org.zeravrix.kotatsu.core.prefs.AppSettings
import org.zeravrix.kotatsu.core.ui.BaseViewModel
import org.zeravrix.kotatsu.explore.data.MangaSourcesRepository
import org.zeravrix.kotatsu.extension.ExtensionManager
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.InstallStep
import org.zeravrix.kotatsu.list.ui.model.ListHeader
import org.zeravrix.kotatsu.list.ui.model.ListModel
import org.zeravrix.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

enum class FilterTab {
    ALL,
    INSTALLED,
    UPDATES,
    AVAILABLE,
}

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    val sourcesRepository: MangaSourcesRepository,
    val extensionManager: ExtensionManager,
) : BaseViewModel() {

    val filterTab = MutableStateFlow(FilterTab.ALL)
    val searchQuery = MutableStateFlow("")
    val selectedLanguage = MutableStateFlow<String?>(null)
    private val activeSteps = MutableStateFlow<Map<String, InstallStep>>(emptyMap())

    val isRefreshing: StateFlow<Boolean> = extensionManager.isRefreshingAvailable

    val installedCount = combine(
        extensionManager.loadedExtensionsFlow,
        extensionManager.notLoadedExtensionsFlow,
    ) { loaded, notLoaded ->
        loaded.size + notLoaded.size
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val updatesCount = combine(
        extensionManager.loadedExtensionsFlow,
        extensionManager.notLoadedExtensionsFlow,
    ) { loaded, notLoaded ->
        loaded.count { it.hasUpdate } + notLoaded.count { it.hasUpdate }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val availableCount = combine(
        extensionManager.availableExtensionsFlow,
        extensionManager.loadedExtensionsFlow,
        extensionManager.notLoadedExtensionsFlow,
    ) { available, loaded, notLoaded ->
        val installedPkgs = (loaded.map { it.pkgName } + notLoaded.map { it.pkgName }).toSet()
        available.count { it.pkgName !in installedPkgs }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val availableLanguages = extensionManager.availableExtensionsFlow.combine(
        extensionManager.loadedExtensionsFlow,
    ) { available, loaded ->
        val langs = (available.map { it.lang } + loaded.map { it.lang }).filter { it.isNotBlank() && it != "all" }.toSet()
        langs.sorted()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val items: StateFlow<List<ListModel>> = combine(
        extensionManager.loadedExtensionsFlow,
        extensionManager.notLoadedExtensionsFlow,
        extensionManager.availableExtensionsFlow,
        isRefreshing,
        filterTab,
        searchQuery,
        selectedLanguage,
        activeSteps,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val loaded = args[0] as List<Extension.Loaded>
        @Suppress("UNCHECKED_CAST")
        val notLoaded = args[1] as List<Extension.NotLoaded>
        @Suppress("UNCHECKED_CAST")
        val available = args[2] as List<Extension.Available>
        val refreshing = args[3] as Boolean
        val tab = args[4] as FilterTab
        val query = (args[5] as String).trim().lowercase()
        val lang = args[6] as String?
        @Suppress("UNCHECKED_CAST")
        val steps = args[7] as Map<String, InstallStep>

        if (refreshing && loaded.isEmpty() && notLoaded.isEmpty() && available.isEmpty()) {
            return@combine listOf(LoadingState)
        }

        val allInstalled = (loaded + notLoaded).sortedBy { it.name.lowercase() }
        val installedPkgs = allInstalled.map { it.pkgName }.toSet()

        val updates = allInstalled.filter { it.hasUpdate }
        val availableOnly = available.filter { it.pkgName !in installedPkgs }.sortedBy { it.name.lowercase() }

        val hideNsfw = settings.isNsfwContentDisabled
        fun matchesFilter(ext: Extension): Boolean {
            if (hideNsfw && ext.contentWarning == ContentWarning.NSFW) {
                return false
            }
            if (query.isNotBlank()) {
                val matchesName = ext.name.lowercase().contains(query)
                val matchesPkg = ext.pkgName.lowercase().contains(query)
                val matchesSource = when (ext) {
                    is Extension.Loaded -> ext.sources.any { it.name.lowercase().contains(query) }
                    is Extension.Available -> ext.sources.any { it.name.lowercase().contains(query) }
                    else -> false
                }
                if (!matchesName && !matchesPkg && !matchesSource) return false
            }
            if (lang != null && !ext.lang.equals(lang, ignoreCase = true) && !ext.lang.equals("all", ignoreCase = true)) {
                return false
            }
            return true
        }

        val filteredUpdates = updates.filter(::matchesFilter)
        val filteredInstalled = allInstalled.filter(::matchesFilter)
        val filteredAvailable = availableOnly.filter(::matchesFilter)

        val result = mutableListOf<ListModel>()

        when (tab) {
            FilterTab.ALL -> {
                if (filteredUpdates.isNotEmpty()) {
                    result.add(ListHeader(context.getString(R.string.updates)))
                    result.addAll(filteredUpdates.map { ExtensionItemModel(it, steps[it.pkgName] ?: InstallStep.Idle) })
                }
                if (filteredInstalled.isNotEmpty()) {
                    result.add(ListHeader(context.getString(R.string.installed)))
                    result.addAll(filteredInstalled.map { ExtensionItemModel(it, steps[it.pkgName] ?: InstallStep.Idle) })
                }
                if (filteredAvailable.isNotEmpty()) {
                    result.add(ListHeader(context.getString(R.string.available_extensions)))
                    result.addAll(filteredAvailable.map { ExtensionItemModel(it, steps[it.pkgName] ?: InstallStep.Idle) })
                }
            }
            FilterTab.INSTALLED -> {
                result.addAll(filteredInstalled.map { ExtensionItemModel(it, steps[it.pkgName] ?: InstallStep.Idle) })
            }
            FilterTab.UPDATES -> {
                result.addAll(filteredUpdates.map { ExtensionItemModel(it, steps[it.pkgName] ?: InstallStep.Idle) })
            }
            FilterTab.AVAILABLE -> {
                result.addAll(filteredAvailable.map { ExtensionItemModel(it, steps[it.pkgName] ?: InstallStep.Idle) })
            }
        }

        result
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            extensionManager.refreshAvailableExtensions()
        }
    }

    fun setFilterTab(tab: FilterTab) {
        filterTab.value = tab
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSelectedLanguage(lang: String?) {
        selectedLanguage.value = lang
    }

    fun install(ext: Extension.Available) {
        val pkg = ext.pkgName
        viewModelScope.launch {
            extensionManager.installer.downloadAndInstall(ext).collect { step ->
                activeSteps.update { it + (pkg to step) }
                if (step.isFinished()) {
                    activeSteps.update { it - pkg }
                }
            }
        }
    }

    fun update(ext: Extension.Installed) {
        val available = extensionManager.availableExtensionsFlow.value
        val listing = ext.findUpdate(available) ?: ext.findListing(available) ?: return
        install(listing)
    }

    fun trust(ext: Extension.NotLoaded) {
        val hash = (ext.reason as? Extension.NotLoaded.Reason.Untrusted)?.signatureHash
            ?: ext.signatures.firstOrNull()
        if (hash != null) {
            extensionManager.trustExtension.trust(ext.pkgName, hash)
        }
    }

    fun uninstall(pkgName: String) {
        viewModelScope.launch {
            extensionManager.uninstallExtension(pkgName)
            sourcesRepository.deleteSourcesByPackage(pkgName)
        }
    }

    fun installFromUri(uri: Uri) {
        viewModelScope.launch {
            extensionManager.installer.installFromUri(uri)
        }
    }

    fun getRepositories(): Set<String> {
        return extensionManager.storeService.getRepositories()
    }

    fun addRepository(url: String): Boolean {
        val added = extensionManager.storeService.addRepository(url)
        if (added) {
            refreshCatalog()
        }
        return added
    }

    fun removeRepository(url: String): Boolean {
        val removed = extensionManager.storeService.removeRepository(url)
        if (removed) {
            refreshCatalog()
        }
        return removed
    }

    suspend fun isExtensionPinned(pkgName: String): Boolean {
        val pinned = sourcesRepository.getPinnedSources()
        return pinned.filterIsInstance<MihonMangaSource>().any { it.packageName == pkgName }
    }

    fun toggleExtensionPin(pkgName: String, pin: Boolean) {
        launchJob(kotlinx.coroutines.Dispatchers.IO) {
            val loadedSources = extensionManager.loadedExtensionsFlow.value
                .find { it.pkgName == pkgName }
                ?.sources
                ?.map { MihonMangaSource(pkgName, it.id, it.name, it.lang) }
                .orEmpty()
            val sources = if (loadedSources.isNotEmpty()) {
                loadedSources
            } else {
                sourcesRepository.getEnabledSources()
                    .filterIsInstance<MihonMangaSource>()
                    .filter { it.packageName == pkgName }
            }
            if (sources.isNotEmpty()) {
                sourcesRepository.setIsPinned(sources, pin)
            }
        }
    }
}
