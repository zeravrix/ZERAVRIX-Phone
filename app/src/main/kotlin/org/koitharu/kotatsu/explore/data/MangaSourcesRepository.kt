package org.zeravrix.kotatsu.explore.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.zeravrix.kotatsu.BuildConfig
import org.zeravrix.kotatsu.core.LocalizedAppContext
import org.zeravrix.kotatsu.core.db.MangaDatabase
import org.zeravrix.kotatsu.core.db.dao.MangaSourcesDao
import org.zeravrix.kotatsu.core.db.entity.MangaSourceEntity
import org.zeravrix.kotatsu.core.model.MangaSourceInfo
import org.zeravrix.kotatsu.core.model.getTitle
import org.zeravrix.kotatsu.core.model.isNsfw
import org.zeravrix.kotatsu.core.parser.external.ExternalMangaSource
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import org.zeravrix.kotatsu.core.prefs.AppSettings
import org.zeravrix.kotatsu.core.prefs.observeAsFlow
import org.zeravrix.kotatsu.core.ui.util.ReversibleHandle
import org.zeravrix.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.zeravrix.kotatsu.extension.ExtensionManager
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val extensionManager: ExtensionManager = ExtensionManager.getInstance(context),
) {

	private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val enabledSourcesCache = AtomicReference<List<MangaSource>>(emptyList())
	private val isNewSourcesAssimilated = AtomicBoolean(false)
	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

	val enabledSourcesSnapshot: List<MangaSource>
		get() = enabledSourcesCache.get()

	init {
		repositoryScope.launch {
			extensionManager.loadedExtensionsFlow.collectLatest { loaded ->
				syncMihonSources(loaded)
			}
		}
	}

	private suspend fun syncMihonSources(loaded: List<Extension.Loaded>) {
		val descriptors = ArrayList<MihonMangaSource>()

		for (ext in loaded) {
			for (source in ext.sources) {
				try {
					val desc = MihonMangaSource(
						packageName = ext.pkgName,
						sourceId = source.id,
						sourceName = source.name,
						language = source.lang,
						isNsfw = ext.contentWarning == ContentWarning.NSFW,
					)
					MihonMangaSource.remember(desc)
					descriptors.add(desc)
				} catch (e: Throwable) {
					android.util.Log.e("MangaSourcesRepository", "Error creating descriptor for ${ext.pkgName}", e)
				}
			}
		}

		val existingEntities = dao.findAll()
		val existingKeys = existingEntities.mapTo(HashSet()) { it.source }
		var maxSortKey = dao.getMaxSortKey()

		val toInsert = ArrayList<MangaSourceEntity>()
		for (desc in descriptors) {
			if (desc.name !in existingKeys) {
				toInsert.add(
					MangaSourceEntity(
						source = desc.name,
						isEnabled = true,
						sortKey = ++maxSortKey,
						addedIn = BuildConfig.VERSION_CODE,
						lastUsedAt = 0,
						isPinned = false,
						cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
					)
				)
			}
		}
		if (toInsert.isNotEmpty()) {
			dao.insertIfAbsent(toInsert)
		}

		if (extensionManager.isInitialized.value) {
			val activePackages = HashSet<String>()
			for (ext in loaded) {
				activePackages.add(ext.pkgName)
			}
			for (ext in extensionManager.getNotLoadedExtensions()) {
				activePackages.add(ext.pkgName)
			}

			val installedPackagesInDb = existingEntities
				.filter { it.source.startsWith("mihon:") }
				.mapNotNull {
					val parts = it.source.removePrefix("mihon:").split(':', limit = 2)
					if (parts.isNotEmpty()) parts[0] else null
				}
				.toSet()

			for (pkg in installedPackagesInDb) {
				if (pkg !in activePackages) {
					dao.deleteByPackage(pkg)
				}
			}
		}
	}

	val allMangaSources: Set<MangaParserSource> = Collections.unmodifiableSet(
		EnumSet.noneOf<MangaParserSource>(MangaParserSource::class.java).also {
			MangaParserSource.entries.filterNotTo(it, MangaParserSource::isBroken)
		}
	)

	suspend fun getEnabledSources(): List<MangaSource> {
		assimilateNewSources()
		val order = settings.sourcesSortOrder
		return dao.findAll(!settings.isAllSourcesEnabled, order).toSources(settings.isNsfwContentDisabled, order)
			.let { enabled ->
				val external = getExternalSources()
				val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
				external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
				list.addAll(enabled)
				enabledSourcesCache.set(list.map { it.mangaSource })
				list
			}
	}

	suspend fun getPinnedSources(): Set<MangaSource> {
		assimilateNewSources()
		val skipNsfw = settings.isNsfwContentDisabled
		return dao.findAllPinned().mapNotNullToSet {
			it.source.toMangaSourceOrNull()?.takeUnless { x -> skipNsfw && x.isNsfw() }
		}
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		assimilateNewSources()
		return dao.findLastUsed(limit).toSources(settings.isNsfwContentDisabled, null)
	}

	suspend fun getDisabledSources(): Set<MangaSource> {
		assimilateNewSources()
		if (settings.isAllSourcesEnabled) {
			return emptySet()
		}
		val result = EnumSet.copyOf(allMangaSources)
		val enabled = dao.findAllEnabledNames()
		for (name in enabled) {
			val source = name.toMangaSourceOrNull() ?: continue
			result.remove(source)
		}
		return result
	}

	suspend fun queryParserSources(
		isDisabledOnly: Boolean,
		isNewOnly: Boolean,
		excludeBroken: Boolean,
		types: Set<ContentType>,
		query: String?,
		locale: String?,
		sortOrder: SourcesSortOrder?,
	): List<MangaParserSource> {
		assimilateNewSources()
		val entities = dao.findAll().toMutableList()
		if (isDisabledOnly && !settings.isAllSourcesEnabled) {
			entities.removeAll { it.isEnabled }
		}
		if (isNewOnly) {
			entities.retainAll { it.addedIn == BuildConfig.VERSION_CODE }
		}
		val sources = entities.toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = sortOrder,
		).run {
			mapNotNullTo(ArrayList(size)) { it.mangaSource as? MangaParserSource }
		}
		if (locale != null) {
			sources.retainAll { it.locale == locale }
		}
		if (excludeBroken) {
			sources.removeAll { it.isBroken }
		}
		if (types.isNotEmpty()) {
			sources.retainAll { it.contentType in types }
		}
		if (!query.isNullOrEmpty()) {
			sources.retainAll {
				it.getTitle(context).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
			}
		}
		return sources
	}

	fun observeIsEnabled(source: MangaSource): Flow<Boolean> {
		return dao.observeIsEnabled(source.name).onStart { assimilateNewSources() }
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
		) { skipNsfw, sources ->
			sources.count {
				it.source.toMangaSourceOrNull()?.let { s -> !skipNsfw || !s.isNsfw() } == true
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
		) { skipNsfw, enabledSources ->
			val enabled = enabledSources.mapToSet { it.source }
			allMangaSources.count { x ->
				x.name !in enabled && (!skipNsfw || !x.isNsfw())
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeIsNsfwDisabled(),
		observeAllEnabled(),
		observeSortOrder(),
	) { skipNsfw, allEnabled, order ->
		dao.observeAll(!allEnabled, order).map {
			it.toSources(skipNsfw, order)
		}
	}.flattenLatest()
		.onStart { assimilateNewSources() }
		.combine(observeExternalSources()) { enabled, external ->
			val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
			external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
			list.addAll(enabled)
			enabledSourcesCache.set(list.map { it.mangaSource })
			list
		}

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = dao.observeAll().map { entities ->
		val result = ArrayList<Pair<MangaSource, Boolean>>(entities.size)
		for (entity in entities) {
			val source = entity.source.toMangaSourceOrNull() ?: continue
			if (source in allMangaSources || source is MihonMangaSource) {
				result.add(source to entity.isEnabled)
			}
		}
		result
	}.onStart { assimilateNewSources() }

	suspend fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
		setSourcesEnabledImpl(sources, isEnabled)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isEnabled)
		}
	}

	suspend fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
		db.withTransaction {
			assimilateNewSources()
			for (s in allMangaSources) {
				dao.setEnabled(s.name, s in sources)
			}
		}
	}

	suspend fun disableAllSources() {
		db.withTransaction {
			assimilateNewSources()
			dao.disableAllSources()
		}
	}

	suspend fun setPositions(sources: List<MangaSource>) {
		db.withTransaction {
			for ((index, item) in sources.withIndex()) {
				dao.setSortKey(item.name, index)
			}
		}
	}

	fun observeHasNewSources(): Flow<Boolean> = observeIsNsfwDisabled().map { skipNsfw ->
		val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(skipNsfw, null)
		sources.isNotEmpty() && sources.size != allMangaSources.size
	}.onStart { assimilateNewSources() }

	fun observeHasNewSourcesForBadge(): Flow<Boolean> = combine(
		settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
		observeIsNsfwDisabled(),
	) { version, skipNsfw ->
		if (version < BuildConfig.VERSION_CODE) {
			val sources = dao.findAllFromVersion(version).toSources(skipNsfw, null)
			sources.isNotEmpty()
		} else {
			false
		}
	}.onStart { assimilateNewSources() }

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = BuildConfig.VERSION_CODE
	}

	private suspend fun assimilateNewSources(): Boolean {
		if (isNewSourcesAssimilated.getAndSet(true)) {
			return false
		}
		val new = getNewSources()
		if (new.isEmpty()) {
			return false
		}
		var maxSortKey = dao.getMaxSortKey()
		val isAllEnabled = settings.isAllSourcesEnabled
		val entities = new.map { x ->
			MangaSourceEntity(
				source = x.name,
				isEnabled = isAllEnabled,
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		return true
	}

	suspend fun isSetupRequired(): Boolean {
		return settings.sourcesVersion == 0 && dao.findAllEnabledNames().isEmpty()
	}

	suspend fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		setSourcesPinnedImpl(sources, isPinned)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isPinned)
		}
	}

	suspend fun trackUsage(source: MangaSource) {
		if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
			dao.setLastUsed(source.name, System.currentTimeMillis())
		}
	}

	suspend fun deleteSourcesByPackage(packageName: String) {
		dao.deleteByPackage(packageName)
	}

	private suspend fun setSourcesEnabledImpl(sources: Collection<MangaSource>, isEnabled: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setEnabled(sources.first().name, isEnabled)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setEnabled(source.name, isEnabled)
			}
		}
	}

	private suspend fun getNewSources(): MutableSet<out MangaSource> {
		val entities = dao.findAll()
		val result = EnumSet.copyOf(allMangaSources)
		for (e in entities) {
			result.remove(e.source.toMangaSourceOrNull() ?: continue)
		}
		return result
	}

	private suspend fun setSourcesPinnedImpl(sources: Collection<MangaSource>, isPinned: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setPinned(sources.first().name, isPinned)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setPinned(source.name, isPinned)
			}
		}
	}

	private fun observeExternalSources(): Flow<List<ExternalMangaSource>> {
		return callbackFlow {
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					trySendBlocking(intent)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_VERIFIED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addAction(Intent.ACTION_PACKAGE_REMOVED)
					addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
					addDataScheme("package")
				},
				ContextCompat.RECEIVER_EXPORTED,
			)
			awaitClose { context.unregisterReceiver(receiver) }
		}.onStart {
			emit(null)
		}.map {
			getExternalSources()
		}.distinctUntilChanged()
			.conflate()
	}

	fun getExternalSources(): List<ExternalMangaSource> {
		val actions = listOf(
			"app.kotatsu.parser.PROVIDE_MANGA",
			"org.koitharu.kotatsu.parser.PROVIDE_MANGA",
			"org.zeravrix.kotatsu.parser.PROVIDE_MANGA",
		)
		val pm = context.packageManager
		val list = ArrayList<ExternalMangaSource>()
		val seenPackages = HashSet<String>()
		for (action in actions) {
			val resolveInfos = pm.queryIntentContentProviders(Intent(action), 0)
			for (resolveInfo in resolveInfos) {
				val pkg = resolveInfo.providerInfo.packageName
				if (seenPackages.add(pkg)) {
					list.add(
						ExternalMangaSource(
							packageName = pkg,
							authority = resolveInfo.providerInfo.authority,
						)
					)
				}
			}
		}
		return list
	}

	private fun List<MangaSourceEntity>.toSources(
		skipNsfwSources: Boolean,
		sortOrder: SourcesSortOrder?,
	): MutableList<MangaSourceInfo> {
		val isAllEnabled = settings.isAllSourcesEnabled
		val result = ArrayList<MangaSourceInfo>(size)
		for (entity in this) {
			val source = entity.source.toMangaSourceOrNull() ?: continue
			if (skipNsfwSources && source.isNsfw()) {
				continue
			}
			result.add(
				MangaSourceInfo(
					mangaSource = source,
					isEnabled = entity.isEnabled || isAllEnabled,
					isPinned = entity.isPinned,
				),
			)
		}
		if (sortOrder == SourcesSortOrder.ALPHABETIC) {
			result.sortWith(compareBy<MangaSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
		}
		return result
	}

	private fun observeIsNsfwDisabled() = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) {
		isNsfwContentDisabled
	}

	private fun observeSortOrder() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) {
		sourcesSortOrder
	}

	private fun observeAllEnabled() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ENABLED_ALL) {
		isAllSourcesEnabled
	}

	private fun String.toMangaSourceOrNull(): MangaSource? {
		if (startsWith("mihon:")) {
			return MihonMangaSource.fromKey(this)
		}
		return MangaParserSource.entries.find { it.name == this }
	}
}
