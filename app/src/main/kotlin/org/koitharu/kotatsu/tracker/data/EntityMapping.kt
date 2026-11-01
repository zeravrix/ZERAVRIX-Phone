package org.zeravrix.kotatsu.tracker.data

import org.zeravrix.kotatsu.core.db.entity.toManga
import org.zeravrix.kotatsu.core.db.entity.toMangaTags
import org.zeravrix.kotatsu.tracker.domain.model.TrackingLogItem
import java.time.Instant

fun TrackLogWithManga.toTrackingLogItem(): TrackingLogItem {
	val chaptersList = trackLog.chapters.split('\n').filterNot { x -> x.isEmpty() }
	return TrackingLogItem(
		id = trackLog.id,
		chapters = chaptersList,
		manga = manga.toManga(tags.toMangaTags(), null),
		createdAt = Instant.ofEpochMilli(trackLog.createdAt),
		isNew = trackLog.isUnread,
	)
}
