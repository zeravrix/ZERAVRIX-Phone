package org.zeravrix.kotatsu.tracker.ui.feed.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.ui.BaseListAdapter
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener
import org.zeravrix.kotatsu.databinding.ItemListGroupBinding
import org.zeravrix.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.zeravrix.kotatsu.list.ui.adapter.ListItemType
import org.zeravrix.kotatsu.list.ui.adapter.mangaGridItemAD
import org.zeravrix.kotatsu.list.ui.model.ListHeader
import org.zeravrix.kotatsu.list.ui.model.ListModel
import org.zeravrix.kotatsu.list.ui.model.MangaListModel
import org.zeravrix.kotatsu.list.ui.size.ItemSizeResolver
import org.zeravrix.kotatsu.tracker.ui.feed.model.UpdatedMangaHeader

fun updatedMangaAD(
	sizeResolver: ItemSizeResolver,
	listener: OnListItemClickListener<MangaListModel>,
	headerClickListener: ListHeaderClickListener,
) = adapterDelegateViewBinding<UpdatedMangaHeader, ListModel, ItemListGroupBinding>(
	{ layoutInflater, parent -> ItemListGroupBinding.inflate(layoutInflater, parent, false) },
) {

	val adapter = BaseListAdapter<ListModel>()
		.addDelegate(ListItemType.MANGA_GRID, mangaGridItemAD(sizeResolver, listener))
	binding.recyclerView.adapter = adapter
	binding.buttonMore.setOnClickListener { v ->
		headerClickListener.onListHeaderClick(ListHeader(0, payload = item), v)
	}
	binding.textViewTitle.setText(R.string.updates)
	binding.buttonMore.setText(R.string.more)

	bind {
		adapter.items = item.list
	}
}
