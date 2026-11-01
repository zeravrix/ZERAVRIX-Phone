package org.zeravrix.kotatsu.favourites.ui

import android.os.Bundle
import org.zeravrix.kotatsu.core.nav.AppRouter
import org.zeravrix.kotatsu.core.ui.FragmentContainerActivity
import org.zeravrix.kotatsu.favourites.ui.list.FavouritesListFragment

class FavouritesActivity : FragmentContainerActivity(FavouritesListFragment::class.java) {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val categoryTitle = intent.getStringExtra(AppRouter.KEY_TITLE)
		if (categoryTitle != null) {
			title = categoryTitle
		}
	}
}
