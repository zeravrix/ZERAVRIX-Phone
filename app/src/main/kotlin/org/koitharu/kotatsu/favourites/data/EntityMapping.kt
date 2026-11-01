package org.zeravrix.kotatsu.favourites.data

import org.zeravrix.kotatsu.core.db.entity.toManga
import org.zeravrix.kotatsu.core.db.entity.toMangaTags
import org.zeravrix.kotatsu.core.model.FavouriteCategory
import org.zeravrix.kotatsu.list.domain.ListSortOrder
import java.time.Instant

fun FavouriteCategoryEntity.toFavouriteCategory(id: Long = categoryId.toLong()) = FavouriteCategory(
	id = id,
	title = title,
	sortKey = sortKey,
	order = ListSortOrder(order, ListSortOrder.NEWEST),
	createdAt = Instant.ofEpochMilli(createdAt),
	isTrackingEnabled = track,
	isVisibleInLibrary = isVisibleInLibrary,
)

fun FavouriteManga.toManga() = manga.toManga(tags.toMangaTags(), null)

fun Collection<FavouriteManga>.toMangaList() = map { it.toManga() }
