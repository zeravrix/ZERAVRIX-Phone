package org.zeravrix.kotatsu.list.ui.model

import org.zeravrix.kotatsu.core.ui.widgets.ChipsView
import org.zeravrix.kotatsu.list.ui.ListModelDiffCallback

data class QuickFilter(
	val items: List<ChipsView.ChipModel>,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean = other is QuickFilter

	override fun getChangePayload(previousState: ListModel) = ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
}
