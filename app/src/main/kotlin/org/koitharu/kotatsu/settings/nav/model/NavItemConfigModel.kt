package org.zeravrix.kotatsu.settings.nav.model

import androidx.annotation.StringRes
import org.zeravrix.kotatsu.core.prefs.NavItem
import org.zeravrix.kotatsu.list.ui.model.ListModel

data class NavItemConfigModel(
	val item: NavItem,
	@StringRes val disabledHintResId: Int,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is NavItemConfigModel && other.item == item
	}
}
