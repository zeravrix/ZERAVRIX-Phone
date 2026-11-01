package org.zeravrix.kotatsu.bookmarks.ui.adapter

import android.content.Context
import org.zeravrix.kotatsu.bookmarks.domain.Bookmark
import org.zeravrix.kotatsu.core.ui.BaseListAdapter
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener
import org.zeravrix.kotatsu.core.ui.list.fastscroll.FastScroller
import org.zeravrix.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.zeravrix.kotatsu.list.ui.adapter.ListItemType
import org.zeravrix.kotatsu.list.ui.adapter.emptyStateListAD
import org.zeravrix.kotatsu.list.ui.adapter.errorStateListAD
import org.zeravrix.kotatsu.list.ui.adapter.listHeaderAD
import org.zeravrix.kotatsu.list.ui.adapter.loadingFooterAD
import org.zeravrix.kotatsu.list.ui.adapter.loadingStateAD
import org.zeravrix.kotatsu.list.ui.model.ListModel

class BookmarksAdapter(
	clickListener: OnListItemClickListener<Bookmark>,
	headerClickListener: ListHeaderClickListener?,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.PAGE_THUMB, bookmarkLargeAD(clickListener))
		addDelegate(ListItemType.HEADER, listHeaderAD(headerClickListener))
		addDelegate(ListItemType.STATE_ERROR, errorStateListAD(null))
		addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return findHeader(position)?.getText(context)
	}
}
