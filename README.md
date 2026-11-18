# Chicken Rear

[![Rear display demo showing a message arriving, music animation that follows the beat, navigation and the Dust face](.github/rear-promo.webp)](.github/rear-promo.mp4?raw=true)

[Watch the MP4 with sound](.github/rear-promo.mp4?raw=true). Music and cover credits are in [Third-party notices](THIRD_PARTY_NOTICES.md).

Chicken Rear is an Android app for the small rear display of the Xiaomi 17 Pro Max. It puts its own
pages on that display, including faces, music, calls, timers, charging, notifications, pinned notes,
navigation and Claude or ChatGPT usage. Xiaomi's own rear screen keeps
running underneath and is covered, never replaced or removed.

It is a fork of [Magic Rear](https://gitlab.com/896kb/ai-usage-companion) by 896kb (MIT), started
from its release 1.2.1. Not affiliated with Xiaomi.

## Contents

- [Set up Shizuku](#set-up-shizuku)
- [Features](#features)
- [What it needs](#what-it-needs)
- [Install](#install)
- [Updating Chicken Rear](#updating-chicken-rear)
- [Circle to Search](#circle-to-search)
- [After a reboot](#after-a-reboot)
- [Gestures on the back](#gestures-on-the-back)
- [What is not included](#what-is-not-included)
- [Known limitations](#known-limitations)
- [Privacy](#privacy)
- [Building from source](#building-from-source)
- [Fork, licence and credits](#fork-licence-and-credits)

## Set up Shizuku

Shizuku lets Chicken Rear start the Android shell process that draws on the rear display.
Chicken Rear recommends the **thedjchi fork of Shizuku**. It can start at boot once Wi-Fi connects, and its Watchdog can restart Shizuku if it stops unexpectedly.

1. Download and install the latest **stable APK** from the [Shizuku fork’s latest stable release](https://github.com/thedjchi/Shizuku/releases/latest). If you already use the original Shizuku, remove its wireless debugging pairing and uninstall it before installing the fork. [Fork installation guide](https://github.com/thedjchi/Shizuku/wiki/Setup#installing)
2. Connect to Wi-Fi. On HyperOS, open **Settings → About phone** and tap **OS version** seven times. Under **Settings → Additional settings → Developer options**, enable **USB debugging**, **Wireless debugging**, and **USB debugging (Security settings)** if listed. Open Shizuku and tap **Pair**. In **Wireless debugging**, choose **Pair device with pairing code** and enter the code in Shizuku’s pairing notification. [Pairing guide](https://github.com/thedjchi/Shizuku/wiki/Setup#pairing)
3. Return to Shizuku and tap **Start** under **Wireless debugging**. Leave **USB debugging** on while Shizuku is running. Open Chicken Rear and approve its Shizuku request. [Wireless debugging and app authorization](https://github.com/thedjchi/Shizuku/wiki/Setup#wireless-debugging)
4. In **Shizuku → Settings → Behavior**, turn on **Start on boot**. If Shizuku asks, tap **Fix** and allow it to run without battery optimization. On an unrooted phone it waits for Wi-Fi after each reboot and may take a few minutes. Enable **Watchdog** there if you also want recovery after an unexpected stop. Enable **TCP mode** if you need to restart Shizuku after leaving Wi-Fi. The first start after each reboot still needs Wi-Fi. [Fork feature guide](https://github.com/thedjchi/Shizuku/wiki/Features)

## Features

Every page is drawn for the 976x596 rear panel, whose left 296 px sit under the camera lenses, so
text is centred in the part you can see.

| | What it does |
|---|---|
| **Face** | Pick an installed Xiaomi rear theme, the built-in Dust in Light face, or a looping clip. Hold the rear face to open the picker. Optional small clock (off by default). |
| **Music** | While anything plays: the cover, title, artist and previous / play-pause / next, on a ground in the cover's colours with slow glows. A wide layout for video thumbnails. Optionally the glows rise with the sound ("Follows the music"). |
| **Calls** | Ringing: decline and answer. In the call: speaker and end. Works with the phone's own dialer; other apps' calls use their notification buttons. |
| **Timers** | The Xiaomi Clock's timer with the time left, water that sinks as it runs, pause / continue, and an X that cancels it. |
| **Charging** | The battery level as a fluid that fills the panel, coloured by level, with the percent. Each show reads the current battery level. Swipe it away to slide it right, and swipe right to left from the face to bring it back while plugged in. At 100% it shows briefly. |
| **Notifications** | Choose a full-page message or a message island. **Island · Tint** colours the live face behind the message; **Island · Sweep** dims the face and sweeps a band of light behind the text. Messages can show the app’s icon, sender and text. Pull down from the right half of the top edge for the waiting ones as a pile of pages; swipe a page sideways to dismiss or snooze it on the phone. |
| **Pinned notes** | "Pin to back" from any text selection or share sheet. Swipe up on the face for the notes, sideways between them, the bin removes one. Pin text or an image, reorder notes, or tie a note to a place so it appears when you arrive and leaves the rear display when you leave. The pin animation is Xiaomi's own or the app's. |
| **AI usage** | Claude and ChatGPT Codex usage limits have been tested with the real services. Weekly limits show by default, with optional five-hour limits. Claude has its animated mascot, and ChatGPT uses the installed app’s icon or its optional Dots animation. |
| **Always-on rules** | The back is lit while the phone is in use or lies face down, and off in a pocket, lying on its back, or after you switch it off by hand. At night (22:00 to 07:00 or Do Not Disturb) with the front off it goes dark after two minutes so the phone can sleep. Optional home detection by Wi-Fi. |
| **Brightness** | Follows the room's light through the rear light sensor, dimmer than Xiaomi's own floor in the dark, with one slider for the overall level. A double tap dims, a tap brightens. The back brightens and the front dims only once the phone rests face down. Lifting it ends that mode. |
| **Apps and camera** | Chosen apps can be opened on the back. A swipe opens the rear camera. In **Settings → Camera**, enable **Our camera on the back** and grant camera access for a rear viewfinder, photo and video, and Portrait where supported. |
| **Navigation** | While a Google Maps route is active, Chicken Rear reads its navigation notification to show the next turn, distance and route progress. Other apps with compatible navigation notifications can use the same page. |
| **Circle to Search** | Hold the front navigation bar, then select text or circle part of the screen to search. The overlay can also translate text in place. Off by default. |
| **Settings** | One page on the front screen: a live preview of the back drawn by the same code, a row of pages under it, and only the settings of the selected page. Follows the phone's light and dark mode. |

## What it needs

- **A Xiaomi phone with a rear display.** The layout is designed around the 976×596 rear panel
  of the Xiaomi 17 Pro Max.
- **Shizuku running**, with Chicken Rear allowed. See [Set up Shizuku](#set-up-shizuku).
- **Xiaomi's rear-screen app** (`com.xiaomi.subscreencenter`, part of the ROM) installed and
  running, as it is out of the box. Chicken Rear covers it. The call, timer and music pages, the
  timer's water and Xiaomi's pin animation use pictures that are read from that app on your phone.
- Works alongside Xiaomi's **Phone**, **Clock** and **Camera** apps: the call and timer pages send
  them the same commands Xiaomi's own rear pages do.
- Optional: your own **video clip** for a custom face and a Claude or ChatGPT account for the usage page.

## Install

1. **Set up Shizuku** using the [steps above](#set-up-shizuku).
2. **Install Chicken Rear.** Download `chicken-rear-<version>.apk` from [Releases](../../releases)
   and open it. Allow your browser or file manager to install apps when prompted.
3. **Open Chicken Rear** and review the privacy notice. The four-step setup guide covers Shizuku,
   notification access, optional permissions for place notes, the rear camera and the earbud animation,
   and HyperOS battery settings. You can skip it or open it again under **Advanced → Setup guide**.
   Fresh installs start with **Dust in Light** and the face clock off. Saved settings carry over on update.
4. **Set up the features you use.** Choose a page under the rear preview to see its settings.
   1. *Message, Notifications*: the switch is on. If the strip says "Notifications need access",
      press "Fix ›", then "Continue". With Shizuku running it is granted at once; otherwise allow
      Chicken Rear on the system page that opens. Notifications, music, calls and timers all come
      through this access.
   2. *Idle*, then the "Advanced" row at the bottom, "Keep it running": set "Battery: no
      restrictions", turn "Autostart" on, and lock Chicken Rear in the recent apps (long-press its
      card, then the padlock). Without them HyperOS freezes or clears the app in the background and
      the back stops updating.
   3. *Usage*: choose Claude or ChatGPT and use the sign-in row. Approve the request in
      your browser, then return to Chicken Rear. The usage page is then one swipe away on the back.
   4. Optional permissions for the features you use: location (approximate for the
      lightning effect; precise and "Allow all the time" for notes tied to a place), the
      microphone permission ("Follows the music"; nothing is recorded), and the accessibility
      service "Pin to back: glass" (lets the pin animation play above other overlays). The glass
      service is shared by *Notes*, "Pin animation: Ours", and [Circle to Search](#circle-to-search).
      If its switch is greyed out in
      Accessibility, open Chicken Rear's App info, the menu at the top right, "Allow restricted
      settings", then switch it on.
5. **Choose a face.** Use the face picker for a built-in or installed face. To use your own clip,
   prepare it as described below and copy it with a computer:
   1. Install Google's [platform-tools](https://developer.android.com/tools/releases/platform-tools)
      and connect the phone by USB. Accept the "Allow USB debugging" question on the phone.
   2. Run:
      ```
      adb push my-clip.mp4 /sdcard/Android/data/dev.chicken.rear/files/clips/david-moon.loop.mp4
      ```
      `david-moon.loop.mp4` is the name the app looks for by default (the glitch and lightning
      effects under Animations were made for the development clip of that name; switch them off if
      they do not suit yours). The folder exists once the app's settings have been opened.
   3. The clip is the face the next time the back lights up.

A clip-based face stays black until its file is available. You can switch to another face in the picker.

### Preparing a clip

A clip is an MP4 (H.264) of exactly 976x596, without sound, a few seconds to half a minute long,
that looks right when it repeats. Black areas are switched-off pixels on the OLED, so dark footage
looks best. With ffmpeg:

```
ffmpeg -i source.mp4 -ss 0 -t 12 -an \
  -vf "scale=976:596:force_original_aspect_ratio=increase,crop=976:596" \
  -c:v libx264 -pix_fmt yuv420p -crf 18 my-clip.mp4
```

Files named `*.loop.mp4` in the clips folder are faces. With two or more, a "Face" row appears
under Animations, and a press and hold on the back goes to the next one. A file named
`*.wake.mp4` can be chosen to play once as the back wakes. "Add a clip" in the app cuts a video
from the phone to the panel's shape, but saves it without `.loop` in its name, so it is not offered
as a face; use the `adb push` above.

## Updating Chicken Rear

Release builds check this project's public [GitHub releases](../../releases) at most once a day
automatically. You can also use **Advanced → Check for updates**. Choose **Update** to download the
APK and its published `SHA256SUMS`. Chicken Rear checks the SHA-256, package, version and signing
certificate against the expected release and the installed app before opening Android’s installer.
Android asks you to approve the installation. It may first ask you to allow installs from Chicken Rear.

## Circle to Search

Go to **Settings → Idle → Circle to Search**. Enable **Pin to back: glass** in Android's
Accessibility settings. The same service handles the glass pin animation and screen capture for
search. The navigation-bar trigger runs in the Shizuku shell and requires the HyperOS launcher.
The option stays off while the standalone Circle Search app is installed.

Hold the front navigation bar to select text for Google Search or circle an image for Google Lens.
You can also copy or pin text. In-place translation works by paragraph and follows the text as you
scroll, using the original layout, text size, colour and emphasis where possible. Translation uses
[Google Translate through ML Kit](https://developers.google.com/ml-kit/language/translation).

PaddleOCR can read English and other Latin-script languages, Chinese, Japanese, Korean, Cyrillic
(including East Slavic), Arabic script, Devanagari, Greek, Thai, Tamil, Telugu and Kannada. Translation
is available for the languages supported by ML Kit.

- **Only my languages** downloads the packs you use. The shared Fast text detector is about **4.8 MB**.
  Each script pack is about **7.8–18.2 MB**, and each needed offline translation model is about
  **30 MB**. The app shows the size before you approve an OCR download.
- **Everything** downloads all 14 Fast OCR files and 58 translation models over Wi-Fi, about
  **1.87 GB** in total, including **133 MB** for OCR. The app checks storage before starting.
- **Model → Fast** is the default. **Accurate** uses a larger text detector and a larger Chinese
  and Japanese recognizer. It requires a separate download of about **173 MB**.

OCR packs come from PaddlePaddle’s official [Hugging Face repositories](https://huggingface.co/PaddlePaddle)
and [PaddleOCR model distribution](https://github.com/PaddlePaddle/PaddleOCR). The app verifies pinned
SHA-256 hashes and can resume interrupted transfers. Translation packs come from Google. You can
remove downloaded packs in Settings. Both recognition and translation run on the phone after
download. Google receives text you choose to search or the image crop you send to Lens. See
[Privacy](#privacy) for details.

## After a reboot

With **Start on boot** enabled, the recommended Shizuku fork starts after Wi-Fi connects. If it
doesn't start, check Wireless debugging and tap **Start** in Shizuku. Pair again if prompted and
open Chicken Rear.

## Gestures on the back

On the face:

| Gesture | Does |
|---|---|
| Tap | Full brightness for 15 seconds |
| Double tap | Dim; again, bright |
| Double tap, holding the second | Back off until you wake it |
| Double tap a dark back | Lights it again (Xiaomi's own wake gesture) |
| Swipe right to left | The live page (call, navigation, timer, music or charging) if there is one, then AI usage |
| Swipe left to right | Rear camera |
| Swipe up | Pinned notes |
| Pull down from the right half of the top edge | Notifications |
| Press and hold | Open the face picker |

On a page, the opposite swipe goes back the way it came. A live page you swipe away stays away
until something new happens; it is one swipe right of the face meanwhile.

## What is not included

- **Video clips for faces.** The face used during development is cut from copyrighted footage, which is not
  in this repository or in the APK. Bring your own (format and folder above).
- **Anything of Xiaomi's.** No picture, model, shader or animation file from Xiaomi's apps is
  bundled. Where a page uses Xiaomi's own artwork, it is read at run time from the system app that
  is already on your phone, and drawn plainly when that is not found.
- **Signing keys**, and any personal data.

## Known limitations

- After a reboot the rear display needs Shizuku to be running. On an unrooted phone, start on boot waits for Wi-Fi.
- HyperOS freezes background apps when a heavy app such as the Camera runs. With battery
  restrictions off and Autostart on the app is thawed again; while frozen, the back keeps its cover
  but does not update.
- A lit rear display keeps the whole phone awake. The night rule exists for that reason; leave
  "Sleep at night" on unless you want the battery cost.
- Do not install the release build (`dev.chicken.rear`) and a debug build
  (`dev.chicken.rear.dev`) at the same time: each would draw on the back.
- The call page has been used on real calls with the phone's own dialer only.
- The usage pages sign in with the public OAuth clients of Claude Code and of OpenAI's Codex CLI.
  Using a token outside the official client may be against the provider's terms; that risk is
  yours. Billing figures are not shown because the usage response has none.
- The app interface is in English. Circle to Search reads and translates supported languages.

## Privacy

Chicken Rear has no account, server or analytics of its own. What leaves the phone:

- **Claude usage**: requests to Anthropic with your own account: the sign-in in your browser at
  `claude.ai`, the token exchange at `platform.claude.com`, usage and profile from
  `api.anthropic.com`. The tokens are kept on the phone, encrypted with an Android Keystore key,
  and sent nowhere else.
- **ChatGPT (optional)**: sign-in and token exchange at `auth.openai.com` and a usage request to
  `chatgpt.com`, with your own account, kept the same way.
- **Place search and maps (optional)**: Photon receives typed search text and, when available,
  the map centre rounded to a tenth of a degree. OpenFreeMap serves the map tiles.
- **Circle to Search (optional)**: text queries go to Google Search and selected image crops go to
  Google Lens. Voice and song search open the Google app. Text recognition and translation process
  screen content on the phone. OCR model downloads contact PaddlePaddle’s official Hugging Face or
  Baidu Object Storage hosts. Translation model downloads contact Google, which may collect ML Kit
  diagnostics. [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms)
- **Updates**: release builds request public release metadata from GitHub. If you ask to update,
  the app downloads the published checksum file and APK from GitHub’s release hosts.
- **Weather (optional)**: with "Lightning when it storms" on, the phone's approximate position,
  rounded to two decimals (about a kilometre), to [Open-Meteo](https://open-meteo.com/) every five
  minutes while the back is lit.

The code inherited from upstream can also fetch lyrics from [LRCLIB](https://lrclib.net/) and look
up a typed place name with Open-Meteo's geocoder (`geocoding-api.open-meteo.com`); this fork's
settings have no switch or field for either, and neither is used.

What stays on the phone: notification contents (shown on the back with their message by default,
where people facing you can read them; "Show the message" turns that off; not stored beyond the
list), pinned notes, the places of notes (for which the app asks for the precise position, all the
time, and gives it to Android's own proximity alert), the last rough position for the weather (to a
tenth of a degree), the home Wi-Fi's identity (its gateway and name servers, not its name), and the
one screenshot the optional "glass" service takes as you pin, which is held in memory for the two
seconds of the animation and never saved. Circle to Search holds the captured screen in memory
while the overlay is open. An image crop selected for Lens is written to the app's cache, where the
next image search clears previous crops. "Follows the music" measures the level of the sound being
played; nothing is recorded.

The app shows the same in its privacy note on first start, and its "Privacy policy" row (under
Advanced) links to this section.

## Building from source

```
git clone <this repository>
cd chicken-rear
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

JDK 17 and the Android SDK (platform 36) are needed; point `ANDROID_HOME` at the SDK or write
`sdk.dir=...` into `local.properties`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`
(application id `dev.chicken.rear.dev`).

A release build (`./gradlew :app:assembleRelease`, application id `dev.chicken.rear`) is signed with
your own key: either set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`, or put
a `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the repository
root or where `KEYSTORE_PROPERTIES` points. Both are ignored by git. Without a key the build
produces an unsigned APK that does not install.

`build-install.sh` builds the debug APK and installs it over adb; it is a developer tool, inherited
from upstream.

See [CONTRIBUTING.md](CONTRIBUTING.md) for branches and releases and [CHANGELOG.md](CHANGELOG.md)
for what changed.

## Fork, licence and credits

Chicken Rear is a fork of [Magic Rear](https://gitlab.com/896kb/ai-usage-companion) (formerly
"AI Usage") by 896kb, taken at its release 1.2.1. Upstream's history is kept in this repository's
history. Upstream replaces Xiaomi's rear screen with a dashboard of its own for the 17 Pro and
later phones. What this fork changes:

- It runs **alongside** Xiaomi's rear screen ("companion mode") and covers it; the mode that
  replaces Xiaomi's app is gone, and so are the old tabbed settings.
- The idle face is a video clip instead of the dashboard.
- Full-screen pages for music, calls, timers, charging, notifications and pinned notes, in the
  style of Xiaomi's own rear pages, with one navigation model for all of them.
- The always-on rules, the night rule, home detection and brightness that follows the light.
- A new settings page with a live preview.

The code is under the MIT licence, see [LICENSE](LICENSE), with the exceptions listed there and in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (third-party code, fonts and artwork). The APK
carries both files and the fonts' licence texts in `assets/licenses/`. Upstream's Chinese README
was removed because it describes upstream's app, not this fork.

"Claude" and the Claude mascot belong to Anthropic; "ChatGPT" and "Codex" to OpenAI; "Xiaomi" and
"HyperOS" to Xiaomi. No warranty.
