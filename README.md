# Pixel Pup

A small dog that lives as a floating overlay on your screen. It wanders on
its own, reacts to charging / low battery / unlocking your phone, has needs
that decay over real time, and responds to taps, drags, and long-presses.

No Android Studio or JDK needed on your machine — GitHub Actions builds the
APK for you.

## Why this builds cleanly in CI

- Kotlin only, classic Views + Canvas (no Jetpack Compose).
- Only two dependencies: `androidx.core:core-ktx` and `androidx.appcompat:appcompat`.
- Zero binary assets — the dog is drawn with Canvas, sounds are synthesized
  at runtime with `AudioTrack`, and the launcher icon is an XML vector.
- No `gradle-wrapper.jar` — the workflow installs Gradle itself via
  `gradle/actions/setup-gradle`, so there's nothing to corrupt in transit.
- Debug build only (`assembleDebug`) — self-signed, no keystore/secrets.
- Pinned versions: AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24, compileSdk/targetSdk 34,
  minSdk 26, JDK 17.

## Get the APK

1. Create a new empty repository on GitHub.
2. Upload the contents of this folder (keep the folder structure — the
   `.github/workflows/build.yml` path has to survive) and commit to `main`.
3. Open the **Actions** tab — the `Build APK` workflow runs automatically.
4. When it finishes, download the `pixel-pup-debug-apk` artifact from the
   run summary, unzip it, and install the `.apk` on your phone (you'll need
   to allow installs from unknown sources).
5. Open the app, tap **Grant overlay permission**, then **Let the pup out**.

If a run ever fails, copy the red step's log and describe just that error —
keep every constraint above intact rather than re-architecting the project.

## Project layout

```
app/src/main/java/com/mahesh/pixelpup/
  PetBrain.kt          pure Kotlin state machine — needs, mood, movement, events
  PetState.kt          the 15 behavioural states
  Needs.kt              hunger / energy / bladder / affection
  Mood.kt               ECSTATIC..DESPERATE
  PetEvent.kt           events + save-state data class
  DogView.kt            all Canvas drawing (dog, particles, bubble, radial menu)
  SoundEngine.kt         AudioTrack-based bark/whine/yip synthesis
  TouchController.kt     tap / double-tap / drag+fling / long-press menu
  PetOverlayService.kt   foreground overlay service, 60fps loop, persistence
  MainActivity.kt        setup screen: permissions, sliders, live status
```

## Phase-2 ideas

Fetch (flick a ball, dog chases it), growth over days of care, perking up at
notifications, teachable tricks via long-press training, naming/collar color
on first launch, step-count walks. Add these one at a time so the build
stays green.
