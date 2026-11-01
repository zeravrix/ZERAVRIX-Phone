package org.zeravrix.kotatsu.scrobbling.common.domain

import org.zeravrix.kotatsu.scrobbling.anilist.data.AniListRepository
import org.zeravrix.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.zeravrix.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.zeravrix.kotatsu.scrobbling.kitsu.data.KitsuRepository
import org.zeravrix.kotatsu.scrobbling.mal.data.MALRepository
import org.zeravrix.kotatsu.scrobbling.shikimori.data.ShikimoriRepository
import javax.inject.Inject
import javax.inject.Provider

class ScrobblerRepositoryMap @Inject constructor(
	private val shikimoriRepository: Provider<ShikimoriRepository>,
	private val aniListRepository: Provider<AniListRepository>,
	private val malRepository: Provider<MALRepository>,
	private val kitsuRepository: Provider<KitsuRepository>,
) {

	operator fun get(scrobblerService: ScrobblerService): ScrobblerRepository = when (scrobblerService) {
		ScrobblerService.SHIKIMORI -> shikimoriRepository
		ScrobblerService.ANILIST -> aniListRepository
		ScrobblerService.MAL -> malRepository
		ScrobblerService.KITSU -> kitsuRepository
	}.get()
}
