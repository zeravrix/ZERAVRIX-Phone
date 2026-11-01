package org.zeravrix.kotatsu.core

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.internal.platform.PlatformRegistry
import org.conscrypt.Conscrypt
import org.zeravrix.kotatsu.BuildConfig
import org.zeravrix.kotatsu.R
import org.zeravrix.kotatsu.core.db.MangaDatabase
import org.zeravrix.kotatsu.core.os.RomCompat
import org.zeravrix.kotatsu.core.prefs.AppSettings
import org.zeravrix.kotatsu.core.util.ext.processLifecycleScope
import org.zeravrix.kotatsu.core.parser.mihon.MihonRuntimeModule
import org.zeravrix.kotatsu.core.parser.mihon.MihonSourceManager
import org.zeravrix.kotatsu.local.data.LocalStorageChanges
import org.zeravrix.kotatsu.local.data.index.LocalMangaIndex
import org.zeravrix.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.zeravrix.kotatsu.settings.about.WebsiteUpdateWorker
import org.zeravrix.kotatsu.settings.work.WorkScheduleManager
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
open class BaseApp : Application(), Configuration.Provider {

	@Inject
	lateinit var databaseObserversProvider: Provider<Set<@JvmSuppressWildcards InvalidationTracker.Observer>>

	@Inject
	lateinit var activityLifecycleCallbacks: Set<@JvmSuppressWildcards ActivityLifecycleCallbacks>

	@Inject
	lateinit var database: Provider<MangaDatabase>

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var workerFactory: HiltWorkerFactory

	@Inject
	lateinit var workScheduleManager: WorkScheduleManager

	@Inject
	lateinit var localMangaIndexProvider: Provider<LocalMangaIndex>

	@Inject
	@LocalStorageChanges
	lateinit var localStorageChanges: MutableSharedFlow<LocalManga?>

	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	override fun onCreate() {
				super.onCreate()
		PlatformRegistry.applicationContext = this // TODO replace with OkHttp.initialize
		runCatching {
			MihonRuntimeModule.install(this)
			MihonSourceManager.initialize(this)
		}.onFailure { Log.e("MihonRuntime", "Failed to initialize extension runtime", it) }

		AppCompatDelegate.setDefaultNightMode(settings.theme)
		// TLS 1.3 support for Android < 10
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			Security.insertProviderAt(Conscrypt.newProvider(), 1)
		}
		setupActivityLifecycleCallbacks()
		processLifecycleScope.launch(Dispatchers.Default) {
			setupDatabaseObservers()
			localStorageChanges.collect(localMangaIndexProvider.get())
		}
		workScheduleManager.init()
		WorkManager.getInstance(this).enqueueUniquePeriodicWork(
			WEBSITE_UPDATE_WORK_NAME,
			ExistingPeriodicWorkPolicy.KEEP,
			PeriodicWorkRequestBuilder<WebsiteUpdateWorker>(1, TimeUnit.DAYS)
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.build(),
		)
	}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
	}

	private companion object {
		const val WEBSITE_UPDATE_WORK_NAME = "zeravrix-website-update"
	}

	@WorkerThread
	private fun setupDatabaseObservers() {
		val tracker = database.get().invalidationTracker
		databaseObserversProvider.get().forEach {
			tracker.addObserver(it)
		}
	}

	private fun setupActivityLifecycleCallbacks() {
		activityLifecycleCallbacks.forEach {
			registerActivityLifecycleCallbacks(it)
		}
	}
}
