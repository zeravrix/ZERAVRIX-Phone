-optimizationpasses 8
-dontobfuscate
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

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
-keep class org.jsoup.parser.Tag
-keep class org.jsoup.internal.StringUtil

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
-keep class eu.kanade.tachiyomi.source.** { *; }
-keep class eu.kanade.tachiyomi.network.** { *; }
-keep class tachiyomi.core.common.preference.** { *; }
-keep class uy.kohesive.injekt.** { *; }

# Optional Mihon JavaScript/serialization integrations not packaged by the minimal host.
-dontwarn app.cash.quickjs.**
-dontwarn kotlinx.serialization.json.okio.**

# OkHttp and related dependencies for dynamically loaded extensions
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okhttp3.brotli.** { *; }
-keep interface okhttp3.brotli.** { *; }
-dontwarn okhttp3.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.brotli.dec.**
-keep class org.brotli.dec.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-keep class rx.** { *; }
-keep interface rx.** { *; }


