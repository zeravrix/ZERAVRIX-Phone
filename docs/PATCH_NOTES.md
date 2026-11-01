# ZERAVRIX Mihon Explore Integration Patch

This patch keeps the existing Kotatsu architecture and completes the executable Mihon-source path rather than rebuilding the application from zero.

The nine bundled Mihon APKs and compatible APKs installed in the phone are discovered through the shared Mihon runtime manager. The manager scans at application startup and refreshes on package add, replace, remove, and runtime preference changes. The resulting sources are enabled by default when no prior source preference exists, emitted through a revision-aware flow, and merged into the normal Explore source list.

The source catalog now shows loaded Mihon sources as real openable source entries. Selecting one opens the ordinary remote-list screen, so the existing Kotatsu repository factory handles popularity/latest/search, details, chapters, reader pages, downloads, history, and library persistence. The source cards use the extension APK application icon through the normal favicon pipeline. Existing native parser sources and extension download cards remain supported.

The private extension import flow deletes temporary cache copies after validation. Host platform context is initialized before the Mihon runtime. A unit test covers restoration of a Mihon source from its stable persisted key.

## Validation

Gradle configuration succeeds with `./gradlew help`. A real Android SDK and JDK were installed in the inspection sandbox. Kotlin compilation reached the app compiler and exposed a scope error in the new flow; that error was fixed. Later full compilation attempts were terminated by the sandbox memory limit during the large 1,040-file Kotlin compilation, so a final successful APK build was not obtained here.

## Device test checklist

Install the debug or release APK on a test device and verify that the nine bundled sources appear on Explore without opening the extension manager. Confirm that their icons and Arabic names render, that tapping a source opens its remote list, and that search, details, chapter loading, reading, downloads, library add, history, and resume work. Install a compatible Mihon extension APK while the app is open and verify that it appears after the package broadcast refresh. Replace or remove an extension and verify that the Explore list updates. Toggle NSFW filtering and confirm the two NSFW bundled sources follow the preference. Finally, test the same sequence after force-stopping and relaunching the app.
