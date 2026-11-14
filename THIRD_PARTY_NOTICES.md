# Third-party notices

Chicken Rear is a fork of **Magic Rear** (formerly AI Usage) by 896kb,
<https://gitlab.com/896kb/ai-usage-companion>, forked at release 1.2.1 (commit `a5f6d9a`). Upstream's code is under the MIT licence; its
copyright notice is kept in [LICENSE](LICENSE). What this fork changed is listed in the README
("Fork, licence and credits").

## Libraries in the APK

Resolved from `:app:releaseRuntimeClasspath` (157 artifacts). All are Apache-2.0 except where noted.

| Library | Version | Licence |
|---|---|---|
| AndroidX (core, appcompat, activity, lifecycle, and their dependencies) | core-ktx 1.16.0, appcompat 1.7.0, activity 1.9.3, lifecycle 2.9.4 | Apache-2.0 |
| Jetpack Compose (ui, foundation, runtime, animation), BOM 2025.08.00 | 1.10.3 | Apache-2.0 |
| AndroidX Media3 (transformer, effect, common and their dependencies) | 1.11.1 | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | 2.3.10, 1.9.0, 1.7.3 | Apache-2.0 |
| OkHttp, Okio (Square) | 4.12.0, 3.6.0 | Apache-2.0 |
| Guava (Google) | 33.3.1-android | Apache-2.0 |
| Backdrop and Shapes (Kyant0) | 1.0.6, 1.2.0 | Apache-2.0 |
| AndroidHiddenApiBypass (LSPosed) | 6.1 | Apache-2.0 |
| Shizuku API (RikkaApps): api, provider, aidl, shared | 13.1.5 | MIT, Copyright (c) 2021 RikkaW |
| Checker Framework qualifiers | 3.43.0 | MIT |

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

## Fonts

- **Departure Mono**, Copyright 2022-2024 Helena Zhang, SIL Open Font License 1.1.
- **Outfit**, Copyright 2021 The Outfit Project Authors, SIL Open Font License 1.1.

## Not covered by this repository's licence

- **The Claude mascot** is Anthropic's. The pixel figures here were rebuilt by upstream from
  <https://ayotomcs.me/claude-mascot>. "Claude" is a trademark of Anthropic.
- The **ACRNM** maker's mark (`res/drawable/acrnm_ray.xml`) is ACRNM's.
- The photograph of a record (`res/drawable-nodpi/vinyl_record.webp`) came with upstream; its
  source is not known to this fork.

If you own one of these and want it removed, open an issue.

## Data

- Weather by [Open-Meteo](https://open-meteo.com/), CC BY 4.0. Lyrics from [LRCLIB](https://lrclib.net/).

## Not in this repository

- Nothing of Xiaomi's. Where the back uses Xiaomi's own pictures or models (call, timer and
  music glyphs, the timer's water, the pin animation), they are read at run time from the
  system app already on the phone (`com.xiaomi.subscreencenter`) and never copied.
- No video clips. The face on the back is a clip you add yourself.
