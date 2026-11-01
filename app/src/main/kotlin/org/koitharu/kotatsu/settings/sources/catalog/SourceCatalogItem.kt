package org.zeravrix.kotatsu.settings.sources.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaSource

sealed interface SourceCatalogItem : ListModel {

	data class Source(
		val source: MangaSource,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Source && other.source == source
		}
	}

	data class Extension(
		val sourceName: String,
		val language: String,
		val extensionName: String,
		val packageName: String,
		val versionName: String,
		val apkUrl: String,
		@DrawableRes val iconRes: Int = R.drawable.zeravrix_chibi,
		val iconUrl: String = "",
		val isInstalling: Boolean = false,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Extension && other.packageName == packageName && other.sourceName == sourceName && other.language == language
		}
	}

	data class Hint(
		@DrawableRes val icon: Int,
		@StringRes val title: Int,
		@StringRes val text: Int,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Hint && other.title == title
		}
	}
}
