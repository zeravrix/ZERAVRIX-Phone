package org.zeravrix.kotatsu.history.ui

import android.content.Context
import org.zeravrix.kotatsu.core.ui.list.fastscroll.FastScroller
import org.zeravrix.kotatsu.list.ui.adapter.MangaListAdapter
import org.zeravrix.kotatsu.list.ui.adapter.MangaListListener
import org.zeravrix.kotatsu.list.ui.size.ItemSizeResolver

class HistoryListAdapter(
	listener: MangaListListener,
	sizeResolver: ItemSizeResolver,
) : MangaListAdapter(listener, sizeResolver), FastScroller.SectionIndexer {

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return findHeader(position)?.getText(context)
	}
}
