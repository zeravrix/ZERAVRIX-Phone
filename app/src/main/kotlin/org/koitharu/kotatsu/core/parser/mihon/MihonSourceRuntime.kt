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
        private val packageToSource = ConcurrentHashMap<String, MihonMangaSource>()

        fun fromKey(key: String): MihonMangaSource? {
            if (!key.startsWith("mihon:")) return null
            val parts = key.removePrefix("mihon:").split(':', limit = 2)
            if (parts.size != 2) return null
            val pkg = parts[0]
            val parsedId = parts[1].toLongOrNull() ?: return null

            // Prioritize finding a loaded source with real non-zero ID
            val byPkg = MihonSourceManager.sources.value.find { it.packageName == pkg && it.sourceId != 0L }
                ?: packageToSource[pkg]?.takeIf { it.sourceId != 0L }
                ?: metadata.values.find { it.packageName == pkg && it.sourceId != 0L }

            if (byPkg != null) {
                return byPkg
            }

            if (parsedId != 0L) {
                metadata[key]?.let { return it }
            }

            return MihonMangaSource(pkg, parsedId)
        }

        internal fun remember(source: MihonMangaSource) {
            if (source.sourceId != 0L) {
                metadata[source.name] = source
                packageToSource[source.packageName] = source
            }
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

    private val iconCache = ConcurrentHashMap<String, Drawable>()

    fun getIcon(context: Context, descriptor: MihonMangaSource): Drawable? =
        getIcon(context, descriptor.packageName)

    fun getIcon(context: Context, packageName: String): Drawable? {
        iconCache[packageName]?.let { return it }

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
                    if (icon != null) {
                        iconCache[packageName] = icon
                        return icon
                    }
                }
                val fallback = runCatching { appInfo.loadIcon(pkgManager) }.getOrNull()
                if (fallback != null) {
                    iconCache[packageName] = fallback
                    return fallback
                }
            }
        }

        // 2. Check system-installed package
        val sharedInfo = runCatching { pkgManager.getApplicationInfo(packageName, 0) }.getOrNull()
        if (sharedInfo != null) {
            if (sharedInfo.icon != 0) {
                val res = runCatching { pkgManager.getResourcesForApplication(sharedInfo) }.getOrNull()
                val icon = runCatching { res?.getDrawableForDensity(sharedInfo.icon, density, null) }.getOrNull()
                if (icon != null) {
                    iconCache[packageName] = icon
                    return icon
                }
            }
            val fallback = runCatching { sharedInfo.loadIcon(pkgManager) }.getOrNull()
            if (fallback != null) {
                iconCache[packageName] = fallback
                return fallback
            }
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

    fun isEnabled(context: Context, descriptor: MihonMangaSource): Boolean {
        val prefs = preferences(context)
        val key = enabledKey(descriptor)
        if (prefs.contains(key)) return prefs.getBoolean(key, true)
        val legacyKey = "enabled:${descriptor.name}"
        if (prefs.contains(legacyKey)) return prefs.getBoolean(legacyKey, true)
        val pkgKey = "source_enabled_pkg_${descriptor.packageName}"
        if (prefs.contains(pkgKey)) return prefs.getBoolean(pkgKey, true)
        return true
    }

    fun isPackageEnabled(context: Context, packageName: String): Boolean {
        val prefs = preferences(context)
        val pkgKey = "source_enabled_pkg_$packageName"
        if (prefs.contains(pkgKey)) return prefs.getBoolean(pkgKey, true)
        for (key in prefs.all.keys) {
            if (key.startsWith("source_enabled_mihon:$packageName:") && prefs.getBoolean(key, true) == false) {
                return false
            }
        }
        return true
    }

    fun setEnabled(context: Context, descriptor: MihonMangaSource, enabled: Boolean) {
        preferences(context).edit()
            .putBoolean(enabledKey(descriptor), enabled)
            .putBoolean("enabled:${descriptor.name}", enabled)
            .putBoolean("source_enabled_pkg_${descriptor.packageName}", enabled)
            .apply()
        MihonSourceManager.debounceRefresh()
    }

    fun isPinned(context: Context, descriptor: MihonMangaSource): Boolean {
        return isPackagePinned(context, descriptor.packageName, descriptor.name)
    }

    fun isPackagePinned(context: Context, packageName: String, sourceName: String? = null): Boolean {
        val prefs = preferences(context)
        if (sourceName != null) {
            val key = "source_pinned_$sourceName"
            if (prefs.contains(key)) return prefs.getBoolean(key, true)
            val legacyKey = "pinned:$sourceName"
            if (prefs.contains(legacyKey)) return prefs.getBoolean(legacyKey, true)
        }
        val pkgKey = "source_pinned_pkg_$packageName"
        if (prefs.contains(pkgKey)) return prefs.getBoolean(pkgKey, true)
        return true
    }

    fun setPinned(context: Context, descriptor: MihonMangaSource, pinned: Boolean) {
        setPackagePinned(context, descriptor.packageName, descriptor.name, pinned)
    }

    fun setPackagePinned(context: Context, packageName: String, sourceName: String? = null, pinned: Boolean) {
        val editor = preferences(context).edit()
            .putBoolean("source_pinned_pkg_$packageName", pinned)
        if (sourceName != null) {
            editor.putBoolean("source_pinned_$sourceName", pinned)
            editor.putBoolean("pinned:$sourceName", pinned)
        }
        for (source in MihonSourceManager.sources.value.filter { it.packageName == packageName }) {
            editor.putBoolean(pinnedKey(source), pinned)
            editor.putBoolean("pinned:${source.name}", pinned)
        }
        editor.apply()
        MihonSourceManager.debounceRefresh()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("mihon_runtime", Context.MODE_PRIVATE)

    private fun enabledKey(descriptor: MihonMangaSource) = "source_enabled_${descriptor.name}"
    private fun pinnedKey(descriptor: MihonMangaSource) = "source_pinned_${descriptor.name}"
}
