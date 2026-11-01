<div align="center">

<img src="assets/logo.png" alt="ZERAVRIX Logo" width="220" />

# ⚡ ZERAVRIX Phone 2.0

**The Ultimate Next-Generation Manga, Manhwa & Comic Reader for Android**

<br/>

[![Official Website](https://img.shields.io/badge/Official%20Website-zeravrix.pages.dev-00D2FF.svg?style=for-the-badge&logo=google-chrome&logoColor=white)](https://zeravrix.pages.dev/)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)](https://github.com/zeravrix/ZERAVRIX-Phone/releases)
[![Language](https://img.shields.io/badge/Language-Kotlin%20100%25-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?style=for-the-badge)](LICENSE)
[![Latest Release](https://img.shields.io/badge/Release-v2.0.0-00D2FF.svg?style=for-the-badge&logo=github)](https://github.com/zeravrix/ZERAVRIX-Phone/releases/latest)

<br/>

🌐 **Official Portal**: [**https://zeravrix.pages.dev/**](https://zeravrix.pages.dev/)

**ZERAVRIX Phone 2.0** combines the raw performance of native Kotlin parsers with the boundless versatility of the **Tachiyomi / Mihon APK Extension** ecosystem. Built on **Material Design 3**, powered by **OkHttp 5 + Brotli**, and featuring an **Auto-Trust Sandbox & Package Installer Integration**, ZERAVRIX Phone offers the smoothest reading experience with zero telemetry and 100% privacy.

<br/>

[📥 **Download Latest Release APK**](https://github.com/zeravrix/ZERAVRIX-Phone/releases/latest) • [🌐 Website](https://zeravrix.pages.dev/) • [✨ Features](#-key-features) • [🏗️ Architecture](#️-architecture) • [🛠️ Building](#-building-from-source) • [📜 Changelog](#-changelog--release-notes)

</div>

---

## ✨ Key Features

### 🔌 Universal Extension Engine & Package Installer
* **Full Mihon & Tachiyomi Compatibility**: Execute thousands of community extension APKs inside a secure sandbox or via the Android Package Installer.
* **Auto-Trust & Explore Pinning**: All installed extension sources are automatically recognized, enabled, and pinned to your Explore tab by default.
* **Native Install Dialog Support**: Prompts the standard Android system installer dialog so you can easily review and approve source APK installations.
* **Smart Catalog & Live Spinner**: Browse sources with real-time download progress indicators that dynamically transition to a `+` pin button when installation succeeds.
* **Custom Repositories**: Easily add, synchronize, and remove custom extension repository JSON URLs with live validation.

### 📖 Immersive Reader & Offline Engine
* **Adaptive Viewing Modes**: Webtoon mode (zero-gap continuous vertical scrolling), Left-to-Right, Right-to-Left, and dual-page landscape mode.
* **Split Image & Crop Borders**: Automatically splits double-page spreads on mobile portrait screens and trims unnecessary whitespace.
* **Resilient Offline Downloads**: Multi-threaded background downloader supporting pause/resume, chapter reordering, and external SD card / SAF directory selection.
* **Hardware-Accelerated Rendering**: Powered by Coil 3 and Subsampling Scale ImageView (SSIV) for crisp rendering of 4K+ manhwa panels without out-of-memory errors.

### 🚀 Accelerated Network Pipeline
* **Brotli Decompression**: Full native Brotli interceptor (`okhttp3.brotli`) active across all sources for up to 30% faster chapter loading and reduced data usage.
* **Cloudflare & Anti-Bot Compatibility**: Custom User-Agent rotation, configurable HTTP headers, and built-in interactive Cloudflare solver browser.
* **Rate Limiting & Queue Gate**: Source-specific request throttling protects your IP against bans and CDN rate limits.

### 🛡️ Privacy, Control & Design
* **Zero Telemetry**: No third-party analytics, no tracking, no ads. All library data, reading history, and bookmarks stay securely on your device.
* **Content Filter & NSFW Toggle**: Complete control over adult content across catalog search, extensions, and explore tabs.
* **Material You (Monet) Theming**: Fully dynamic theming conforming to Android 12+ system colors, plus pure AMOLED Black for maximum battery savings.

---

## 🏗️ Architecture

```mermaid
graph TD
    UI[App UI Layer<br/>Explore • Catalog • Reader • Details] --> Router[AppRouter Navigation]
    Router --> Repositories[MangaRepository Factory]
    
    Repositories --> NativeRepo[Native Kotlin Parsers]
    Repositories --> MihonRepo[MihonMangaRepository]
    
    MihonRepo --> SourceManager[MihonSourceManager]
    SourceManager --> Sandbox[DelegateLastClassLoader Sandbox]
    Sandbox --> ExtAPK[Tachiyomi / Mihon Source APKs]
    
    ExtAPK --> Injekt[Injekt DI & Bridge Layer]
    Injekt --> NetworkHelper[NetworkHelper & Headers]
    NetworkHelper --> OkHttp[OkHttp 5 + Brotli Engine]
    OkHttp --> Internet[(External Manga CDNs & Web)]
```

---

## 📱 Tech Stack & Technical Specifications

| Domain | Technology / Library | Description |
|---|---|---|
| **Official Website** | [zeravrix.pages.dev](https://zeravrix.pages.dev/) | Official portal & web home for ZERAVRIX ecosystem |
| **Core Architecture** | MVVM + Clean Architecture | Unidirectional data flow with state persistence |
| **Language** | Kotlin 2.0+ & Coroutines | 100% Kotlin codebase with Flow & StateFlow reactive state |
| **Dependency Injection** | Hilt / Dagger 2 | Scoped dependencies across Activities, ViewModels, and Workers |
| **Local Database** | Room (SQLite) + KSP | High-speed relational caching with type-safe migrations |
| **Networking** | OkHttp 5 + Brotli + Conscrypt | Modern HTTP/2 & HTTP/3 engine with Brotli decompression |
| **Image Pipeline** | Coil 3 + SSIV | Memory-optimized bitmap caching & deep-zoom tiling |
| **Extension Sandbox** | Dalvik `DelegateLastClassLoader` | Dynamic runtime isolation for Tachiyomi / Mihon APKs |
| **UI Components** | Google Material Design 3 | Material You dynamic color, Edge-to-Edge window insets |

---

## 🚀 Getting Started

### Installation
1. Visit the [**Releases Page**](https://github.com/zeravrix/ZERAVRIX-Phone/releases/latest) or our [**Official Website**](https://zeravrix.pages.dev/).
2. Download `ZERAVRIX_Phone_2.0_release.apk`.
3. Open the downloaded file on your Android device and tap **Install**.
4. *(If prompted, allow "Install from Unknown Sources" in your device settings).*

---

## 🛠️ Building from Source

### Prerequisites
* **Android Studio**: Ladybug / Meerkat or newer
* **Java Development Kit (JDK)**: JDK 17 or JDK 21 (configured as `JAVA_HOME`)
* **Android SDK**: Build-Tools `35.0.0` or higher, SDK Platform `android-35`

### Build Steps

1. **Clone the repository**:
   ```bash
   git clone https://github.com/zeravrix/ZERAVRIX-Phone.git
   cd ZERAVRIX-Phone
   ```

2. **Verify Gradle Wrapper**:
   * **Windows (PowerShell)**:
     ```powershell
     .\gradlew.bat --version
     ```
   * **Linux / macOS**:
     ```bash
     chmod +x gradlew
     ./gradlew --version
     ```

3. **Assemble Debug Build**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Assemble Release Build**:
   ```bash
   ./gradlew assembleRelease
   ```
   *The optimized, minified APK will be generated at `app/build/outputs/apk/release/app-release.apk`.*

---

## 📜 Changelog — Release v2.0.0

* 📲 **Package Installer Integration**: Prompts the standard Android system installer dialog on APK downloads for seamless user approval.
* 📌 **Automatic Source Discovery & Pinning**: Automatically scans all installed extension packages on the device and pins them in Explore by default.
* ⚡ **Full Mihon Extension Engine Integration**: Browse, install, and execute community source APKs directly.
* 🔄 **Dynamic Download Progress**: Circular indeterminate spinner appears while downloading extensions, transitioning to a `+` pin button on install.
* 🚀 **Unstripped Brotli Pipeline**: Configured strict keep rules preventing R8 from stripping OkHttp / Brotli internals (`NoClassDefFoundError: Lokhttp3/brotli/Brotli;` resolved).
* 🏷️ **Clean Chapter Titles**: Eliminated redundant leading `#` characters in chapter lists.
* 🎨 **Real ZK Logo Brand Identity**: Preserved the genuine blue ZK logo on pure AMOLED black splash screens and across GitHub documentation.
* 🧹 **Theme & Locale Modernization**: Removed deprecated Spider-Verse theme and legacy locale files for a cleaner, leaner binary.
* 🛡️ **NSFW Filtration**: Instant toggling of 18+ sources across catalog and search results.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0** (GPL-3.0). See the [LICENSE](LICENSE) file for details.

---

<div align="center">
  <sub>Official Website: <a href="https://zeravrix.pages.dev/"><b>zeravrix.pages.dev</b></a></sub><br/>
  <sub>Developed with ❤️ for the global manga & manhwa reading community.</sub><br/>
  <b><a href="https://github.com/zeravrix/ZERAVRIX-Phone">github.com/zeravrix/ZERAVRIX-Phone</a></b>
</div>
