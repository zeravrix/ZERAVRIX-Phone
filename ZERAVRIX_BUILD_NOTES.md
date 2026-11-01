# ZERAVRIX 1.4.0 — full Mihon source-runtime rebuild

## Scope of this candidate

This candidate is not a metadata-only extension catalog update. The Mihon 0.20.4 source-api and core-common modules were built from the supplied Mihon source archive and integrated into the ZERAVRIX application. The previous reduced AAR copies were removed from the project. The application continues to use package ID `org.zeravrix.kotatsu`, remains separately installable from official Kotatsu, and keeps visible version name `1.4.0`. The Android internal version code for this candidate is `1038`.

The existing Kotatsu application remains the host for library, history, reader, downloads, resume state, update checks, source settings, and navigation. Mihon sources enter that host through a rebuilt executable source-manager boundary rather than through decorative catalog rows.

## Mihon loader and source-manager rebuild

The attached Mihon 0.20.4 `ExtensionLoader` and `AndroidSourceManager` were used as the reference implementation. ZERAVRIX now has a dedicated `MihonSourceManager` with an initialized `StateFlow` of executable sources. It owns refreshes and is initialized from application startup. It refreshes after package installation, replacement, removal, private-extension changes, and Mihon runtime preference changes. Explore, source management, source counts, source suggestions, details, update checks, reader requests, and downloads use the same runtime registry or the same stable source key.

The loader now handles installed shared APKs, app-private `.ext` extension files, and the nine supplied bundled APK assets. It applies version precedence between shared/private/bundled copies, fixes archive `sourceDir` and `publicSourceDir` paths, supports relative `tachiyomi.extension.class` names such as `.ExtensionGenerated`, supports both `Source` and `SourceFactory`, uses Mihon’s parent-last classloader behavior, preserves source metadata, and invalidates stale loaded instances after a package change. Local or downloaded APKs can be copied into the app-private extension directory and become executable sources immediately without depending on Android’s external package installer.

A critical compatibility defect was corrected in the API-library check. Mihon APK manifests store library versions as Android float values such as `0x3fcccccd`; comparing the raw float converted directly to `Double` rejects `1.6` because its binary value is `1.6000000238`. ZERAVRIX now normalizes the float through `Float.toString()` exactly like Mihon before comparing supported library versions `1.4` and `1.6`. Regression tests cover both values and the version-name fallback.

Another critical defect was a namespace split between the host module and the ZERAVRIX runtime. The rebuilt startup module, source manager, runtime, and adapter now use the coherent `org.zeravrix.kotatsu.core.parser.mihon` namespace. Application startup links the host module directly instead of silently relying on reflection, and R8 keep rules preserve the complete runtime and reflective extension entry points.

The original Mihon AARs require a higher SDK declaration than the Kotatsu base app. The build retains the requested older application minimum SDK using an explicit manifest override, while keeping the original Mihon class artifacts and Kotlin metadata. R8 still reports Kotlin metadata-version warnings because Mihon 0.20.4 was built with newer Kotlin metadata; the release build completes, but this remains a compatibility boundary that must be exercised on Android.

## Explore and Kotatsu lifecycle integration

Every successfully loaded Mihon source is represented by a stable key of the form `mihon:<package>:<sourceId>`. Dynamic sources are enabled by default, can be pinned or disabled, and are filtered by the existing NSFW preference. They are merged into the ordinary Explore source flow and dispatched through the normal remote-list screen and `MangaRepository.Factory` branch.

The adapter maps Mihon `SManga`, `MangasPage`, `SMangaUpdate`, `SChapter`, and `Page` objects into Kotatsu manga, chapter, page, and source models. Search, popular, latest, details, chapter refresh, reader page resolution, library persistence, and downloads are connected. Mihon source headers and cookies are applied to source-tagged reader/download requests. Mihon repository caching is disabled to prevent stale empty chapters or pages from surviving a repaired extension. Tracked Mihon manga and downloads request fresh details/chapter data. Mihon source usage is persisted into Kotatsu’s source database so recent-source behavior and source ordering survive restarts.

Source management now includes Mihon sources in enabled/disabled actions, exclusive source setup, disable-all, enabled counts, source suggestions, and the normal source-management screen. Global source suggestions include loaded Mihon descriptors. Native parser-only settings remain limited to native parser sources, while executable Mihon sources retain the actions that are valid for them.

## TeamXNovel/OlympusStaff

TeamXNovel is detected from its OlympusStaff base URL. The compatibility path supports the older `select#select_chapter option` layout and the current live chapter-card layout. It resolves relative URLs using Mihon’s `getMangaUrl`/`getChapterUrl` behavior before falling back to OlympusStaff’s base URL. It extracts Arabic or English chapter numbers, titles, numeric URL fallbacks, and chapter links.

For reader pages, the parser prefers OlympusStaff’s real `div.image_list img` container and falls back to the older image attributes only if the container is absent. A saved live fixture from `https://olympustaff.com/series/surviving-in-another-world` contains five chapter cards, and the saved chapter-5 response contains twenty manga images. Unit tests verify both live layouts, chapter ordering, URL resolution, lazy image attributes, and the twenty-page reader fixture.

## Supplied APK sources

The application contains nine supplied Arabic Mihon APK assets, including Team X. Seven are marked non-NSFW and two are marked NSFW. The two NSFW entries are hidden when **Disable NSFW content** is enabled. All bundled APKs are copied into the application-private extension area and passed through the executable loader; they are not only catalog rows. The final release APK contains all nine bundled extension APK assets.

## Validation completed in this environment

The following checks pass after the rebuild:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
./gradlew :app:compileDebugJavaWithJavac
./gradlew :app:assembleRelease
```

Static validation confirms that the new release contains the original Mihon API artifacts, the ZERAVRIX source manager, direct startup module, private-extension installation path, all nine bundled APK assets, the TeamX live series/chapter fixtures, and the API-float regression tests. APK package metadata, signing, version code, and source archive contents must be checked against the final attached files.

This sandbox has no connected Android device, emulator, or ADB session. Consequently, I cannot truthfully state that the complete Android sequence has been device-verified here. The required final device test is: install the release; open Explore; confirm the seven safe bundled sources are active; turn NSFW on and confirm the two NSFW sources appear; open each source; search and open a title; confirm details and chapters; add a title to the library; open Team X chapter 5; confirm reader pages; download a chapter; restart the app; confirm resume/history; and run an update refresh. If any source fails, collect `adb logcat -d -v time | grep -E 'MihonRuntime|AndroidRuntime|org.zeravrix.kotatsu'`.

The release is signed with the temporary inspection key used for this candidate. A permanent ZERAVRIX keystore is required for public distribution and future in-place updates.

## Follow-up integration patch

The follow-up patch keeps the existing app architecture and makes the executable Mihon sources visible throughout the normal source UX. The live Mihon registry now emits a revision on every refresh, including package install/replacement/removal and runtime preference changes, so Explore and the source catalog update even when the descriptor list is unchanged. The source catalog accepts generic runtime `MangaSource` values and displays loaded Mihon sources as directly openable entries instead of treating them only as extension-download cards. Installed extension package icons are returned through the normal favicon pipeline. Synchronous source reads use the shared manager refresh path, selected APK cache copies are deleted after import, and host platform context is initialized before the Mihon runtime.

The patch was statically reviewed and Gradle configuration succeeds. Kotlin compilation reached the app compiler and surfaced/fixed one scope error; subsequent compilation was limited by the sandbox daemon’s memory pressure before a final success result. Device validation remains required, especially for package broadcasts, extension loading, source search, details, reader pages, downloads, and app restart behavior.
