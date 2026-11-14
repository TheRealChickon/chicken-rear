# Changelog

Versions are the upstream release this fork is based on plus the fork's own count.

## Unreleased

Nothing yet.

## 1.2.1-cr1

The first release of Chicken Rear, a fork of Magic Rear 1.2.1 for the Xiaomi 17 Pro Max.

### Changed from Magic Rear 1.2.1

- Runs alongside Xiaomi's rear screen and covers it ("companion mode"). The mode that replaced
  Xiaomi's rear app, and the old tabbed settings, are removed. A stored "replace" setting is read
  as companion mode.
- Application id `dev.chicken.rear` (debug builds: `dev.chicken.rear.dev`), label "Chicken Rear".
- Built for the 17 Pro Max (`2509FPN0BC`, 976x596 rear panel) only.

### Added

- The face: one video clip looping over the whole rear panel, drawn in one OpenGL pass, with an
  optional clock, a glitch effect and lightning when the weather storms.
- A cover over Xiaomi's rear launcher whenever a page of the app is wanted, kept by two shell
  processes so it survives one of them dying.
- Full-screen pages in the style of Xiaomi's rear pages: music (with a wide layout for video),
  calls, timers, charging (leaves at full after a short moment), and a pop-up for a new message.
- Notifications pulled down from the top edge as a pile of pages, dismissed by a sideways throw.
- Pinned notes: "Pin to back" from text selection and share, a notes page, notes tied to a place,
  and a pin animation that is either Xiaomi's own or the app's.
- The Claude usage page: limits as filling vessels with reset times and an animated mascot, for up
  to two accounts; ChatGPT's Codex limits as an optional account.
- One navigation model for every page of the back, with pages that follow the finger, slide in and
  out, and take the display's rounded corners while they move.
- Always-on rules: lit in use and face down, off in a pocket, on its back and after a double tap
  and hold; a night rule that lets the phone sleep; optional home detection by Wi-Fi.
- Brightness that follows the rear light sensor, a double tap to dim, and a dimmed front screen
  while the phone lies face down.
- A new settings page with a live preview of the back and settings filtered by the selected page.
- Release builds keep the guard service's classes, and can be signed from a `keystore.properties`.

### Removed

- Upstream's demo video, its Chinese README, and artwork that the fork does not draw.

### Not included

- No video clip for the face, and nothing from Xiaomi's apps: see the README.
