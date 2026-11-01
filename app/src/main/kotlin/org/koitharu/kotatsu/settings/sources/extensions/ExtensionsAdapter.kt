package org.zeravrix.kotatsu.settings.sources.extensions

import android.content.Context
import android.view.View
import org.zeravrix.kotatsu.core.ui.BaseListAdapter
import org.zeravrix.kotatsu.core.ui.list.fastscroll.FastScroller
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.list.ui.adapter.ListItemType
import org.zeravrix.kotatsu.list.ui.adapter.listHeaderAD
import org.zeravrix.kotatsu.list.ui.adapter.loadingStateAD
import org.zeravrix.kotatsu.list.ui.model.ListHeader
import org.zeravrix.kotatsu.list.ui.model.ListModel

class ExtensionsAdapter(
    onItemClick: (Extension) -> Unit,
    onItemLongClick: (Extension, View) -> Boolean,
    onActionClick: (Extension) -> Unit,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

    init {
        addDelegate(ListItemType.HEADER, listHeaderAD(null))
        addDelegate(ListItemType.EXPLORE_SOURCE_LIST, extensionItemAD(onItemClick, onItemLongClick, onActionClick))
        addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
    }

    override fun getSectionText(context: Context, position: Int): CharSequence? {
        return when (val item = items.getOrNull(position)) {
            is ExtensionItemModel -> item.extension.name.take(1).uppercase()
            is ListHeader -> item.getText(context)?.take(1)?.toString()?.uppercase()
            else -> null
        }
    }
}
