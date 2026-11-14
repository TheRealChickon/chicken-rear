# Chicken Rear

Chicken Rear is an Android app for the small rear display of the Xiaomi 17 Pro Max. It puts its own
pages on that display: a looping video as the idle face, and full-screen pages for music, calls,
timers, charging, notifications, pinned notes and Claude usage. Xiaomi's own rear screen keeps
running underneath and is covered, never replaced or removed.

It is a fork of [Magic Rear](https://gitlab.com/896kb/ai-usage-companion) by 896kb (MIT), started
from its release 1.2.1. Not affiliated with Xiaomi, Anthropic, OpenAI or the upstream project.

**Status:** a personal project, built for and run on one phone model. See
[Known limits](#known-limits) for what has not been tried.

## Contents

- [Features](#features)
- [What it needs](#what-it-needs)
- [Install](#install)
- [After a reboot](#after-a-reboot)
- [Gestures on the back](#gestures-on-the-back)
- [What is not included](#what-is-not-included)
- [Known limits](#known-limits)
- [Privacy](#privacy)
- [Building from source](#building-from-source)
- [Fork, licence and credits](#fork-licence-and-credits)

## Features

Every page is drawn for the 976x596 rear panel, whose left 296 px sit under the camera lenses, so
text is centred in the part you can see.

| | What it does |
|---|---|
| **Face** | One video clip of yours loops as the idle face over the whole panel. Black in the clip is a switched-off pixel. Optional small clock (off by default), and a glitch and lightning effect made for the default face. |
| **Music** | While anything plays: the cover, title, artist and previous / play-pause / next, on a ground in the cover's colours with slow glows. A wide layout for video thumbnails. Optionally the glows rise with the sound ("Follows the music"). |
| **Calls** | Ringing: decline and answer. In the call: speaker and end. Works with the phone's own dialer; other apps' calls use their notification buttons. |
| **Timers** | The Xiaomi Clock's timer with the time left, water that sinks as it runs, pause / continue, and an X that cancels it. |
| **Charging** | The battery level as a fluid that fills the panel, coloured by level, with the percent. Stays while the charger is in; at 100% it shows one short moment and leaves. |
| **Notifications** | A new message takes the whole back for five seconds: the app's real icon, the sender and the text, in the app's colour. Pull down from the right half of the top edge for the waiting ones as a pile of pages; throw a page sideways to dismiss it on the phone too. |
| **Pinned notes** | "Pin to back" from any text selection or share sheet. Swipe up on the face for the notes, sideways between them, the bin removes one. A note can be tied to a place and come back when you arrive there. The pin animation is Xiaomi's own or the app's. |
| **Claude usage** | The five-hour and weekly limits of up to two accounts as vessels that fill, each with its reset time and an animated mascot. ChatGPT's Codex limits can be added as an account. |
| **Always-on rules** | The back is lit while the phone is in use or lies face down, and off in a pocket, lying on its back, or after you switch it off by hand. At night (22:00 to 07:00 or Do Not Disturb) with the front off it goes dark after two minutes so the phone can sleep. Optional home detection by Wi-Fi. |
| **Brightness** | Follows the room's light through the rear light sensor, dimmer than Xiaomi's own floor in the dark, with one slider for the overall level. A double tap dims, a tap brightens. Lying face down, the back is bright and the front is dimmed. |
| **Apps and camera** | Chosen apps can be opened on the back. A swipe opens Xiaomi's Camera there for selfies with the main cameras. |
| **Settings** | One page on the front screen: a live preview of the back drawn by the same code, a row of pages under it, and only the settings of the selected page. Follows the phone's light and dark mode. |

## What it needs

- **Xiaomi 17 Pro Max**, model `2509FPN0BC`, China ROM, HyperOS 4 (Android 17). This is the only
  phone the fork has run on. The pages are laid out for this panel and are untested elsewhere.
- **[Shizuku](https://shizuku.rikka.app/)**, running, with Chicken Rear allowed in it. Everything
  on the back is drawn by a shell process that Shizuku starts; without it nothing of the app shows.
  The development phone runs a fork of Shizuku; the original RikkaApps build has not been tried on
  HyperOS 4.
- **Xiaomi's rear-screen app** (`com.xiaomi.subscreencenter`, part of the ROM) installed and
  running, as it is out of the box. Chicken Rear covers it. The call, timer and music pages, the
  timer's water and Xiaomi's pin animation use pictures that are read from that app on your phone.
- Works alongside Xiaomi's **Phone**, **Clock** and **Camera** apps: the call and timer pages send
  them the same commands Xiaomi's own rear pages do.
- A **video clip** for the face (see [What is not included](#what-is-not-included)).
- Optional: a Claude account for the usage page.

## Install

No computer is needed for steps 1 to 7. Step 8 (the face clip) needs one.

The HyperOS menu names below are approximate: on your HyperOS version or language a menu may be
worded or placed a little differently. Chicken Rear's own buttons and rows are quoted as the app shows them.

1. **Turn on developer options.** Settings, About phone: tap "OS version" seven times until it
   says you are a developer.
2. **Turn on debugging.** Settings, Additional settings, Developer options: switch on
   "USB debugging", "USB debugging (Security settings)" and "Wireless debugging". The phone must be
   on Wi-Fi for wireless debugging. HyperOS asks you to confirm each of these, and the security
   setting asks for a Mi account.
3. **Install Shizuku** from its [GitHub releases page](https://github.com/RikkaApps/Shizuku/releases)
   (a China ROM has no Google Play), and open it.
4. **Start Shizuku.** In Shizuku choose "Start via Wireless debugging", then "Pairing". Shizuku
   shows a notification. Go to Developer options, Wireless debugging, "Pair device with pairing
   code", and type the six digits into Shizuku's notification. Back in Shizuku, press "Start".
   It should now say "Shizuku is running".
5. **Install Chicken Rear.** Download `chicken-rear-<version>.apk` from this repository's
   [Releases](../../releases) on the phone and open it. Allow your browser or file manager to
   install apps when asked.
6. **Open Chicken Rear** and press OK on the privacy note. The strip under the preview names what
   is missing: press "Fix ›", then "Continue", and choose "Allow all the time" in Shizuku's dialog.
   Do the same for each line the strip shows until it is gone.
7. **Work through the switches.** The row of pages under the preview (Idle, Charging, Message,
   Music, Timer, Call, Usage, Notes) chooses which settings are shown. In this order:
   1. *Message, Notifications*: the switch is on. If the strip says "Notifications need access",
      press "Fix ›", then "Continue". With Shizuku running it is granted at once; otherwise allow
      Chicken Rear on the system page that opens. Notifications, music, calls and timers all come
      through this access.
   2. *Idle*, then the "Advanced" row at the bottom, "Keep it running": set "Battery: no
      restrictions", turn "Autostart" on, and lock Chicken Rear in the recent apps (long-press its
      card, then the padlock). Without them HyperOS freezes or clears the app in the background and
      the back stops updating.
   3. *Usage*: press "Sign in". Your browser opens Claude's approval page; approve it and come
      back. The usage page is then one swipe away on the back.
   4. Optional, each asked for only when you switch its feature on: location (approximate for the
      lightning effect; precise and "Allow all the time" for notes tied to a place), the
      microphone permission ("Follows the music"; nothing is recorded), and the accessibility
      service "Pin to back: glass" (lets the pin animation play above other overlays). The glass
      service matters only with *Notes*, "Pin animation: Ours". If its switch is greyed out in
      Accessibility, open Chicken Rear's App info, the menu at the top right, "Allow restricted
      settings", then switch it on.
8. **Add a face.** No clip ships with the app. Prepare a clip (see below) and copy it to the phone
   with a computer:
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

Until a clip is there, the idle back is black; the other pages work without one.

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

## After a reboot

Wireless debugging and Shizuku do not survive a restart of the phone. After every reboot:

1. Connect to Wi-Fi, then Developer options, switch "Wireless debugging" on again.
2. Open Shizuku and press "Start" (no new pairing is needed).
3. Open Chicken Rear once.

## Gestures on the back

On the face:

| Gesture | Does |
|---|---|
| Tap | Full brightness for 15 seconds |
| Double tap | Dim; again, bright |
| Double tap, holding the second | Back off until you wake it |
| Double tap a dark back | Lights it again (Xiaomi's own wake gesture) |
| Swipe right to left | The live page (call, timer, music or charging) if there is one, then Claude usage |
| Swipe left to right | Xiaomi's Camera on the back |
| Swipe up | Pinned notes |
| Pull down from the right half of the top edge | Notifications |
| Press and hold | Next face, if you have more than one |

On a page, the opposite swipe goes back the way it came. A live page you swipe away stays away
until something new happens; it is one swipe right of the face meanwhile.

## What is not included

- **Video clips.** The face used during development is cut from copyrighted footage, which is not
  in this repository or in the APK. Bring your own (format and folder above).
- **Anything of Xiaomi's.** No picture, model, shader or animation file from Xiaomi's apps is
  bundled. Where a page uses Xiaomi's own artwork, it is read at run time from the system app that
  is already on your phone, and drawn plainly when that is not found.
- **Signing keys**, and any personal data.

## Known limits

- One phone model, one ROM. Nothing here was tried on another device.
- After a reboot nothing shows until Shizuku is started again.
- HyperOS freezes background apps when a heavy app such as the Camera runs. With battery
  restrictions off and Autostart on the app is thawed again; while frozen, the back keeps its cover
  but does not update.
- A lit rear display keeps the whole phone awake. The night rule exists for that reason; leave
  "Sleep at night" on unless you want the battery cost.
- Do not install the release build (`dev.chicken.rear`) and a debug build
  (`dev.chicken.rear.dev`) at the same time: each would draw on the back.
- Not tried against the real thing: the ChatGPT sign-in and its Codex limits, notes tied to a
  place, "Follows the music" on HyperOS, and the accessibility "glass" service on HyperOS.
- The call page has been used on real calls with the phone's own dialer only.
- The usage pages sign in with the public OAuth clients of Claude Code and of OpenAI's Codex CLI.
  Using a token outside the official client may be against the provider's terms; that risk is
  yours. Billing figures are not shown because the usage response has none.
- English only.

## Privacy

There is no analytics, no account with this project and no server of its own. What leaves the
phone:

- **Claude usage**: requests to Anthropic with your own account: the sign-in in your browser at
  `claude.ai`, the token exchange at `platform.claude.com`, usage and profile from
  `api.anthropic.com`. The tokens are kept on the phone, encrypted with an Android Keystore key,
  and sent nowhere else.
- **ChatGPT (optional)**: sign-in and token exchange at `auth.openai.com` and a usage request to
  `chatgpt.com`, with your own account, kept the same way.
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
seconds of the animation and never saved. "Follows the music" measures the level of the sound being
played; nothing is recorded.

The app shows the same in its privacy note on first start, and its "Privacy policy" row (under
Advanced) links to this section. While this repository is private, that link opens only for people
who have access to it.

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
- It targets the 17 Pro Max only.

The code is under the MIT licence, see [LICENSE](LICENSE), with the exceptions listed there and in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (third-party code, fonts and artwork). The APK
carries both files and the fonts' licence texts in `assets/licenses/`. Upstream's Chinese README
was removed because it describes upstream's app, not this fork.

"Claude" and the Claude mascot belong to Anthropic; "ChatGPT" and "Codex" to OpenAI; "Xiaomi" and
"HyperOS" to Xiaomi. They are named only to say what the app works with. This project is not
affiliated with or endorsed by any of them. No warranty.
