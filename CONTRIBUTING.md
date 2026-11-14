# Contributing

## Branches

- `master` is always releasable: it builds, its unit tests pass, and it holds nothing personal.
- Work happens on `feature/<name>` branches (for example `feature/timer-page`), one topic each,
  branched from `master`.
- A feature branch reaches `master` by pull request. Before merging:
  - `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes;
  - the change has been seen on the phone, or the pull request says plainly that it has not;
  - `CHANGELOG.md` has a line for it under "Unreleased".
- Fixes for a release that is already out go on `fix/<name>`, the same way.
- Changes from upstream (Magic Rear) come in on `feature/upstream-<version>`: merge the upstream
  tag there, resolve, test, and open a pull request like any other.
- Code changes are made in the maintainer's working copy and arrive here as `feature/sync-*`
  branches. A pull request from anyone else is ported there first and comes back through a sync,
  never merged on its own: the next sync replaces the whole tree, so code merged here directly
  would be undone by it. Changes to this repository's own documents (`README.md`,
  `CONTRIBUTING.md`, `CHANGELOG.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`) are the exception and
  are merged here as usual.

## Commits

Say what changed and why, in plain words. No names of people, no quotes from chats, no times of day,
no paths on your own machine, no screenshots of a real phone's content.

## Versions and releases

- The version lives in `version.properties`: `name` is the upstream release this fork is based on
  plus the fork's own count (`1.2.1-cr1`, `1.2.1-cr2`, ...), `code` is a whole number that goes up
  by one with every published APK. Android refuses an update whose code did not rise.
- A release is a tag `v<name>` (for example `v1.2.1-cr1`) on a commit of `master`.
- Every release has the signed release APK attached, named `chicken-rear-<name>.apk`, with its
  SHA-256 in the release notes. Build it from the tagged commit: the APK records the commit it was
  built from.
- All releases must be signed with the same key, or phones cannot update from one to the next.
  The key is never in the repository (see `.gitignore`).

To cut a release: raise `name` and `code` in `version.properties`, move the "Unreleased" lines of
`CHANGELOG.md` under the new version, merge to `master`, tag, build, attach.

## What must stay out of the repository

- Anything personal: names, e-mail addresses, phone numbers, addresses of devices or networks,
  serial numbers, account names, tokens, notification or message contents, location data, and
  screenshots or logs of a real phone.
- Signing keys and their passwords.
- Files from Xiaomi's apps (pictures, models, shaders, animations). The app reads what it needs
  from the phone at run time.
- Video clips and the footage they are cut from.

Test fixtures use made-up people and messages.

## Building

See "Building from source" in the README. JDK 17, Android SDK platform 36.
