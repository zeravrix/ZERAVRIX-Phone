package org.zeravrix.kotatsu.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.zeravrix.kotatsu.BuildConfig
import org.zeravrix.kotatsu.extension.dalvik.DelegateLastClassLoaderCompat
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.ExtensionStore
import org.zeravrix.kotatsu.extension.trust.TrustExtension
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ExtensionLoader {

    private const val TAG = "ExtensionLoader"

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    // Supported Mihon extension library versions (only 1.4 and 1.6)
    private val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"
    private const val BUNDLED_EXTENSIONS_DIR = "zeravrix_extensions"

    fun getPrivateExtensionDir(context: Context): File {
        val dir = File(context.filesDir, "exts")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getArchivePackageInfo(context: Context, file: File): PackageInfo? {
        val path = file.absolutePath
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
        }
        pkg?.applicationInfo?.let { appInfo ->
            if (appInfo.sourceDir == null) appInfo.sourceDir = path
            if (appInfo.publicSourceDir == null) appInfo.publicSourceDir = path
        }
        return pkg
    }

    /**
     * Installs or updates a private extension APK file with read-only permissions for Android 14+.
     */
    fun installPrivateExtensionFile(context: Context, file: File, extension: PackageInfo) {
        check(isPackageAnExtension(extension)) { "${extension.packageName} isn't an extension" }
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)

        if (currentExtension != null) {
            val extensionSignatures = getSignatures(extension)
            val currentSignatures = getSignatures(currentExtension).orEmpty()
            if (!extensionSignatures.isNullOrEmpty() && currentSignatures.isNotEmpty()) {
                if (!extensionSignatures.containsAll(currentSignatures)) {
                    Log.w(TAG, "Signatures differ for update of ${extension.packageName}, proceeding")
                }
            }
        }

        val target = File(getPrivateExtensionDir(context), "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        val staged = File(target.parentFile, "${target.name}.part")
        try {
            copyAndSetReadOnly(file, staged)
            if (!staged.renameTo(target)) {
                target.delete()
                if (!staged.renameTo(target)) {
                    throw IOException("Unable to replace ${target.name}")
                }
            }
        } finally {
            staged.delete()
        }

        if (currentExtension != null) {
            ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
        } else {
            ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        val file = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        if (file.exists()) {
            file.delete()
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Unpacks any bundled asset APKs into the private extension directory once per app version.
     * Sets read-only permissions so Android 14+ executes them safely.
     */
    fun unpackBundledExtensions(context: Context) {
        val prefs = context.getSharedPreferences("bundled_exts", Context.MODE_PRIVATE)
        val lastUnpackedVersion = prefs.getInt("unpacked_version", 0)
        if (lastUnpackedVersion >= BuildConfig.VERSION_CODE) {
            return
        }
        val assetManager = context.assets
        val assetList = runCatching { assetManager.list(BUNDLED_EXTENSIONS_DIR) }.getOrNull() ?: return
        val extDir = getPrivateExtensionDir(context)

        for (assetName in assetList) {
            if (!assetName.endsWith(".apk", ignoreCase = true)) continue
            try {
                val tempPart = File(extDir, "$assetName.part")
                assetManager.open("$BUNDLED_EXTENSIONS_DIR/$assetName").use { input ->
                    FileOutputStream(tempPart).use { output ->
                        input.copyTo(output)
                    }
                }
                val pkgInfo = getArchivePackageInfo(context, tempPart)
                if (pkgInfo != null && isPackageAnExtension(pkgInfo)) {
                    val target = File(extDir, "${pkgInfo.packageName}.$PRIVATE_EXTENSION_EXTENSION")
                    val existingPkg = if (target.exists()) {
                        getArchivePackageInfo(context, target)
                    } else null

                    val assetVersion = PackageInfoCompat.getLongVersionCode(pkgInfo)
                    val existingVersion = existingPkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L

                    if (assetVersion >= existingVersion) {
                        tempPart.setReadOnly()
                        target.delete()
                        tempPart.renameTo(target)
                    } else {
                        tempPart.delete()
                    }
                } else {
                    tempPart.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unpack bundled asset extension: $assetName", e)
            }
        }
        prefs.edit().putInt("unpacked_version", BuildConfig.VERSION_CODE).apply()
    }

    /**
     * Loads all available installed extensions concurrently.
     * Reuses already loaded instances when the underlying APK hasn't changed.
     */
    suspend fun loadExtensions(
        context: Context,
        trustExtension: TrustExtension,
        stores: List<ExtensionStore> = emptyList(),
        allowNsfw: Boolean = true,
        alreadyLoaded: Map<String, Extension.Loaded> = emptyMap(),
    ): List<Extension.Installed> {
        unpackBundledExtensions(context)
        val pkgManager = context.packageManager

        val discoveryFlags = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA
        val installedPkgs = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(discoveryFlags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pkgManager.getInstalledPackages(discoveryFlags)
            }
        }.getOrElse {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pkgManager.getInstalledPackages(0)
            }
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(it) }
            .map { pkg ->
                val fullPkg = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pkgManager.getPackageInfo(pkg.packageName, PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
                    } else {
                        @Suppress("DEPRECATION")
                        pkgManager.getPackageInfo(pkg.packageName, PACKAGE_FLAGS)
                    }
                }.getOrNull() ?: pkg

                if (fullPkg.applicationInfo?.metaData == null) {
                    val meta = runCatching {
                        pkgManager.getApplicationInfo(fullPkg.packageName, PackageManager.GET_META_DATA).metaData
                    }.getOrNull()
                    if (meta != null) {
                        fullPkg.applicationInfo?.metaData = meta
                    }
                }
                ExtensionInfo(packageInfo = fullPkg, isShared = true)
            }
            .toList()

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull { file ->
                // Ensure read-only permission for Android 14+
                if (file.canWrite()) {
                    file.setReadOnly()
                }
                val path = file.absolutePath
                val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pkgManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                }
                pkg?.also {
                    it.applicationInfo?.fixBasePaths(path)
                    if (it.applicationInfo?.metaData == null) {
                        it.applicationInfo?.metaData = extractMetaDataFallback(path)
                    }
                }
            }
            ?.filter { isPackageAnExtension(it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?.toList()
            .orEmpty()

        val sharedExtPkgsByName = sharedExtPkgs.associateBy { it.packageInfo.packageName }
        val privateExtPkgsByName = privateExtPkgs.associateBy { it.packageInfo.packageName }
        val allPkgNames = (sharedExtPkgs.map { it.packageInfo.packageName } + privateExtPkgs.map { it.packageInfo.packageName }).distinct()

        val extPkgs = allPkgNames.mapNotNull { pkgName ->
            val shared = sharedExtPkgsByName[pkgName]
            val private = privateExtPkgsByName[pkgName]
            selectExtensionPackage(shared, private)
        }

        if (extPkgs.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            extPkgs.map { info ->
                async {
                    loadExtensionCatching(
                        context = context,
                        extensionInfo = info,
                        trustExtension = trustExtension,
                        stores = stores,
                        allowNsfw = allowNsfw,
                        alreadyLoaded = alreadyLoaded[info.packageInfo.packageName],
                    )
                }
            }.awaitAll()
        }
    }

    suspend fun loadExtensionFromPkgName(
        context: Context,
        pkgName: String,
        trustExtension: TrustExtension,
        stores: List<ExtensionStore> = emptyList(),
        allowNsfw: Boolean = true,
        alreadyLoaded: Extension.Loaded? = null,
    ): Extension.Installed? {
        val extensionPackage = getExtensionInfoFromPkgName(context, pkgName) ?: return null
        return loadExtensionCatching(
            context = context,
            extensionInfo = extensionPackage,
            trustExtension = trustExtension,
            stores = stores,
            allowNsfw = allowNsfw,
            alreadyLoaded = alreadyLoaded,
        )
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        return getExtensionInfoFromPkgName(context, pkgName)?.packageInfo
    }

    private fun getExtensionInfoFromPkgName(context: Context, pkgName: String): ExtensionInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            if (privateExtensionFile.canWrite()) {
                privateExtensionFile.setReadOnly()
            }
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) }
                ?.also { it.applicationInfo?.fixBasePaths(privateExtensionFile.absolutePath) }
                ?.let { ExtensionInfo(packageInfo = it, isShared = false) }
        } else null

        val sharedPkg = runCatching {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
                ?.let { ExtensionInfo(packageInfo = it, isShared = true) }
        }.getOrNull()

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        if (shared == null && private == null) return null
        if (shared == null) return private
        if (private == null) return shared

        val sharedVersion = PackageInfoCompat.getLongVersionCode(shared.packageInfo)
        val privateVersion = PackageInfoCompat.getLongVersionCode(private.packageInfo)
        return if (privateVersion > sharedVersion) private else shared
    }

    private suspend fun loadExtensionCatching(
        context: Context,
        extensionInfo: ExtensionInfo,
        trustExtension: TrustExtension,
        stores: List<ExtensionStore>,
        allowNsfw: Boolean,
        alreadyLoaded: Extension.Loaded? = null,
    ): Extension.Installed {
        return try {
            loadExtension(
                context = context,
                extensionInfo = extensionInfo,
                trustExtension = trustExtension,
                stores = stores,
                allowNsfw = allowNsfw,
                alreadyLoaded = alreadyLoaded,
            )
        } catch (e: Throwable) {
            val pkgInfo = extensionInfo.packageInfo
            Log.e(TAG, "Extension load error: ${pkgInfo.packageName}", e)
            val signatures = getSignatures(pkgInfo).orEmpty()
            Extension.NotLoaded(
                name = pkgInfo.packageName,
                pkgName = pkgInfo.packageName,
                versionName = pkgInfo.versionName.orEmpty(),
                versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
                isShared = extensionInfo.isShared,
                contentWarning = ContentWarning.SAFE,
                signatures = signatures,
                store = stores.firstOrNull { it.signingKey in signatures },
                reason = Extension.NotLoaded.Reason.Failed(e.message ?: "Unknown error", e.stackTraceToString()),
            )
        }
    }

    private suspend fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
        trustExtension: TrustExtension,
        stores: List<ExtensionStore>,
        allowNsfw: Boolean,
        alreadyLoaded: Extension.Loaded? = null,
    ): Extension.Installed {
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName
        val appInfo = pkgInfo.applicationInfo
        val apkPath = appInfo?.sourceDir
            ?: (File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")).takeIf { it.exists() }?.absolutePath
        val metaData = appInfo?.metaData ?: apkPath?.let { extractMetaDataFallback(it) }

        val extName = metaData?.getString(METADATA_NAME)
            ?: appInfo?.let { pkgManager.getApplicationLabel(it).toString().substringAfter("Tachiyomi: ") }
            ?: pkgName
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val signatures = getSignatures(pkgInfo).orEmpty()
        val store = stores.firstOrNull { it.signingKey in signatures }

        val contentWarning = when {
            metaData == null -> ContentWarning.SAFE
            metaData.containsKey(METADATA_CONTENT_WARNING) -> {
                when (metaData.getInt(METADATA_CONTENT_WARNING)) {
                    1 -> ContentWarning.MIXED
                    2 -> ContentWarning.NSFW
                    else -> ContentWarning.SAFE
                }
            }
            metaData.getInt(METADATA_NSFW) == 1 -> ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        fun notLoaded(reason: Extension.NotLoaded.Reason, libVersion: Double? = null) =
            Extension.NotLoaded(
                name = extName,
                pkgName = pkgName,
                versionName = versionName.orEmpty(),
                versionCode = versionCode,
                isShared = extensionInfo.isShared,
                contentWarning = contentWarning,
                signatures = signatures,
                store = store,
                libVersion = libVersion,
                reason = reason,
            )

        if (apkPath == null || metaData == null || versionName.isNullOrEmpty()) {
            return notLoaded(Extension.NotLoaded.Reason.Malformed)
        }

        // Validate library version strictly (only 1.4 and 1.6 accepted)
        val libVersion = metaData.getFloat(METADATA_EXTENSION_LIB)
            .takeUnless { it == 0.0f }
            ?.toString()
            ?.toDoubleOrNull()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()

        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            Log.w(TAG, "Unsupported lib version: $libVersion for $pkgName (supported: $SUPPORTED_LIB_VERSIONS)")
            return notLoaded(Extension.NotLoaded.Reason.UnsupportedLibVersion, libVersion)
        }

        if (signatures.isEmpty()) {
            Log.w(TAG, "Package $pkgName is unsigned")
            return notLoaded(Extension.NotLoaded.Reason.Unsigned, libVersion)
        }

        if (!trustExtension.isTrusted(pkgInfo, signatures, stores)) {
            Log.w(TAG, "Extension $pkgName is untrusted")
            return notLoaded(Extension.NotLoaded.Reason.Untrusted(signatures.last()), libVersion)
        }

        if (!allowNsfw && contentWarning == ContentWarning.NSFW) {
            return notLoaded(Extension.NotLoaded.Reason.Filtered, libVersion)
        }

        // Reuse loaded instance if APK has not changed
        if (alreadyLoaded != null &&
            alreadyLoaded.versionCode == versionCode &&
            alreadyLoaded.isShared == extensionInfo.isShared
        ) {
            return alreadyLoaded.copy(store = store)
        }

        val classLoader = try {
            DelegateLastClassLoaderCompat(apkPath, null, context.classLoader)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create classloader for $pkgName", e)
            return notLoaded(Extension.NotLoaded.Reason.Failed(e.message ?: "ClassLoader error", e.stackTraceToString()), libVersion)
        }

        val sourceClasses = metaData.getString(METADATA_SOURCE_CLASS)
        if (sourceClasses.isNullOrBlank()) {
            return notLoaded(Extension.NotLoaded.Reason.Malformed, libVersion)
        }

        val sources = sourceClasses
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith(".")) pkgName + it else it }
            .flatMap { className ->
                try {
                    when (val obj = Class.forName(className, false, classLoader).getDeclaredConstructor().apply { isAccessible = true }.newInstance()) {
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> throw IllegalArgumentException("Unknown source class: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error instantiating $className in $pkgName", e)
                    return notLoaded(Extension.NotLoaded.Reason.Failed(e.message ?: "Class init error", e.stackTraceToString()), libVersion)
                }
            }

        val langs = sources.map { it.lang }.toSet()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        return Extension.Loaded(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            contentWarning = contentWarning,
            isShared = extensionInfo.isShared,
            signatures = signatures,
            pkgFactory = metaData.getString(METADATA_SOURCE_FACTORY),
            sources = sources,
            store = store,
            classLoader = classLoader,
        )
    }

    fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }
    }

    fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            when {
                signingInfo == null -> null
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }?.map { Hash.sha256(it.toByteArray()) }
    }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) sourceDir = apkPath
        if (publicSourceDir == null) publicSourceDir = apkPath
    }

    private fun copyAndSetReadOnly(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        target.setReadOnly()
    }

    private fun extractMetaDataFallback(apkPath: String): android.os.Bundle? {
        return try {
            val assetManagerClass = android.content.res.AssetManager::class.java
            val assetManager = assetManagerClass.getDeclaredConstructor().newInstance()
            val addAssetPath = assetManagerClass.getMethod("addAssetPath", String::class.java)
            val cookie = addAssetPath.invoke(assetManager, apkPath) as Int
            val parser = assetManager.openXmlResourceParser(cookie, "AndroidManifest.xml")
            val bundle = android.os.Bundle()
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "meta-data") {
                    var name: String? = null
                    var value: String? = null
                    for (i in 0 until parser.attributeCount) {
                        val attrName = parser.getAttributeName(i)
                        if (attrName == "name") {
                            name = parser.getAttributeValue(i)
                        } else if (attrName == "value") {
                            value = parser.getAttributeValue(i)
                        }
                    }
                    if (name != null && value != null) {
                        bundle.putString(name, value)
                    }
                }
                eventType = parser.next()
            }
            bundle.takeUnless { it.isEmpty }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to extract fallback metadata from $apkPath", e)
            null
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )
}
