package org.zeravrix.kotatsu.explore.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
import org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager
import org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime
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
import java.util.HashSet
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
) {

	private val isNewSourcesAssimilated = AtomicBoolean(false)
	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

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
					val mihon = getMihonSources()
						.filter { MihonSourceRuntime.isEnabled(context, it) }
						.filterNot { settings.isNsfwContentDisabled && it.isNsfw }
					val list = ArrayList<MangaSourceInfo>(enabled.size + external.size + mihon.size)
						external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
						mihon.filterNot { settings.isNsfwContentDisabled && it.isNsfw }
						.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = MihonSourceRuntime.isPinned(context, it)) }
					list.addAll(enabled)

				list
			}
	}

		suspend fun getPinnedSources(): Set<MangaSource> {
			assimilateNewSources()
			val skipNsfw = settings.isNsfwContentDisabled
			val result = HashSet<MangaSource>().apply {
				addAll(dao.findAllPinned().mapNotNullToSet {
					it.source.toMangaSourceOrNull()?.takeUnless { x -> skipNsfw && x.isNsfw() }
				})
			}
			withContext(Dispatchers.IO) {
getMihonSources()
						.filter { MihonSourceRuntime.isEnabled(context, it) && MihonSourceRuntime.isPinned(context, it) }
					.filterNot { skipNsfw && it.isNsfw }
					.forEach(result::add)
			}
			return result
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
				val result = HashSet<MangaSource>()
			result.addAll(EnumSet.copyOf(allMangaSources))
			val enabled = dao.findAllEnabledNames()
			for (name in enabled) {
				val source = name.toMangaSourceOrNull() ?: continue
				result.remove(source)
			}
			MihonSourceManager.sources.value
				.filter { !MihonSourceRuntime.isEnabled(context, it) }
				.filterNot { settings.isNsfwContentDisabled && it.isNsfw }
				.forEach(result::add)
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
		if (source is MihonMangaSource) {
			return callbackFlow {
				val prefs = context.getSharedPreferences("mihon_runtime", Context.MODE_PRIVATE)
				val key = "source_enabled_${source.name}"
				val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
					if (changed == key) trySend(MihonSourceRuntime.isEnabled(context, source))
				}
				trySend(MihonSourceRuntime.isEnabled(context, source))
				prefs.registerOnSharedPreferenceChangeListener(listener)
				awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
			}
		}
		return dao.observeIsEnabled(source.name).onStart { assimilateNewSources() }
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
				observeEnabledMihonSources(),
			) { skipNsfw, sources, mihon ->
			sources.count {
				it.source.toMangaSourceOrNull()?.let { s -> !skipNsfw || !s.isNsfw() } == true
			} + mihon.count { !skipNsfw || !it.isNsfw }
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
			observeMihonSources(),
		) { skipNsfw, enabledSources, mihonSources ->
			val enabled = enabledSources.mapToSet { it.source }
			val nativeAvailable = allMangaSources.count { x ->
				x.name !in enabled && (!skipNsfw || !x.isNsfw())
			}
			val mihonAvailable = mihonSources.count { source ->
				(!skipNsfw || !source.isNsfw) && !MihonSourceRuntime.isEnabled(context, source)
			}
			nativeAvailable + mihonAvailable
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
				list
			}
				.combine(observeEnabledMihonSources()) { enabled, mihon ->
				val list = ArrayList<MangaSourceInfo>(enabled.size + mihon.size)
				val existingKeys = enabled.mapTo(HashSet<String>()) { it.name }
				mihon.filterNot { it.name in existingKeys }
					.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = MihonSourceRuntime.isPinned(context, it)) }
				list.addAll(enabled)
				list.sortWith(compareBy<MangaSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
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
			MihonSourceManager.sources.value.forEach { mihon ->
				MihonSourceRuntime.setEnabled(context, mihon, mihon in sources)
			}
		}
	}

	suspend fun disableAllSources() {
		db.withTransaction {
			assimilateNewSources()
			dao.disableAllSources()
			MihonSourceManager.sources.value.forEach { MihonSourceRuntime.setEnabled(context, it, false) }
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
				isEnabled = true,
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		if (dao.findAllEnabledNames().isEmpty()) {
			dao.enableAllSources()
		}
		return true
	}

	suspend fun isSetupRequired(): Boolean {
		return settings.sourcesVersion == 0 && dao.findAllEnabledNames().isEmpty()
	}

	suspend fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		setSourcesPinnedImpl(sources, isPinned)
		return ReversibleHandle {
			setSourcesPinnedImpl(sources, !isPinned)
		}
	}

	suspend fun trackUsage(source: MangaSource) {
		if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
			if (source is MihonMangaSource) {
				dao.setEnabled(source.name, true)
			}
			dao.setLastUsed(source.name, System.currentTimeMillis())
		}
	}

	private suspend fun setSourcesEnabledImpl(sources: Collection<MangaSource>, isEnabled: Boolean) {
		val mihon = sources.filterIsInstance<MihonMangaSource>()
		mihon.forEach {
			MihonSourceRuntime.setEnabled(context, it, isEnabled)
			dao.upsert(MangaSourceEntity(source = it.name, isEnabled = isEnabled, sortKey = 0, addedIn = BuildConfig.VERSION_CODE, lastUsedAt = 0L, isPinned = MihonSourceRuntime.isPinned(context, it), cfState = 0))
			dao.setEnabled(it.name, isEnabled)
		}
		val native = sources.filterNot { it is MihonMangaSource }
		if (native.isEmpty()) return
		if (native.size == 1) { // fast path
			dao.setEnabled(native.first().name, isEnabled)
			return
		}
		db.withTransaction {
			for (source in native) {
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
		sources.filterIsInstance<MihonMangaSource>().forEach {
			MihonSourceRuntime.setPinned(context, it, isPinned)
			dao.upsert(MangaSourceEntity(source = it.name, isEnabled = true, sortKey = 0, addedIn = BuildConfig.VERSION_CODE, lastUsedAt = 0L, isPinned = isPinned, cfState = 0))
			dao.setPinned(it.name, isPinned)
		}
		val native = sources.filterNot { it is MihonMangaSource }
		if (native.isEmpty()) return
		if (native.size == 1) { // fast path
			dao.setPinned(native.first().name, isPinned)
			return
		}
		db.withTransaction {
			for (source in native) {
				dao.setPinned(source.name, isPinned)
			}
		}
	}

	fun observeMihonSources(): Flow<List<MihonMangaSource>> {
		MihonSourceManager.initialize(context)
			return combine(MihonSourceManager.sources, MihonSourceManager.revision) { sources, _ -> sources }
			.distinctUntilChanged()
			.conflate()
	}

	private fun observeEnabledMihonSources(): Flow<List<MihonMangaSource>> = observeMihonSources()
		.map { sources -> sources.filter { MihonSourceRuntime.isEnabled(context, it) } }
		.distinctUntilChanged()
		.conflate()

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

	/**
	 * Returns all executable Mihon sources currently visible to the app.
	 * The loader scans shared APKs, private extensions, and bundled assets.
	 */
	suspend fun getMihonSources(): List<MihonMangaSource> = withContext(Dispatchers.IO) {
		MihonSourceManager.initialize(context)
		if (!MihonSourceManager.isInitialized.value) {
			MihonSourceManager.awaitInitialization()
		}
		MihonSourceManager.sources.value
	}

	fun getExternalSources(): List<ExternalMangaSource> {
		val pm = context.packageManager
		val intentZeravrix = Intent("org.zeravrix.kotatsu.parser.PROVIDE_MANGA")
		val intentKoitharu = Intent("org.koitharu.kotatsu.parser.PROVIDE_MANGA")
		val providers = (pm.queryIntentContentProviders(intentZeravrix, 0) +
			pm.queryIntentContentProviders(intentKoitharu, 0))
			.distinctBy { it.providerInfo.packageName }
		return providers.map { resolveInfo ->
			ExternalMangaSource(
				packageName = resolveInfo.providerInfo.packageName,
				authority = resolveInfo.providerInfo.authority,
			)
		}
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
			if (source in allMangaSources || source is MihonMangaSource) {
				result.add(
					MangaSourceInfo(
						mangaSource = source,
						isEnabled = entity.isEnabled || isAllEnabled,
						isPinned = entity.isPinned,
					),
				)
			}
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
