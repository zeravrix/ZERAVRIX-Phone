package org.zeravrix.kotatsu.extension.trust

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.zeravrix.kotatsu.extension.model.ExtensionStore

class TrustExtension(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zeravrix_trusted_extensions", Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    // Bundled and well-known extension certificate hashes (Keiyoushi, Tachiyomi, ZERAVRIX)
    private val pinnedCertificates = setOf(
        "777e090299f01540cb7e7ce7ab536967da510c4d293d207ec25b292e31379435", // Keiyoushi
        "8434771465e9b7a42ec3e8876c0e7687cf18ff7ab09c4d9b4b0e5ee0d984cfb7", // Official Tachiyomi
    )

    fun isTrusted(
        pkgInfo: PackageInfo,
        fingerprints: List<String>,
        stores: List<ExtensionStore> = emptyList(),
        isBundled: Boolean = false,
    ): Boolean {
        // Auto-trust all installed extensions without requiring manual confirmation
        return true
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        // Remove older entries for this package
        current.removeAll { it.startsWith("$pkgName:") }
        current.add("${pkgName}:$versionCode:$signatureHash")
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
        _changes.value += 1
    }

    fun trust(pkgName: String, signatureHash: String) {
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(pkgName)
        current.add("$pkgName:0:$signatureHash")
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
        _changes.value += 1
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

    private companion object {
        const val KEY_TRUSTED = "trusted_signatures"
    }
}
