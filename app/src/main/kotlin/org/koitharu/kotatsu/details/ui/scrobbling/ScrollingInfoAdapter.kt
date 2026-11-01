package org.zeravrix.kotatsu.details.ui.scrobbling

import org.zeravrix.kotatsu.core.nav.AppRouter
import org.zeravrix.kotatsu.core.ui.BaseListAdapter
import org.zeravrix.kotatsu.list.ui.model.ListModel

class ScrollingInfoAdapter(
	router: AppRouter,
) : BaseListAdapter<ListModel>() {

	init {
		delegatesManager.addDelegate(scrobblingInfoAD(router))
	}
}
