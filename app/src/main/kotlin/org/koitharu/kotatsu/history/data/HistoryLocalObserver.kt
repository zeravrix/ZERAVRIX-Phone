package org.zeravrix.kotatsu.history.data

import dagger.Reusable
import org.zeravrix.kotatsu.core.db.MangaDatabase
import org.zeravrix.kotatsu.core.db.entity.toManga
import org.zeravrix.kotatsu.core.db.entity.toMangaTags
import org.zeravrix.kotatsu.history.domain.model.MangaWithHistory
import org.zeravrix.kotatsu.list.domain.ListFilterOption
import org.zeravrix.kotatsu.list.domain.ListSortOrder
import org.zeravrix.kotatsu.local.data.index.LocalMangaIndex
import org.zeravrix.kotatsu.local.domain.LocalObserveMapper
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@Reusable
class HistoryLocalObserver @Inject constructor(
	localMangaIndex: LocalMangaIndex,
	private val db: MangaDatabase,
) : LocalObserveMapper<HistoryWithManga, MangaWithHistory>(localMangaIndex) {

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	) = db.getHistoryDao().observeAll(order, filterOptions, limit).mapToLocal()

	override fun toManga(e: HistoryWithManga) = e.manga.toManga(e.tags.toMangaTags(), null)

	override fun toResult(e: HistoryWithManga, manga: Manga) = MangaWithHistory(
		manga = manga,
		history = e.history.toMangaHistory(),
	)
}
