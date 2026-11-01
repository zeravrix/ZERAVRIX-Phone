package org.zeravrix.kotatsu.history.domain.model

import org.zeravrix.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.parsers.model.Manga

data class MangaWithHistory(
	val manga: Manga,
	val history: MangaHistory
)
