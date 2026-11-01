package org.zeravrix.kotatsu.extension.model

import eu.kanade.tachiyomi.source.Source

enum class ContentWarning {
    SAFE,
    MIXED,
    NSFW,
}

data class ExtensionStore(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val signingKey: String,
    val website: String = "",
    val discord: String? = null,
    val isLegacy: Boolean = false,
    val extensionListUrl: String? = null,
)

sealed interface Extension {
    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double?
    val lang: String?
    val contentWarning: ContentWarning

    /**
     * An extension whose apk is on the device, whether loaded or not.
     */
    sealed interface Installed : Extension {
        val isShared: Boolean
        val signatures: List<String>
        val hasUpdate: Boolean
        val store: ExtensionStore?

        fun findListing(available: Collection<Available>): Available? {
            return available
                .filter { it.pkgName == pkgName && (it.store.signingKey == "NO_SIGNING_KEY" || it.store.signingKey.isBlank() || it.store.signingKey in signatures) }
                .maxWithOrNull(compareBy<Available> { it.versionCode }.thenBy { it.libVersion })
        }

        fun findUpdate(available: Collection<Available>): Available? {
            val installedLib = libVersion
            return findListing(available)?.takeIf {
                it.versionCode > versionCode || (installedLib != null && it.libVersion > installedLib)
            }
        }
    }

    /**
     * An extension available in an extension store.
     */
    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentWarning: ContentWarning,
        val sources: List<SourceInfo>,
        val apkUrl: String,
        val iconUrl: String,
        val store: ExtensionStore,
    ) : Extension {
        data class SourceInfo(
            val id: Long,
            val lang: String,
            val name: String,
            val baseUrl: String,
        )
    }

    /**
     * An installed extension whose sources are loaded and ready to execute.
     */
    data class Loaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val contentWarning: ContentWarning,
        override val isShared: Boolean,
        override val signatures: List<String>,
        val pkgFactory: String?,
        val sources: List<Source>,
        override val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        override val store: ExtensionStore? = null,
        val classLoader: ClassLoader? = null,
    ) : Installed

    /**
     * An installed extension that failed to load or was filtered.
     */
    data class NotLoaded(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val isShared: Boolean,
        override val contentWarning: ContentWarning,
        override val signatures: List<String>,
        override val libVersion: Double? = null,
        override val lang: String? = null,
        override val hasUpdate: Boolean = false,
        override val store: ExtensionStore? = null,
        val reason: Reason,
    ) : Installed {

        sealed interface Reason {
            data class Untrusted(val signatureHash: String) : Reason
            data object Filtered : Reason
            data object Unsigned : Reason
            data object UnsupportedLibVersion : Reason
            data object Malformed : Reason
            data class Failed(val message: String, val stackTrace: String) : Reason
        }
    }
}
