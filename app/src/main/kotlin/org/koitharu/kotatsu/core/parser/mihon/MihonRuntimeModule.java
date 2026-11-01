package org.zeravrix.kotatsu.core.parser.mihon;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import eu.kanade.tachiyomi.network.JavaScriptEngine;
import eu.kanade.tachiyomi.network.NetworkHelper;
import eu.kanade.tachiyomi.network.NetworkPreferences;
import tachiyomi.core.common.preference.AndroidPreferenceStore;
import tachiyomi.core.common.preference.PreferenceStore;
import uy.kohesive.injekt.api.InjektModule;
import uy.kohesive.injekt.api.InjektRegistrar;
import uy.kohesive.injekt.api.TypeReference;

/** Minimal host service graph required by Mihon source extensions. */
public final class MihonRuntimeModule implements InjektModule {

    private final Application application;

    public MihonRuntimeModule(Application application) {
        this.application = application;
    }

    public static void install(Application application) {
        uy.kohesive.injekt.InjektKt.getInjekt().importModule(new MihonRuntimeModule(application));
    }

    @Override
    public void registerWith(InjektRegistrar registrar) {
        registerInjectables(registrar);
    }

    @Override
    public void registerInjectables(InjektRegistrar registrar) {
        PreferenceStore store = new AndroidPreferenceStore(
                application,
                application.getSharedPreferences("mihon_network", Context.MODE_PRIVATE)
        );
        NetworkPreferences preferences = new NetworkPreferences(store, false);
        NetworkHelper network = new NetworkHelper(application, preferences);

        registrar.addSingleton(type(PreferenceStore.class), store);
        registrar.addSingleton(type(NetworkPreferences.class), preferences);
        registrar.addSingleton(type(NetworkHelper.class), network);
        try {
            registrar.addSingleton(type(JavaScriptEngine.class), new JavaScriptEngine(application));
        } catch (Throwable error) {
            // Non-JavaScript extensions must remain usable if the optional engine is unavailable.
            Log.e("MihonRuntime", "Optional JavaScript engine unavailable", error);
        }
    }

    private static <T> TypeReference<T> type(final Class<T> clazz) {
        return new TypeReference<T>() {
            @Override
            public java.lang.reflect.Type getType() {
                return clazz;
            }
        };
    }
}
