-dontoptimize
-dontobfuscate

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext

-keep class org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment

-keep class org.koitharu.kotatsu.core.exceptions.* { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }
-keep class org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }

# Mihon host startup and source registry are referenced directly and through APK reflection.
-keep class org.zeravrix.kotatsu.core.parser.mihon.MihonRuntimeModule { *; }
-keep class org.zeravrix.kotatsu.core.parser.mihon.** { *; }
-keep class org.zeravrix.kotatsu.extension.** { *; }

# Optional Mihon network diagnostics/localization classes not required by the minimal host graph.
-dontwarn dev.icerock.moko.resources.**
-dontwarn logcat.**
-dontwarn okhttp3.logging.**
-dontwarn tachiyomi.i18n.**

# Public ABI consumed by separately compiled Mihon extension APKs.
-keep class eu.kanade.tachiyomi.** { *; }
-keep interface eu.kanade.tachiyomi.** { *; }
-keep class tachiyomi.** { *; }
-keep interface tachiyomi.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep interface uy.kohesive.injekt.** { *; }

# Keep entire Kotlin standard library and runtime used by extension APKs
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep interface kotlinx.** { *; }
-keep class kotlin.time.** { *; }
-keep interface kotlin.time.** { *; }

# Jsoup HTML parsing for extensions
-keep class org.jsoup.** { *; }
-keep interface org.jsoup.** { *; }

# OkHttp and related dependencies for dynamically loaded extensions
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okhttp3.brotli.** { *; }
-keep interface okhttp3.brotli.** { *; }
-keep class okhttp3.zstd.** { *; }
-keep interface okhttp3.zstd.** { *; }
-keep class com.github.luben.zstd.** { *; }
-keep class io.github.airgapit.zstd.** { *; }
-dontwarn okhttp3.zstd.**
-dontwarn com.github.luben.zstd.**
-dontwarn io.github.airgapit.zstd.**
-dontwarn okhttp3.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.brotli.dec.**
-keep class org.brotli.dec.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-keep class rx.** { *; }
-keep interface rx.** { *; }
-keep class androidx.preference.** { *; }
-keep interface androidx.preference.** { *; }

# Optional Mihon JavaScript/serialization integrations not packaged by the minimal host.
-dontwarn app.cash.quickjs.**
-dontwarn kotlinx.serialization.json.okio.**
-dontwarn com.hippo.unifile.UniFile
-dontwarn net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
-dontwarn tachiyomi.decoder.**
-dontwarn nucleus.view.NucleusActionBarActivity

# JSON and Serialization commonly referenced by extensions
-keep class com.google.gson.** { *; }
-keep interface com.google.gson.** { *; }
-dontwarn com.google.gson.**



