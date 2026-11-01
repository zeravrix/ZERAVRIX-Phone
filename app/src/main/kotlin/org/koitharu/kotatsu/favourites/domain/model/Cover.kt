package org.zeravrix.kotatsu.favourites.domain.model

import org.zeravrix.kotatsu.core.model.MangaSource

data class Cover(
	val url: String?,
	val source: String,
) {
	val mangaSource by lazy { MangaSource(source) }
}
