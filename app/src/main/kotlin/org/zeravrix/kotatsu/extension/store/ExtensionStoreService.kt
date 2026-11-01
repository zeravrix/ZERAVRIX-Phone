package org.zeravrix.kotatsu.extension.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zeravrix.kotatsu.extension.model.ContentWarning
import org.zeravrix.kotatsu.extension.model.Extension
import org.zeravrix.kotatsu.extension.model.ExtensionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class ExtensionStoreService(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    init {
        seedDefaultRepositories()
    }

    fun getRepositories(): Set<String> {
        return preferences.getStringSet(KEY_REPOSITORIES, emptySet())?.toSet().orEmpty()
    }

    fun addRepository(url: String): Boolean {
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val current = HashSet(getRepositories())
        val added = current.add(parsed.toString())
        if (added) {
            preferences.edit().remove(KEY_REPOSITORIES).commit()
            preferences.edit().putStringSet(KEY_REPOSITORIES, current).commit()
        }
        return added
    }

    fun removeRepository(url: String): Boolean {
        val target = url.trim().trimEnd('/')
        val current = HashSet(getRepositories())
        val found = current.find { it.trim().trimEnd('/') == target }
        val removed = if (found != null) current.remove(found) else current.remove(url.trim())
        if (removed) {
            preferences.edit().remove(KEY_REPOSITORIES).commit()
            preferences.edit().putStringSet(KEY_REPOSITORIES, current).commit()
        }
        return removed
    }

    private fun seedDefaultRepositories() {
        val isSeeded = preferences.getBoolean(KEY_REPOS_SEEDED, false)
        if (!isSeeded) {
            val current = preferences.getStringSet(KEY_REPOSITORIES, null)
            if (current == null) {
                preferences.edit()
                    .putStringSet(KEY_REPOSITORIES, HashSet(DEFAULT_REPOSITORIES))
                    .putBoolean(KEY_REPOS_SEEDED, true)
                    .commit()
            } else {
                preferences.edit().putBoolean(KEY_REPOS_SEEDED, true).commit()
            }
        }
    }

    suspend fun fetchAll(): Pair<List<ExtensionStore>, List<Extension.Available>> = withContext(Dispatchers.IO) {
        val repoUrls = getRepositories()
        val allStores = mutableListOf<ExtensionStore>()
        val allExtensions = mutableListOf<Extension.Available>()

        for (url in repoUrls) {
            try {
                val storeResult = fetchStore(url)
                val store = storeResult.getOrNull() ?: continue
                allStores.add(store)

                val extsResult = fetchExtensions(store)
                val exts = extsResult.getOrNull().orEmpty()
                allExtensions.addAll(exts)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch repository $url", e)
            }
        }

        // Deduplicate extensions by packageName + versionCode
        val distinctExtensions = allExtensions
            .groupBy { it.pkgName }
            .mapValues { (_, group) -> group.maxByOrNull { it.versionCode }!! }
            .values
            .toList()

        Pair(allStores, distinctExtensions)
    }

    suspend fun fetchStore(indexUrl: String): Result<ExtensionStore> = withContext(Dispatchers.IO) {
        runCatching {
            var updatedUrl = indexUrl.trim()
            val bytes = request(updatedUrl).decompressIfGzipped()

            when (firstContentByte(bytes)) {
                '['.code -> {
                    // Legacy array: convert index.min.json to repo.json if possible
                    if (updatedUrl.endsWith("/index.min.json")) {
                        val repoJsonUrl = updatedUrl.replace("/index.min.json", "/repo.json")
                        val repoBytes = runCatching { request(repoJsonUrl).decompressIfGzipped() }.getOrNull()
                        if (repoBytes != null) {
                            return@runCatching parseStoreFromJson(repoBytes.toString(Charsets.UTF_8), repoJsonUrl)
                        }
                    }
                    val base = storeBaseUrl(updatedUrl)
                    val name = base.substringAfterLast('/').ifBlank { "Extension Store" }
                    ExtensionStore(
                        indexUrl = updatedUrl,
                        name = name,
                        badgeLabel = name,
                        signingKey = "NO_SIGNING_KEY",
                        website = base,
                        isLegacy = true,
                    )
                }
                '{'.code -> {
                    parseStoreFromJson(bytes.toString(Charsets.UTF_8), updatedUrl)
                }
                else -> {
                    parseStoreFromProtobuf(bytes, updatedUrl)
                }
            }
        }
    }

    suspend fun fetchExtensions(store: ExtensionStore): Result<List<Extension.Available>> = withContext(Dispatchers.IO) {
        runCatching {
            val targetUrl = store.extensionListUrl ?: store.indexUrl
            val bytes = request(targetUrl).decompressIfGzipped()
            val base = storeBaseUrl(store.indexUrl)

            when (firstContentByte(bytes)) {
                '['.code -> {
                    val rawList = json.decodeFromString<List<RawLegacyExtension>>(bytes.toString(Charsets.UTF_8))
                    rawList.map { it.toAvailable(store, base) }
                }
                '{'.code -> {
                    val body = bytes.toString(Charsets.UTF_8)
                    val jsonTree = json.parseToJsonElement(body)
                    if (jsonTree is JsonObject && jsonTree.containsKey("index_v2")) {
                        val indexV2Url = jsonTree["index_v2"]?.jsonPrimitive?.content
                        if (!indexV2Url.isNullOrBlank()) {
                            val resolvedV2 = resolveUrl(indexV2Url, targetUrl)
                            val v2Bytes = request(resolvedV2).decompressIfGzipped()
                            return@runCatching parseProtobufExtensions(v2Bytes, store, resolveBaseUrl(resolvedV2))
                        }
                    }

                    if (jsonTree is JsonObject && jsonTree.containsKey("extensions")) {
                        val wrapper = json.decodeFromString<RawExtensionListWrapper>(body)
                        wrapper.extensions.map { it.toAvailable(store, base) }
                    } else {
                        // Fallback: check index.min.json or index.pb
                        val fallbackMin = "$base/index.min.json"
                        val fallbackBytes = request(fallbackMin).decompressIfGzipped()
                        val rawList = json.decodeFromString<List<RawLegacyExtension>>(fallbackBytes.toString(Charsets.UTF_8))
                        rawList.map { it.toAvailable(store, base) }
                    }
                }
                else -> {
                    parseProtobufExtensions(bytes, store, base)
                }
            }
        }
    }

    private fun parseStoreFromJson(body: String, url: String): ExtensionStore {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: error("Invalid JSON response")

        if (root.containsKey("meta")) {
            val meta = root["meta"]?.let { json.decodeFromJsonElement(RawMeta.serializer(), it) }
            val indexV2 = root["index_v2"]?.jsonPrimitive?.content
            return ExtensionStore(
                indexUrl = indexV2 ?: url,
                name = meta?.name ?: "Extension Store",
                badgeLabel = meta?.shortName ?: meta?.name ?: "Store",
                signingKey = meta?.signingKeyFingerprint ?: "NO_SIGNING_KEY",
                website = meta?.website.orEmpty(),
                isLegacy = true,
            )
        }

        val name = root["name"]?.jsonPrimitive?.content ?: "Extension Store"
        val badge = root["badgeLabel"]?.jsonPrimitive?.content ?: name
        val signingKey = root["signingKey"]?.jsonPrimitive?.content ?: "NO_SIGNING_KEY"
        val extListUrl = root["extensionListUrl"]?.jsonPrimitive?.content

        val contactObj = root["contact"] as? JsonObject
        val website = contactObj?.get("website")?.jsonPrimitive?.content.orEmpty()
        val discord = contactObj?.get("discord")?.jsonPrimitive?.content

        return ExtensionStore(
            indexUrl = url,
            name = name,
            badgeLabel = badge,
            signingKey = signingKey,
            website = website,
            discord = discord,
            isLegacy = false,
            extensionListUrl = extListUrl,
        )
    }

    private fun parseStoreFromProtobuf(bytes: ByteArray, url: String): ExtensionStore {
        val fields = ProtoReader(bytes).readAll()
        val name = fields.firstText(1).ifBlank { "Extension Store" }
        val badge = fields.firstText(2).ifBlank { name }
        val signingKey = fields.firstText(3).ifBlank { "NO_SIGNING_KEY" }
        val contactBytes = fields.firstBytes(4)
        var website = ""
        var discord: String? = null
        if (contactBytes != null) {
            val contactFields = ProtoReader(contactBytes).readAll()
            website = contactFields.firstText(1)
            discord = contactFields.firstText(2).takeIf { it.isNotBlank() }
        }
        val extListUrl = fields.firstText(102).takeIf { it.isNotBlank() }

        return ExtensionStore(
            indexUrl = url,
            name = name,
            badgeLabel = badge,
            signingKey = signingKey,
            website = website,
            discord = discord,
            isLegacy = false,
            extensionListUrl = extListUrl,
        )
    }

    private fun parseProtobufExtensions(bytes: ByteArray, store: ExtensionStore, base: String): List<Extension.Available> {
        val root = ProtoReader(bytes).readAll()
        val container = root.firstOrNull { it.number == 101 && it.bytes != null }?.bytes
        val records = if (container != null) {
            ProtoReader(container).readAll().filter { it.number == 1 && it.bytes != null }.mapNotNull { it.bytes }
        } else {
            root.filter { it.number == 1 && it.bytes != null }.mapNotNull { it.bytes }
                .filter { recordLooksLikeExtension(it) }
        }

        return records.mapNotNull { recordBytes ->
            parseProtobufSingleExtension(recordBytes, store, base)
        }
    }

    private fun recordLooksLikeExtension(bytes: ByteArray): Boolean {
        val fields = ProtoReader(bytes).readAll()
        return fields.any { it.number == 1 && it.textOrNull()?.isNotBlank() == true } &&
            fields.any { it.number == 2 && it.textOrNull()?.contains('.') == true }
    }

    private fun parseProtobufSingleExtension(bytes: ByteArray, store: ExtensionStore, base: String): Extension.Available? {
        val fields = ProtoReader(bytes).readAll()
        val name = fields.firstText(1)
        val packageName = fields.firstText(2)
        if (packageName.isBlank()) return null

        val resourceBytes = fields.firstBytes(3)
        var apkUrl = ""
        var iconUrl = ""
        if (resourceBytes != null) {
            val resFields = ProtoReader(resourceBytes).readAll()
            apkUrl = resFields.firstText(1)
            iconUrl = resFields.firstText(2)
        }
        val libVersionStr = fields.firstText(4)
        val libVersion = libVersionStr.toDoubleOrNull() ?: 1.4
        val versionCode = fields.firstVarint(5)
        val versionName = fields.firstText(6)
        val warningCode = fields.firstVarint(7).toInt()
        val warning = when (warningCode) {
            1 -> ContentWarning.SAFE
            2 -> ContentWarning.MIXED
            3 -> ContentWarning.NSFW
            else -> ContentWarning.SAFE
        }

        val sources = fields.filter { it.number == 8 && it.bytes != null }.mapNotNull { sourceBytes ->
            val sFields = ProtoReader(sourceBytes.bytes!!).readAll()
            val id = sFields.firstVarint(1)
            val sName = sFields.firstText(2)
            val sLang = sFields.firstText(3)
            val sBase = sFields.firstText(4)
            if (sName.isBlank() && sLang.isBlank()) null
            else Extension.Available.SourceInfo(id = id, name = sName, lang = sLang, baseUrl = sBase)
        }

        val resolvedApk = resolveApkUrl(apkUrl, base)
        val resolvedIcon = resolveIconUrl(iconUrl, packageName, base)
        val lang = sources.map { it.lang }.distinct().let { if (it.size == 1) it.first() else "all" }

        return Extension.Available(
            name = name.removePrefix("Tachiyomi: "),
            pkgName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = lang,
            contentWarning = warning,
            sources = sources,
            apkUrl = resolvedApk,
            iconUrl = resolvedIcon,
            store = store,
        )
    }

    private fun request(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "ZERAVRIX/2.0")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            response.body?.bytes() ?: error("Empty body for $url")
        }
    }

    private fun resolveBaseUrl(indexUrl: String): String = storeBaseUrl(indexUrl)

    private fun storeBaseUrl(indexUrl: String): String {
        val clean = indexUrl.substringBefore('?').removeSuffix("/")
        return when {
            clean.endsWith("/repo.json") -> clean.removeSuffix("/repo.json")
            clean.endsWith("/index.min.json") -> clean.removeSuffix("/index.min.json")
            clean.endsWith("/index.json") -> clean.removeSuffix("/index.json")
            clean.endsWith("/index.pb") -> clean.removeSuffix("/index.pb")
            else -> clean.substringBeforeLast('/')
        }
    }

    private fun resolveApkUrl(value: String, base: String): String {
        return value.toHttpUrlOrNull()?.toString()
            ?: if (value.startsWith('/')) {
                base.toHttpUrlOrNull()?.newBuilder()?.encodedPath(value)?.build()?.toString().orEmpty()
            } else {
                base.toHttpUrlOrNull()?.resolve(if (value.contains('/')) value else "apk/$value")?.toString().orEmpty()
            }
    }

    private fun resolveIconUrl(value: String, pkgName: String, base: String): String {
        if (value.isNotBlank()) {
            value.toHttpUrlOrNull()?.toString()?.let { return it }
            base.toHttpUrlOrNull()?.resolve(value)?.toString()?.let { return it }
        }
        return base.toHttpUrlOrNull()?.resolve("icon/$pkgName.png")?.toString().orEmpty()
    }

    private fun resolveUrl(value: String, parent: String): String {
        return value.toHttpUrlOrNull()?.toString()
            ?: parent.toHttpUrlOrNull()?.resolve(value)?.toString()
            ?: value
    }

    private fun firstContentByte(bytes: ByteArray): Int {
        return bytes.firstOrNull { it.toInt().toChar().isWhitespace().not() }?.toInt() ?: -1
    }

    private fun ByteArray.decompressIfGzipped(): ByteArray {
        if (size < 2 || this[0] != 0x1f.toByte() || this[1] != 0x8b.toByte()) return this
        return GZIPInputStream(ByteArrayInputStream(this)).use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output)
            output.toByteArray()
        }
    }

    @Serializable
    private data class RawMeta(
        val name: String,
        val shortName: String? = null,
        val website: String = "",
        val signingKeyFingerprint: String = "",
    )

    @Serializable
    private data class RawExtensionListWrapper(
        val extensions: List<RawLegacyExtension> = emptyList(),
    )

    @Serializable
    private data class RawLegacyExtension(
        val name: String = "",
        @SerialName("pkg") val packageName: String = "",
        @SerialName("apk") val apkUrl: String = "",
        @SerialName("lang") val language: String = "",
        @SerialName("code") val versionCode: Long = 0,
        @SerialName("version") val versionName: String = "",
        val nsfw: Int = 0,
        val sources: List<RawLegacySource>? = null,
    ) {
        fun toAvailable(store: ExtensionStore, base: String): Extension.Available {
            val sourceList = sources?.map {
                Extension.Available.SourceInfo(
                    id = it.id,
                    lang = it.lang,
                    name = it.name,
                    baseUrl = it.baseUrl,
                )
            }.orEmpty()

            val cleanName = name.removePrefix("Tachiyomi: ")
            val lib = versionName.substringBeforeLast('.').toDoubleOrNull() ?: 1.4

            return Extension.Available(
                name = cleanName,
                pkgName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                libVersion = lib,
                lang = language.ifBlank { "all" },
                contentWarning = if (nsfw == 1) ContentWarning.NSFW else ContentWarning.SAFE,
                sources = sourceList,
                apkUrl = if (apkUrl.startsWith("http")) apkUrl else "$base/apk/$apkUrl",
                iconUrl = "$base/icon/$packageName.png",
                store = store,
            )
        }
    }

    @Serializable
    private data class RawLegacySource(
        val id: Long = 0,
        val lang: String = "",
        val name: String = "",
        val baseUrl: String = "",
    )

    companion object {
        private const val TAG = "ExtensionStoreService"
        const val PREFERENCES_NAME = "zeravrix_extensions"
        const val KEY_REPOSITORIES = "repositories"
        const val KEY_REPOS_SEEDED = "repositories_seeded"
        val DEFAULT_REPOSITORIES = setOf(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            "https://raw.githubusercontent.com/kitsumed/mihonyomi-extensions/releases/repo.json",
            "https://raw.githubusercontent.com/Kareadita/tach-extensions/repo/repo.json",
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb",
        )
    }
}

private data class ProtoField(
    val number: Int,
    val wireType: Int,
    val varint: Long = 0,
    val bytes: ByteArray? = null,
) {
    fun textOrNull(): String? = bytes?.toString(Charsets.UTF_8)?.takeIf { text ->
        text.all { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
    }
}

private class ProtoReader(private val data: ByteArray) {
    private var position = 0

    fun readAll(): List<ProtoField> {
        val result = ArrayList<ProtoField>()
        while (position < data.size) result += readField()
        return result
    }

    private fun readField(): ProtoField {
        val tag = readVarint().toInt()
        val number = tag ushr 3
        val wireType = tag and 7
        return when (wireType) {
            0 -> ProtoField(number, wireType, varint = readVarint())
            1 -> ProtoField(number, wireType, bytes = data.copyOfRange(position, position + 8)).also { position += 8 }
            2 -> {
                val length = readVarint().toInt()
                ProtoField(number, wireType, bytes = data.copyOfRange(position, position + length)).also { position += length }
            }
            5 -> ProtoField(number, wireType, bytes = data.copyOfRange(position, position + 4)).also { position += 4 }
            else -> error("Unsupported protobuf wire type $wireType")
        }
    }

    private fun readVarint(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            if (position >= data.size || shift > 63) error("Malformed protobuf varint")
            val byte = data[position++].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }
}

private fun List<ProtoField>.firstText(number: Int): String =
    firstOrNull { it.number == number }?.textOrNull().orEmpty()

private fun List<ProtoField>.firstBytes(number: Int): ByteArray? =
    firstOrNull { it.number == number }?.bytes

private fun List<ProtoField>.firstVarint(number: Int): Long =
    firstOrNull { it.number == number }?.varint ?: 0L
