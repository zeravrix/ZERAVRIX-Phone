package org.zeravrix.kotatsu.core.parser.mihon

import android.app.Application
import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton

class MihonRuntimeModule(private val application: Application) : InjektModule {

    companion object {
        @JvmStatic
        fun install(application: Application) {
            Injekt.importModule(MihonRuntimeModule(application))
        }
    }

    override fun InjektRegistrar.registerInjectables() {
        val store: PreferenceStore = AndroidPreferenceStore(
            application,
            application.getSharedPreferences("mihon_network", Context.MODE_PRIVATE),
        )
        val preferences = NetworkPreferences(store, false)
        val network = NetworkHelper(application, preferences)
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        addSingleton(application)
        addSingleton<Context>(application)
        addSingleton<PreferenceStore>(store)
        addSingleton<NetworkPreferences>(preferences)
        addSingleton<NetworkHelper>(network)
        addSingleton<Json>(json)
        try {
            addSingleton(JavaScriptEngine(application))
        } catch (error: Throwable) {
            Log.e("MihonRuntime", "Optional JavaScript engine unavailable", error)
        }
    }
}
