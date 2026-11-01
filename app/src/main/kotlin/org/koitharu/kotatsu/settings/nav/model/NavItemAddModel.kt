package org.zeravrix.kotatsu.settings.nav.model

import org.zeravrix.kotatsu.list.ui.model.ListModel

data class NavItemAddModel(
	val canAdd: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean = other is NavItemAddModel
}
