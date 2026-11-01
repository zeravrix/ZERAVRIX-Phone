package org.zeravrix.kotatsu.settings.sources.catalog

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.model.getSummary
import org.zeravrix.kotatsu.core.model.getTitle
import org.zeravrix.kotatsu.core.ui.image.FaviconDrawable
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener
import org.zeravrix.kotatsu.core.util.ext.drawableStart
import org.zeravrix.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.zeravrix.kotatsu.core.util.ext.setTextAndVisible
import org.zeravrix.kotatsu.databinding.ItemEmptyHintBinding
import org.zeravrix.kotatsu.databinding.ItemSourceCatalogBinding
import org.zeravrix.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.zeravrix.kotatsu.core.parser.mihon.MihonMangaSource
import androidx.appcompat.R as appcompatR

fun sourceCatalogItemSourceAD(
	listener: OnListItemClickListener<SourceCatalogItem.Source>
) = adapterDelegateViewBinding<SourceCatalogItem.Source, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener { v ->
		listener.onItemLongClick(item, v)
	}
	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	bind {
		binding.progressBarDownload.isVisible = false
		binding.imageViewAdd.isVisible = true
		if (item.isEnabled) {
			binding.imageViewAdd.setImageResource(R.drawable.ic_remove)
			binding.imageViewAdd.contentDescription = context.getString(R.string.remove)
		} else {
			binding.imageViewAdd.setImageResource(R.drawable.ic_add)
			binding.imageViewAdd.contentDescription = context.getString(R.string.add)
		}
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewDescription.text = item.source.getSummary(context)
		binding.textViewDescription.drawableStart = if ((item.source as? MangaParserSource)?.isBroken == true) {
			ContextCompat.getDrawable(context, R.drawable.ic_off_small)
		} else {
			null
		}
		val src = item.source
		binding.textViewBadge.isVisible = (src is MihonMangaSource)
		if (src is MihonMangaSource) {
			val realIcon = org.zeravrix.kotatsu.core.parser.mihon.MihonSourceRuntime.getIcon(context, src)
			if (realIcon != null) {
				binding.imageViewIcon.disposeImage()
				binding.imageViewIcon.setImageDrawable(realIcon)
			} else {
				val customIcon = extensionIconRes(src.packageName)
				if (customIcon != R.drawable.zeravrix_chibi) {
					binding.imageViewIcon.disposeImage()
					binding.imageViewIcon.setImageResource(customIcon)
				} else {
					binding.imageViewIcon.setImageAsync(src)
				}
			}
		} else {
			binding.imageViewIcon.setImageAsync(src)
		}
	}
}

fun sourceCatalogItemExtensionAD(
	listener: OnListItemClickListener<SourceCatalogItem.Extension>,
) = adapterDelegateViewBinding<SourceCatalogItem.Extension, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent -> ItemSourceCatalogBinding.inflate(layoutInflater, parent, false) },
) {
	binding.imageViewAdd.setOnClickListener { v ->
		if (item.isInstalled) {
			listener.onItemLongClick(item, v)
		} else {
			listener.onItemClick(item, v)
		}
	}
	binding.root.setOnClickListener { v -> listener.onItemClick(item, v) }
	bind {
		if (item.isInstalling) {
			binding.progressBarDownload.isVisible = true
			binding.imageViewAdd.isVisible = false
		} else {
			binding.progressBarDownload.isVisible = false
			binding.imageViewAdd.isVisible = true
			if (item.isInstalled) {
				if (item.isEnabled) {
					binding.imageViewAdd.setImageResource(R.drawable.ic_remove)
					binding.imageViewAdd.contentDescription = context.getString(R.string.remove)
				} else {
					binding.imageViewAdd.setImageResource(R.drawable.ic_add)
					binding.imageViewAdd.contentDescription = context.getString(R.string.add)
				}
			} else {
				binding.imageViewAdd.setImageResource(R.drawable.ic_download)
				binding.imageViewAdd.contentDescription = context.getString(R.string.install)
			}
		}
		binding.textViewTitle.text = item.sourceName
		binding.textViewBadge.isVisible = true
		binding.textViewDescription.text = listOfNotNull(
			item.language.takeIf { it.isNotBlank() },
			item.extensionName.takeIf { it.isNotBlank() },
			item.versionName.takeIf { it.isNotBlank() },
		).joinToString(" · ")
		binding.textViewDescription.drawableStart = null
		binding.imageViewIcon.disposeImage()
		if (item.iconRes != R.drawable.zeravrix_chibi) {
			binding.imageViewIcon.setImageResource(item.iconRes)
		} else if (item.iconUrl.isNotBlank()) {
			binding.imageViewIcon.setImageAsync(item.iconUrl)
		} else {
			binding.imageViewIcon.setImageResource(item.iconRes)
		}
	}
}

fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyHintBinding>(
	{ inflater, parent -> ItemEmptyHintBinding.inflate(inflater, parent, false) },
) {
	bind {
		binding.icon.setImageResource(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setText(item.text)
	}
}
