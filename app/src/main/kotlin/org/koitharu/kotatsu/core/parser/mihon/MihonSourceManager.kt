package org.zeravrix.kotatsu.core.parser.mihon

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.zeravrix.kotatsu.extension.ExtensionManager
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.StubSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotatsu-facing coordinator for Mihon executable sources.
 *
 * It manages loaded extension sources, debounces preference and package updates,
 * guarantees non-blocking source lookups, and provides stub sources for uninstalled extensions.
 */
object MihonSourceManager {

    private const val TAG = "MihonSourceManager"
    private const val DEBOUNCE_DELAY_MS = 300L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _sources = MutableStateFlow<List<MihonMangaSource>>(emptyList())
    private val _isInitialized = MutableStateFlow(false)
    private val _revision = MutableStateFlow(0)

    private val loadedSourcesMap = ConcurrentHashMap<String, LoadedMihonSource>()
    private val stubSourcesMap = ConcurrentHashMap<String, LoadedMihonSource>()

    private var refreshJob: Job? = null
    private var initializedContext: Context? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile
    var extensionManager: ExtensionManager? = null
        private set

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    val sources: StateFlow<List<MihonMangaSource>> = _sources.asStateFlow()
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (initializedContext === appContext && extensionManager != null) {
            return
        }

        synchronized(this) {
            if (initializedContext === appContext && extensionManager != null) {
                return
            }

            initializedContext = appContext
            val mgr = ExtensionManager.getInstance(appContext)
            extensionManager = mgr
            updateLoadedSources(mgr.getLoadedExtensions())

            // Listen to loaded extensions from the ExtensionManager
            scope.launch {
                mgr.loadedExtensionsFlow.collectLatest { loadedExts ->
                    updateLoadedSources(loadedExts)
                }
            }

            // Listen to explicit user enable/pin toggles on mihon_runtime with debounce
            val runtimePrefs = appContext.getSharedPreferences("mihon_runtime", Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && (key.startsWith("source_enabled_") || key.startsWith("source_pinned_"))) {
                    debounceRefresh()
                }
            }
            runtimePrefs.registerOnSharedPreferenceChangeListener(listener)
            preferenceListener = listener
        }
    }

    private fun updateLoadedSources(loadedExts: List<Extension.Loaded>) {
        val newMap = ConcurrentHashMap<String, LoadedMihonSource>()
        val descriptors = mutableListOf<MihonMangaSource>()

        for (ext in loadedExts) {
            val isNsfw = ext.contentWarning == ContentWarning.NSFW
            for (source in ext.sources) {
                val desc = MihonMangaSource(
                    packageName = ext.pkgName,
                    sourceId = source.id,
                    sourceName = source.name,
                    language = source.lang,
                    isNsfw = isNsfw,
                )
                MihonMangaSource.remember(desc)
                descriptors.add(desc)

                val loaded = LoadedMihonSource(
                    descriptor = desc,
                    source = source,
                    classLoader = ext.classLoader ?: javaClass.classLoader!!,
                )
                newMap[desc.name] = loaded
            }
        }

        descriptors.sortWith(compareBy({ it.language }, { it.sourceName }))

        loadedSourcesMap.clear()
        stubSourcesMap.clear()
        loadedSourcesMap.putAll(newMap)
        MihonMangaSource.retain(newMap.keys)

        _sources.value = descriptors
        _isInitialized.value = true
        _revision.update { it + 1 }
        Log.i(TAG, "Mihon sources updated: ${descriptors.size} active source(s)")
    }

    fun debounceRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(DEBOUNCE_DELAY_MS)
            extensionManager?.loadExtensions()
        }
    }

    fun refresh(context: Context? = null) {
        val appContext = (context ?: initializedContext ?: return).applicationContext
        if (initializedContext !== appContext) {
            initialize(appContext)
            return
        }
        debounceRefresh()
    }

    suspend fun refreshNow(context: Context? = null): List<MihonMangaSource> {
        val appContext = (context ?: initializedContext ?: return emptyList()).applicationContext
        if (initializedContext !== appContext) {
            initialize(appContext)
        }
        mutex.withLock {
            extensionManager?.loadExtensions()
            extensionManager?.getLoadedExtensions()?.let { updateLoadedSources(it) }
        }
        return _sources.value
    }

    suspend fun awaitInitialization() {
        extensionManager?.awaitInitialization()
    }

    suspend fun getLoadedOrAwait(context: Context, descriptor: MihonMangaSource): LoadedMihonSource {
        initialize(context)
        if (!_isInitialized.value) {
            awaitInitialization()
        }
        loadedSourcesMap[descriptor.name]?.let { return it }
        loadedSourcesMap.values.find { it.descriptor.sourceId == descriptor.sourceId }?.let { return it }
        loadedSourcesMap.values.find { it.descriptor.packageName == descriptor.packageName }?.let { return it }

        // Attempt refresh if not present
        refreshNow(context)
        loadedSourcesMap[descriptor.name]?.let { return it }
        loadedSourcesMap.values.find { it.descriptor.sourceId == descriptor.sourceId }?.let { return it }
        loadedSourcesMap.values.find { it.descriptor.packageName == descriptor.packageName }?.let { return it }

        return get(descriptor)
    }

    /**
     * Non-blocking source getter. If the source is not loaded or missing,
     * returns a StubSource so library entries and historical data do not crash.
     */
    fun get(descriptor: MihonMangaSource): LoadedMihonSource {
        loadedSourcesMap[descriptor.name]?.let { return it }
        loadedSourcesMap.values.find { it.descriptor.sourceId == descriptor.sourceId }?.let { return it }

        return stubSourcesMap.getOrPut(descriptor.name) {
            val stub = StubSource(
                id = descriptor.sourceId,
                lang = descriptor.language,
                name = descriptor.sourceName,
            )
            LoadedMihonSource(
                descriptor = descriptor,
                source = stub,
                classLoader = javaClass.classLoader!!,
            )
        }
    }

    fun getAllLoaded(): List<LoadedMihonSource> = loadedSourcesMap.values.toList()
}
