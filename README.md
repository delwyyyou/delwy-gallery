# Delwy Gallery

A lightweight, open source, offline-first Android gallery built with **Kotlin** and **Jetpack Compose**, with conversion to .webP and .webM

## Features

* **Smart WebP Compression:** Converts heavy images (> 300 KB) into compact **WebP** files while preserving original 1:1 resolution.
* **EXIF Metadata Stripping:** Automatically cleans EXIF metadata from smaller images (< 300 KB) without re-encoding or quality loss.
* **WebM Video Conversion:** Compresses videos into efficient **WebM** (VP9 + Opus) directly on your device.
* **System Trash Integration:** Moves original files safely to the native Android Trash (`MediaStore`) so they can be restored anytime.
* **Built-in Media Player & Albums:** Clean album navigation and native video playback with zero external dependencies.
* **Material You UI:** Modern interface following Material Design 3 guidelines.

## Tech Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose, Material Design 3
* **Storage:** Android MediaStore API (Scoped Storage)

## Installation

Grab the latest `.apk` from the [Releases](../../releases) page.

## Build from Source

```bash
git clone https://github.com/delwyyyou/delwy-gallery.git
cd delwy-gallery
./gradlew assembleDebug
```

The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

## License

🄯 2026 Delwy (`org.delwy.webpgallery`)

This project is free software licensed under the **GNU General Public License v3.0** (`GPL-3.0-only`). See the [LICENSE](LICENSE) file for details.
