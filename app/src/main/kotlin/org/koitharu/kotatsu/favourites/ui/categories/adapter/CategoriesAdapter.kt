package org.zeravrix.kotatsu.favourites.ui.categories.adapter

import org.zeravrix.kotatsu.core.ui.ReorderableListAdapter
import org.zeravrix.kotatsu.favourites.ui.categories.FavouriteCategoriesListListener
import org.zeravrix.kotatsu.list.ui.adapter.ListItemType
import org.zeravrix.kotatsu.list.ui.adapter.ListStateHolderListener
import org.zeravrix.kotatsu.list.ui.adapter.emptyStateListAD
import org.zeravrix.kotatsu.list.ui.adapter.loadingStateAD
import org.zeravrix.kotatsu.list.ui.model.ListModel

class CategoriesAdapter(
	onItemClickListener: FavouriteCategoriesListListener,
	listListener: ListStateHolderListener,
) : ReorderableListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.CATEGORY_LARGE, categoryAD(onItemClickListener))
		addDelegate(ListItemType.NAV_ITEM, allCategoriesAD(onItemClickListener))
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(listListener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}
}
