package org.zeravrix.kotatsu.settings.sources.catalog

import android.content.Context
import org.zeravrix.kotatsu.core.model.getTitle
import org.zeravrix.kotatsu.core.ui.BaseListAdapter
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener
import org.zeravrix.kotatsu.core.ui.list.fastscroll.FastScroller
import org.zeravrix.kotatsu.list.ui.adapter.ListItemType
import org.zeravrix.kotatsu.list.ui.adapter.loadingStateAD
import org.zeravrix.kotatsu.list.ui.model.ListModel

class SourcesCatalogAdapter(
	sourceListener: OnListItemClickListener<SourceCatalogItem.Source>,
	extensionListener: OnListItemClickListener<SourceCatalogItem.Extension>,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.CHAPTER_LIST, sourceCatalogItemSourceAD(sourceListener))
		addDelegate(ListItemType.EXPLORE_SOURCE_LIST, sourceCatalogItemExtensionAD(extensionListener))
		addDelegate(ListItemType.HINT_EMPTY, sourceCatalogItemHintAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}

			override fun getSectionText(context: Context, position: Int): CharSequence? {
			return when (val item = items.getOrNull(position)) {
				is SourceCatalogItem.Source -> item.source.getTitle(context).take(1)
				is SourceCatalogItem.Extension -> item.sourceName.take(1)
				else -> null
			}
		}

}
