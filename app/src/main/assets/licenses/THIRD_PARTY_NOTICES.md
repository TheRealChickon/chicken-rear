# Third-party notices

Chicken Rear is a fork of **Magic Rear** (formerly AI Usage) by 896kb,
<https://gitlab.com/896kb/ai-usage-companion>, forked at release 1.2.1 (commit `a5f6d9a`). Upstream's code is under the MIT licence; its
copyright notice is kept in [LICENSE](LICENSE). What this fork changed is listed in the README
("Fork, licence and credits").

## Libraries in the APK

Selected libraries from `:app:releaseRuntimeClasspath`. All are Apache-2.0 except where noted.

| Library | Version | Licence |
|---|---|---|
| AndroidX (core, activity, lifecycle, and their dependencies) | core-ktx 1.16.0, activity 1.9.3, lifecycle 2.9.4 | Apache-2.0 |
| Jetpack Compose (ui, foundation, runtime, animation), BOM 2025.08.00 | 1.10.3 | Apache-2.0 |
| AndroidX CameraX (Camera2, lifecycle, view, video, extensions) | 1.4.2 | Apache-2.0 |
| MapLibre Native for Android, OpenGL | 13.7.1 | BSD-2-Clause, see below |
| Google Play services location | 21.3.0 | [Android SDK terms](https://developer.android.com/studio/terms) |
| Google ML Kit Text Recognition (bundled Latin, Chinese, Devanagari, Japanese and Korean models) | 16.0.1 | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |
| Google ML Kit Language Identification and Translation | 17.0.6 and 17.0.3 | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |
| Android Data Transport (API, runtime and CCT backend) | 2.2.1, 2.2.6 and 2.3.3 | Apache-2.0 |
| Firebase Components and Encoders (including JSON) | 16.1.0, 16.1.0 and 17.1.0 | Apache-2.0 |
| Android ODML Image | 1.0.0-beta1 | [Android SDK terms](https://developer.android.com/studio/terms) |
| AndroidX Media3 (transformer, effect, common and their dependencies) | 1.11.1 | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | 2.3.10, 1.9.0, 1.7.3 | Apache-2.0 |
| OkHttp, Okio (Square) | 4.12.0, 3.6.0 | Apache-2.0 |
| Guava (Google) | 33.3.1-android | Apache-2.0 |
| Backdrop and Shapes (Kyant0) | 1.0.6, 1.2.0 | Apache-2.0 |
| AndroidHiddenApiBypass (LSPosed) | 6.1 | Apache-2.0 |
| Shizuku API (RikkaApps): api, provider, aidl, shared | 13.1.5 | MIT, Copyright (c) 2021 RikkaW |
| Checker Framework qualifiers | 3.43.0 | MIT, Copyright 2004-present by the Checker Framework developers |

The MIT licence's text, which both MIT entries above are under with their own copyright lines, is
the one in [LICENSE](LICENSE). The Apache License 2.0 is in `assets/licenses/Apache-2.0-MaterialSymbols.txt`.

### Google ML Kit

The ML Kit libraries, their Google SDK dependencies and the bundled recognition models carry
their upstream terms. The ML Kit terms incorporate the
[Google APIs Terms of Service](https://developers.google.com/terms). On-device translations
are powered by Google Translate. See the
[ML Kit Translation usage and attribution guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms).

### MapLibre Native licence

BSD 2-Clause License

Copyright (c) 2021 MapLibre contributors

Copyright (c) 2018-2021 MapTiler.com

Copyright (c) 2014-2020 Mapbox

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

* Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in
  the documentation and/or other materials provided with the
  distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Code taken into the source

- **AndroidLiquidGlass / Backdrop catalog** by Kyant0, Apache-2.0: glass shaders, and the
  controls ported from its catalog app (`ui/glass`, `ui/liquid`, `rear/Glass.kt`). Each file
  says what it was ported from.
- **Paper Shaders** (paper-design/shaders), Apache-2.0: the liquid metal shader, by way of
  **metal-fx** by Jakub Antalik, MIT. Paper Shaders' NOTICE: "Powered by Paper Shaders:
  https://shaders.paper.design".
- **Vue Bits** by David Haz (after React Bits): the Silk, Color Bends and Electric Border
  shaders, under "MIT + Commons Clause": free to use as part of an application, not to be
  sold or redistributed as a component collection.
- **Snowfall** adapted from Lumine's multilayer snowfall on Godot Shaders, CC0.
- **Material Symbols / Material Icons** (Google), Apache-2.0: the glyphs in `res/drawable`
  and `rear/GlassIcons.kt`.

- **Material Color Utilities** (Google), Apache-2.0: cover colour quantization and HCT
  in `app/src/main/java/dev/kb896/aiusage/color/mcu`.

## Fonts

- **Departure Mono**, Copyright 2022–2024 Helena Zhang, SIL Open Font License 1.1
  (`app/src/main/assets/licenses/OFL-DepartureMono.txt`).
- **Outfit**, Copyright 2021 The Outfit Project Authors, SIL Open Font License 1.1
  (`app/src/main/assets/licenses/OFL-Outfit.txt`).

## Not covered by this repository's licence

- **The Claude mascot** is Anthropic's. The pixel figures here were rebuilt by upstream from
  <https://ayotomcs.me/claude-mascot>. "Claude" is a trademark of Anthropic.

If you own it and want it removed, open an issue.

## In the APK

These notices travel with the app: `assets/licenses/` in the APK holds this file, `LICENSE`, the
Apache License 2.0 and the two fonts' SIL Open Font License texts.

## Data

- Maps from [OpenFreeMap](https://openfreemap.org/) and place search by
  [Photon](https://photon.komoot.io/), using [OpenStreetMap](https://www.openstreetmap.org/copyright) data.
- Weather by [Open-Meteo](https://open-meteo.com/), CC BY 4.0. Lyrics from [LRCLIB](https://lrclib.net/), in code inherited from upstream that this
  fork's settings do not switch on.

## Not in this repository

- Nothing of Xiaomi's. Where the back uses Xiaomi's own pictures or models (call, timer and
  music glyphs, the timer's water, the pin animation), they are read at run time from the
  system app already on the phone (`com.xiaomi.subscreencenter`) and never copied.
- No video clips. The face on the back is a clip you add yourself.
