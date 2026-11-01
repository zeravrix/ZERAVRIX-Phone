package org.zeravrix.kotatsu.explore.ui.adapter

import android.view.View
import org.zeravrix.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.zeravrix.kotatsu.list.ui.adapter.ListStateHolderListener

interface ExploreListEventListener : ListStateHolderListener, View.OnClickListener, ListHeaderClickListener
