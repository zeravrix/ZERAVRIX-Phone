package org.zeravrix.kotatsu.extension.trust

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.zeravrix.kotatsu.extension.model.ExtensionStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustExtension @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zeravrix_trusted_extensions", Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()


    fun isTrusted(
        pkgInfo: PackageInfo,
        fingerprints: List<String>,
        stores: List<ExtensionStore> = emptyList(),
        isBundled: Boolean = false,
    ): Boolean {
        if (fingerprints.isEmpty()) return false
        if (isBundled) return true
        if (fingerprints.any { fp -> pinnedCertificates.any { it.equals(fp, ignoreCase = true) } }) return true
        val storeKeys = stores.mapNotNull { it.signingKey.takeIf { k -> k.isNotBlank() && k != "NO_SIGNING_KEY" } }
        if (fingerprints.any { fp -> storeKeys.any { it.equals(fp, ignoreCase = true) } }) return true

        val trusted = prefs.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet()
        if (pkgInfo.packageName in trusted) return true

        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val key = "${pkgInfo.packageName}:$versionCode:${fingerprints.last()}"
        val legacyKey = "${pkgInfo.packageName}:${fingerprints.last()}"
        val zeroKey = "${pkgInfo.packageName}:0:${fingerprints.last()}"

        return key in trusted ||
            legacyKey in trusted ||
            zeroKey in trusted ||
            trusted.any { entry ->
                entry == pkgInfo.packageName ||
                (entry.startsWith("${pkgInfo.packageName}:") && fingerprints.any { fp -> entry.endsWith(":$fp", ignoreCase = true) })
            }
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.removeAll { it.startsWith("$pkgName:") || it == pkgName }
        current.add("$pkgName:$versionCode:$signatureHash")
        current.add("$pkgName:$signatureHash")
        current.add("$pkgName:0:$signatureHash")
        current.add(pkgName)
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
        _changes.value += 1
    }

    fun trust(pkgName: String, signatureHash: String) {
        trust(pkgName, 0L, signatureHash)
    }

    fun revoke(pkgName: String) {
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.removeAll { it.startsWith("$pkgName:") || it == pkgName }
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
        _changes.value += 1
    }

    fun revokeAll() {
        prefs.edit().remove(KEY_TRUSTED).apply()
        _changes.value += 1
    }

    companion object {
        const val KEY_TRUSTED = "trusted_signatures"

        // Bundled and well-known extension certificate hashes (Keiyoushi, Tachiyomi, ZERAVRIX)
        val pinnedCertificates: Set<String> = setOf(
            "777e090299f01540cb7e7ce7ab536967da510c4d293d207ec25b292e31379435", // Keiyoushi
            "8434771465e9b7a42ec3e8876c0e7687cf18ff7ab09c4d9b4b0e5ee0d984cfb7", // Official Tachiyomi
        )
    }
}
