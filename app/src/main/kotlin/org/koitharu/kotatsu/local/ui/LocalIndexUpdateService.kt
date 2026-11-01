package org.zeravrix.kotatsu.local.ui

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.zeravrix.kotatsu.core.ui.CoroutineIntentService
import org.zeravrix.kotatsu.local.data.index.LocalMangaIndex
import javax.inject.Inject

@AndroidEntryPoint
class LocalIndexUpdateService : CoroutineIntentService() {

	@Inject
	lateinit var localMangaIndex: LocalMangaIndex

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		localMangaIndex.update()
	}

	override fun IntentJobContext.onError(error: Throwable) = Unit
}
