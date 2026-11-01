package org.zeravrix.kotatsu.scrobbling.common.ui.selector.adapter

import org.zeravrix.kotatsu.core.ui.BaseListAdapter
import org.zeravrix.kotatsu.core.ui.list.OnListItemClickListener
import org.zeravrix.kotatsu.list.ui.adapter.ListItemType
import org.zeravrix.kotatsu.list.ui.adapter.ListStateHolderListener
import org.zeravrix.kotatsu.list.ui.adapter.loadingFooterAD
import org.zeravrix.kotatsu.list.ui.adapter.loadingStateAD
import org.zeravrix.kotatsu.list.ui.model.ListModel
import org.zeravrix.kotatsu.scrobbling.common.domain.model.ScrobblerManga

class ScrobblerSelectorAdapter(
	clickListener: OnListItemClickListener<ScrobblerManga>,
	stateHolderListener: ListStateHolderListener,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(ListItemType.MANGA_SCROBBLING, scrobblingMangaAD(clickListener))
		addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
		addDelegate(ListItemType.HINT_EMPTY, scrobblerHintAD(stateHolderListener))
	}
}
