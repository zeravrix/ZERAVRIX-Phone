package org.zeravrix.kotatsu.core.parser.mihon

import android.content.Context
import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.Request
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.zeravrix.kotatsu.extension.ExtensionManager
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.SourceNotInstalledException
import org.zeravrix.kotatsu.extension.model.StubSource
import org.zeravrix.kotatsu.extension.util.ExtensionLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Stable descriptor for one executable source exported by an extension APK. */
data class MihonMangaSource(
    val packageName: String,
    val sourceId: Long,
    val sourceName: String = if (sourceId != 0L) "Source $sourceId" else "Universal Extension Source",
    val language: String = "",
    val isNsfw: Boolean = false,
) : MangaSource {
    override val name: String = "mihon:$packageName:$sourceId"

    init {
        remember(this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MihonMangaSource) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = sourceName

    companion object {
        private val metadata = ConcurrentHashMap<String, MihonMangaSource>()
        private val packageToSource = ConcurrentHashMap<String, MihonMangaSource>()

        fun fromKey(key: String): MihonMangaSource? {
            if (!key.startsWith("mihon:")) return null
            val parts = key.removePrefix("mihon:").split(':', limit = 2)
            if (parts.size != 2) return null
            val pkg = parts[0]
            val parsedId = parts[1].toLongOrNull() ?: return null

            metadata[key]?.let { return it }

            val byPkg = packageToSource[pkg]?.takeIf { it.sourceId == parsedId || parsedId == 0L }
            if (byPkg != null) {
                return byPkg
            }

            return MihonMangaSource(pkg, parsedId)
        }

        internal fun remember(source: MihonMangaSource) {
            metadata[source.name] = source
            if (source.sourceId != 0L || !packageToSource.containsKey(source.packageName)) {
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

    fun normalizeExtensionApiVersion(libVersion: Float, versionName: String?): Double? {
        if (libVersion > 0f) {
            val asDouble = libVersion.toString().toDoubleOrNull()
            if (asDouble != null) return asDouble
        }
        return versionName?.substringBeforeLast('.')?.toDoubleOrNull()
    }

    fun getLoaded(context: Context, descriptor: MihonMangaSource): LoadedMihonSource {
        val mgr = ExtensionManager.getInstance(context)
        val ext = mgr.getLoadedExtensions().find { it.pkgName == descriptor.packageName }
        val src = if (descriptor.sourceId != 0L) {
            ext?.sources?.find { it.id == descriptor.sourceId }
        } else {
            ext?.sources?.firstOrNull()
        }

        if (ext != null && src != null) {
            val updatedDescriptor = descriptor.copy(
                sourceId = src.id,
                sourceName = src.name,
                language = src.lang,
                isNsfw = ext.contentWarning == ContentWarning.NSFW,
            )
            MihonMangaSource.remember(updatedDescriptor)
            return LoadedMihonSource(
                descriptor = updatedDescriptor,
                source = src,
                classLoader = ext.classLoader ?: context.classLoader,
            )
        }

        val stub = StubSource(
            id = descriptor.sourceId,
            lang = descriptor.language,
            name = descriptor.sourceName,
        )
        return LoadedMihonSource(
            descriptor = descriptor,
            source = stub,
            classLoader = context.classLoader,
        )
    }

    suspend fun getLoadedOrAwait(context: Context, descriptor: MihonMangaSource): LoadedMihonSource {
        val mgr = ExtensionManager.getInstance(context)
        withTimeoutOrNull(4000L) {
            mgr.awaitInitialization()
        }
        val loaded = getLoaded(context, descriptor)
        if (loaded.source !is StubSource) {
            return loaded
        }
        withTimeoutOrNull(4000L) {
            mgr.loadExtensions()
        }
        val reloaded = getLoaded(context, descriptor)
        if (reloaded.source is StubSource) {
            throw SourceNotInstalledException(descriptor.sourceId, descriptor.sourceName)
        }
        return reloaded
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
}
