package org.zeravrix.kotatsu.extension

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.zeravrix.kotatsu.extension.installer.ExtensionInstaller
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.ExtensionStore
import org.zeravrix.kotatsu.extension.store.ExtensionStoreService
import org.zeravrix.kotatsu.extension.trust.TrustExtension
import org.zeravrix.kotatsu.extension.util.ExtensionInstallReceiver
import org.zeravrix.kotatsu.extension.util.ExtensionLoader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val trustExtension: TrustExtension,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = CompletableDeferred<Unit>()
    private val loadMutex = Mutex()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    val installer = ExtensionInstaller(context)
    val storeService = ExtensionStoreService(context)

    private val _loadedMap = MutableStateFlow<Map<String, Extension.Loaded>>(emptyMap())
    val loadedExtensionsFlow: StateFlow<List<Extension.Loaded>> = _loadedMap
        .map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _notLoadedMap = MutableStateFlow<Map<String, Extension.NotLoaded>>(emptyMap())
    val notLoadedExtensionsFlow: StateFlow<List<Extension.NotLoaded>> = _notLoadedMap
        .map { it.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _availableList = MutableStateFlow<List<Extension.Available>>(emptyList())
    val availableExtensionsFlow: StateFlow<List<Extension.Available>> = _availableList.asStateFlow()

    private val _isRefreshingAvailable = MutableStateFlow(false)
    val isRefreshingAvailable: StateFlow<Boolean> = _isRefreshingAvailable.asStateFlow()

    private val stores = mutableListOf<ExtensionStore>()

    init {
        scope.launch {
            loadExtensions()
            ExtensionInstallReceiver(InstallationListener(), scope, trustExtension).register(context)

            trustExtension.changes.collectLatest {
                loadExtensions()
            }
        }
    }

    suspend fun awaitInitialization() {
        initialized.await()
    }

    suspend fun refreshAvailableExtensions() {
        if (_isRefreshingAvailable.value) return
        _isRefreshingAvailable.value = true
        try {
            val (fetchedStores, fetchedExtensions) = storeService.fetchAll()
            stores.clear()
            stores.addAll(fetchedStores)
            _availableList.value = fetchedExtensions

            // Update loaded and notLoaded with updates and stores
            val currentLoaded = _loadedMap.value.toMutableMap()
            for ((pkg, loaded) in currentLoaded) {
                val update = loaded.findUpdate(fetchedExtensions)
                val listing = loaded.findListing(fetchedExtensions)
                currentLoaded[pkg] = loaded.copy(
                    hasUpdate = update != null,
                    store = listing?.store ?: loaded.store,
                )
            }
            _loadedMap.value = currentLoaded

            val currentNotLoaded = _notLoadedMap.value.toMutableMap()
            for ((pkg, notLoaded) in currentNotLoaded) {
                val update = notLoaded.findUpdate(fetchedExtensions)
                val listing = notLoaded.findListing(fetchedExtensions)
                currentNotLoaded[pkg] = notLoaded.copy(
                    hasUpdate = update != null,
                    store = listing?.store ?: notLoaded.store,
                )
            }
            _notLoadedMap.value = currentNotLoaded
        } catch (e: Exception) {
            Log.e("ExtensionManager", "Error refreshing available extensions", e)
        } finally {
            _isRefreshingAvailable.value = false
        }
    }

    suspend fun loadExtensions(allowNsfw: Boolean = true) {
        loadMutex.withLock {
            try {
                val alreadyLoaded = _loadedMap.value
                val results = ExtensionLoader.loadExtensions(
                    context = context,
                    trustExtension = trustExtension,
                    stores = stores,
                    allowNsfw = allowNsfw,
                    alreadyLoaded = alreadyLoaded,
                )

                val newLoaded = mutableMapOf<String, Extension.Loaded>()
                val newNotLoaded = mutableMapOf<String, Extension.NotLoaded>()
                val available = _availableList.value

                for (ext in results) {
                    when (ext) {
                        is Extension.Loaded -> {
                            val hasUpdate = ext.findUpdate(available) != null
                            val store = ext.findListing(available)?.store ?: ext.store
                            newLoaded[ext.pkgName] = ext.copy(hasUpdate = hasUpdate, store = store)
                        }
                        is Extension.NotLoaded -> {
                            val hasUpdate = ext.findUpdate(available) != null
                            val store = ext.findListing(available)?.store ?: ext.store
                            newNotLoaded[ext.pkgName] = ext.copy(hasUpdate = hasUpdate, store = store)
                        }
                    }
                }

                _loadedMap.value = newLoaded
                _notLoadedMap.value = newNotLoaded
            } catch (e: Exception) {
                Log.e("ExtensionManager", "Error loading extensions", e)
            } finally {
                _isInitialized.value = true
                if (!initialized.isCompleted) {
                    initialized.complete(Unit)
                }
            }
        }
    }

    fun getLoadedExtensions(): List<Extension.Loaded> = _loadedMap.value.values.toList()

    fun getNotLoadedExtensions(): List<Extension.NotLoaded> = _notLoadedMap.value.values.toList()

    fun installPrivateExtensionFile(file: File, packageInfo: PackageInfo) {
        ExtensionLoader.installPrivateExtensionFile(context, file, packageInfo)
    }

    fun uninstallExtension(pkgName: String) {
        installer.uninstall(pkgName)
    }

    private inner class InstallationListener : ExtensionInstallReceiver.Listener {
        override suspend fun onExtensionLoaded(extension: Extension.Loaded) {
            val available = _availableList.value
            val hasUpdate = extension.findUpdate(available) != null
            val store = extension.findListing(available)?.store ?: extension.store
            val updated = extension.copy(hasUpdate = hasUpdate, store = store)

            _loadedMap.value = _loadedMap.value + (extension.pkgName to updated)
            _notLoadedMap.value = _notLoadedMap.value - extension.pkgName
        }

        override suspend fun onExtensionNotLoaded(extension: Extension.NotLoaded) {
            val available = _availableList.value
            val hasUpdate = extension.findUpdate(available) != null
            val store = extension.findListing(available)?.store ?: extension.store
            val updated = extension.copy(hasUpdate = hasUpdate, store = store)

            _loadedMap.value = _loadedMap.value - extension.pkgName
            _notLoadedMap.value = _notLoadedMap.value + (extension.pkgName to updated)
        }

        override suspend fun onPackageUninstalled(pkgName: String) {
            _loadedMap.value = _loadedMap.value - pkgName
            _notLoadedMap.value = _notLoadedMap.value - pkgName
        }
    }

    companion object {
        @Volatile
        private var instance: ExtensionManager? = null

        fun getInstance(context: Context): ExtensionManager {
            return instance ?: synchronized(this) {
                instance ?: ExtensionManager(
                    context = context.applicationContext,
                    trustExtension = TrustExtension(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}
