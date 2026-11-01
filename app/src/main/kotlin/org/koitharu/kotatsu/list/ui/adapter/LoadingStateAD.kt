package org.zeravrix.kotatsu.list.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.list.ui.model.ListModel
import org.zeravrix.kotatsu.list.ui.model.LoadingState

fun loadingStateAD() = adapterDelegate<LoadingState, ListModel>(R.layout.item_loading_state) {
}