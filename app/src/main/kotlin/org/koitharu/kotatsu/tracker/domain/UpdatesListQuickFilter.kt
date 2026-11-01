package org.zeravrix.kotatsu.tracker.domain

import org.zeravrix.kotatsu.core.prefs.AppSettings
import org.zeravrix.kotatsu.favourites.domain.FavouritesRepository
import org.zeravrix.kotatsu.list.domain.ListFilterOption
import org.zeravrix.kotatsu.list.domain.MangaListQuickFilter
import javax.inject.Inject

class UpdatesListQuickFilter @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	settings: AppSettings,
) : MangaListQuickFilter(settings) {

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> =
		favouritesRepository.getMostUpdatedCategories(
			limit = 4,
		).map {
			ListFilterOption.Favorite(it)
		}
}
