package org.zeravrix.kotatsu.list.ui.adapter

import org.zeravrix.kotatsu.list.domain.ListFilterOption

interface QuickFilterClickListener {

	fun onFilterOptionClick(option: ListFilterOption)
}
