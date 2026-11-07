package org.zeravrix.kotatsu.settings.sources.extensions

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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

data class ExtensionLanguage(
    val code: String,
    val displayName: String,
)

fun getLanguageDisplayName(code: String): String {
    return try {
        val locale = java.util.Locale.forLanguageTag(code)
        val engName = locale.getDisplayLanguage(java.util.Locale.ENGLISH).replaceFirstChar { it.uppercase() }
        val nativeName = locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase() }
        if (engName.isBlank()) {
            code.uppercase()
        } else if (nativeName.isNotBlank() && !nativeName.equals(engName, ignoreCase = true)) {
            "$engName ($nativeName)"
        } else {
            engName
        }
    } catch (_: Throwable) {
        code.uppercase()
    }
}

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

    companion object {
        private val KNOWN_LANGUAGES = listOf(
            "ar", "en", "es", "fr", "id", "it", "ja", "ko", "pt", "pt-BR",
            "ru", "th", "tr", "vi", "zh-Hans", "de", "pl"
        )
    }

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

    val availableLanguages: StateFlow<List<ExtensionLanguage>> = combine(
        extensionManager.availableExtensionsFlow,
        extensionManager.loadedExtensionsFlow,
        extensionManager.notLoadedExtensionsFlow,
    ) { available, loaded, notLoaded ->
        val foundCodes = (available.map { it.lang } + loaded.map { it.lang } + notLoaded.mapNotNull { it.lang })
            .filter { it.isNotBlank() && !it.equals("all", ignoreCase = true) }
            .toSet()

        val allCodes = (KNOWN_LANGUAGES + foundCodes).distinct()
        allCodes.map { code ->
            ExtensionLanguage(code = code, displayName = getLanguageDisplayName(code))
        }.sortedBy { it.displayName.lowercase() }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        KNOWN_LANGUAGES.map { ExtensionLanguage(it, getLanguageDisplayName(it)) }.sortedBy { it.displayName.lowercase() }
    )

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
            if (lang != null) {
                val extLang = ext.lang.orEmpty().lowercase()
                val target = lang.lowercase()
                val matches = extLang == target ||
                    extLang == "all" ||
                    (target == "pt" && extLang == "pt-br") ||
                    (target == "zh-hans" && extLang.startsWith("zh"))
                if (!matches) return false
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
            try {
                extensionManager.installer.downloadAndInstall(ext).collect { step ->
                    activeSteps.update { it + (pkg to step) }
                    if (step.isFinished()) {
                        activeSteps.update { it - pkg }
                    }
                }
            } catch (e: CancellationException) {
                activeSteps.update { it - pkg }
                throw e
            } catch (e: Throwable) {
                Log.e("ExtensionsViewModel", "Failed to install extension $pkg", e)
                activeSteps.update { it - pkg }
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
            extensionManager.trustExtension.trust(ext.pkgName, ext.versionCode, hash)
            // trust() notifies the manager; a single reload is enough.
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                extensionManager.loadExtensions()
            }
        }
    }

    fun uninstall(pkgName: String) {
        viewModelScope.launch {
            // Source rows are removed by syncMihonSources once the package is really gone.
            extensionManager.uninstallExtension(pkgName)
        }
    }

    fun installFromUri(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        return pinned.any { (it as? MihonMangaSource)?.packageName == pkgName || it.name.startsWith("mihon:$pkgName:") }
    }

    fun toggleExtensionPin(pkgName: String, pin: Boolean) {
        launchJob(kotlinx.coroutines.Dispatchers.IO) {
            sourcesRepository.setPackagePinned(pkgName, pin)
        }
    }
}
