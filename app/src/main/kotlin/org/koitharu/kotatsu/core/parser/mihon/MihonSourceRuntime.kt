package org.zeravrix.kotatsu.core.parser.mihon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.util.Log
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.util.ExtensionLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Stable descriptor for one executable source exported by a Mihon extension APK. */
data class MihonMangaSource(
    val packageName: String,
    val sourceId: Long,
    val sourceName: String = "Mihon source $sourceId",
    val language: String = "",
    val isNsfw: Boolean = false,
) : MangaSource {
    override val name: String = "mihon:$packageName:$sourceId"

    init {
        remember(this)
    }

    companion object {
        private val metadata = ConcurrentHashMap<String, MihonMangaSource>()

        fun fromKey(key: String): MihonMangaSource? {
            if (!key.startsWith("mihon:")) return null
            metadata[key]?.let { return it }
            val parts = key.removePrefix("mihon:").split(':', limit = 2)
            if (parts.size != 2) return null
            return MihonMangaSource(parts[0], parts[1].toLongOrNull() ?: return null)
        }

        internal fun remember(source: MihonMangaSource) {
            metadata[source.name] = source
        }

        internal fun retain(keys: Set<String>) {
            metadata.keys.retainAll(keys)
        }
    }
}

data class LoadedMihonSource(
    val descriptor: MihonMangaSource,
    val source: Source,
    val classLoader: ClassLoader,
)

object MihonSourceRuntime {
    private const val TAG = "MihonRuntime"

    fun loadInstalled(context: Context): List<MihonMangaSource> {
        MihonSourceManager.initialize(context)
        return MihonSourceManager.sources.value
    }

    fun installPrivateExtensionFile(context: Context, file: File): Boolean {
        MihonSourceManager.initialize(context)
        val pkgInfo = ExtensionLoader.getArchivePackageInfo(context, file) ?: return false
        return try {
            MihonSourceManager.extensionManager?.installPrivateExtensionFile(file, pkgInfo)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install private extension", e)
            false
        }
    }

    fun uninstallPrivateExtension(context: Context, packageName: String) {
        MihonSourceManager.initialize(context)
        MihonSourceManager.extensionManager?.uninstallExtension(packageName)
    }

    /**
     * Non-blocking getter. Returns real loaded source or a StubSource fallback.
     */
    fun getLoaded(context: Context, descriptor: MihonMangaSource): LoadedMihonSource {
        MihonSourceManager.initialize(context)
        return MihonSourceManager.get(descriptor)
    }

    fun getIcon(context: Context, descriptor: MihonMangaSource): Drawable? =
        getIcon(context, descriptor.packageName)

    fun getIcon(context: Context, packageName: String): Drawable? {
        val pkgManager = context.packageManager
        val density = context.resources.displayMetrics.densityDpi

        // 1. Check private or bundled extension on disk
        val privateExt = File(ExtensionLoader.getPrivateExtensionDir(context), "$packageName.ext")
        if (privateExt.exists()) {
            val archive = pkgManager.getPackageArchiveInfo(privateExt.absolutePath, 0)
            val appInfo = archive?.applicationInfo
            if (appInfo != null) {
                appInfo.sourceDir = privateExt.absolutePath
                appInfo.publicSourceDir = privateExt.absolutePath
                if (appInfo.icon != 0) {
                    val res = runCatching { pkgManager.getResourcesForApplication(appInfo) }.getOrNull()
                    val icon = runCatching { res?.getDrawableForDensity(appInfo.icon, density, null) }.getOrNull()
                    if (icon != null) return icon
                }
                val fallback = runCatching { appInfo.loadIcon(pkgManager) }.getOrNull()
                if (fallback != null) return fallback
            }
        }

        // 2. Check system-installed package
        val sharedInfo = runCatching { pkgManager.getApplicationInfo(packageName, 0) }.getOrNull()
        if (sharedInfo != null) {
            if (sharedInfo.icon != 0) {
                val res = runCatching { pkgManager.getResourcesForApplication(sharedInfo) }.getOrNull()
                val icon = runCatching { res?.getDrawableForDensity(sharedInfo.icon, density, null) }.getOrNull()
                if (icon != null) return icon
            }
            return runCatching { sharedInfo.loadIcon(pkgManager) }.getOrNull()
        }

        return null
    }

    fun requestHeaders(context: Context, descriptor: MihonMangaSource, request: Request? = null): Headers? {
        val httpSource = getLoaded(context, descriptor).source as? HttpSource ?: return null
        val builder = httpSource.headers.newBuilder()
        request?.let { req ->
            val cookies = httpSource.client.cookieJar.loadForRequest(req.url)
            if (cookies.isNotEmpty()) {
                builder.set("Cookie", cookies.joinToString("; ") { it.toString() })
            }
        }
        return builder.build()
    }

    fun isEnabled(context: Context, descriptor: MihonMangaSource): Boolean =
        preferences(context).getBoolean(enabledKey(descriptor), true)

    fun setEnabled(context: Context, descriptor: MihonMangaSource, enabled: Boolean) {
        preferences(context).edit().putBoolean(enabledKey(descriptor), enabled).apply()
        MihonSourceManager.debounceRefresh()
    }

    fun isPinned(context: Context, descriptor: MihonMangaSource): Boolean =
        preferences(context).getBoolean(pinnedKey(descriptor), false)

    fun setPinned(context: Context, descriptor: MihonMangaSource, pinned: Boolean) {
        preferences(context).edit().putBoolean(pinnedKey(descriptor), pinned).apply()
        MihonSourceManager.debounceRefresh()
    }

    fun observeEnabled(context: Context, descriptor: MihonMangaSource, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun stopObservingEnabled(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun normalizeExtensionApiVersion(rawLibVersion: Float, versionName: String?): Double? {
        val parsed = rawLibVersion.takeUnless { it == 0.0f }
            ?.toString()
            ?.toDoubleOrNull()
        if (parsed != null) return parsed
        return versionName?.substringBeforeLast('.')?.toDoubleOrNull()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("mihon_runtime", Context.MODE_PRIVATE)

    private fun enabledKey(descriptor: MihonMangaSource) = "source_enabled_${descriptor.name}"
    private fun pinnedKey(descriptor: MihonMangaSource) = "source_pinned_${descriptor.name}"
}
