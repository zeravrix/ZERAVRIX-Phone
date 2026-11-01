package org.zeravrix.kotatsu.settings.tracker.categories

import org.zeravrix.kotatsu.core.model.FavouriteCategory
import org.zeravrix.kotatsu.core.ui.BaseListAdapter
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener

class TrackerCategoriesConfigAdapter(
	listener: OnListItemClickListener<FavouriteCategory>,
) : BaseListAdapter<FavouriteCategory>() {

	init {
		delegatesManager.addDelegate(trackerCategoryAD(listener))
	}
}
