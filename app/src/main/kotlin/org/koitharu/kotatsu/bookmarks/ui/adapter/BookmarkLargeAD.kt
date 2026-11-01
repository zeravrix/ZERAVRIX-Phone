package org.zeravrix.kotatsu.bookmarks.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.zeravrix.kotatsu.bookmarks.domain.Bookmark
import org.zeravrix.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener
import org.zeravrix.kotatsu.databinding.ItemBookmarkLargeBinding
import org.zeravrix.kotatsu.list.ui.model.ListModel

fun bookmarkLargeAD(
	clickListener: OnListItemClickListener<Bookmark>,
) = adapterDelegateViewBinding<Bookmark, ListModel, ItemBookmarkLargeBinding>(
	{ inflater, parent -> ItemBookmarkLargeBinding.inflate(inflater, parent, false) },
) {
	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)

	bind {
		binding.imageViewThumb.setImageAsync(item)
		binding.progressView.setProgress(item.percent, false)
	}
}
