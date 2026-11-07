package org.zeravrix.kotatsu.reader.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.zeravrix.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.parsers.model.Manga

@Parcelize
data class ReaderState(
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
) : Parcelable {

	constructor(history: MangaHistory) : this(
		chapterId = history.chapterId,
		page = history.page,
		scroll = history.scroll,
	)

	constructor(manga: Manga, branch: String?) : this(
		chapterId = manga.chapters?.let { list ->
			val branchChapters = if (branch != null) list.filter { it.branch == branch } else list
			val candidate = branchChapters.ifEmpty { list }
			candidate.filter { it.number > 0f }.minByOrNull { it.number }?.id
				?: candidate.firstOrNull()?.id
		} ?: error("Cannot find first chapter"),
		page = 0,
		scroll = 0,
	)
}
