# Suno Driver

An Android app that drives Suno from your phone: type a one-line song idea,
an AI mind writes the Suno prompt for you, and the app fills in Suno's create
page and starts the generation — on your device, with your login.

Built for the DeepSoundRealm and StillMind Music Official channels.

## How it works

- **Write screen** — paste your NVIDIA API key once (saved only in the app's
  private storage on your phone, never in the code), type a song idea, pick a
  channel, tap **AI Write & Create**.
- **The AI mind** (NVIDIA, `meta/llama-3.2-11b-vision-instruct`) writes the
  title, description and style following the channel rulebook baked into
  `AiMind.kt`: hook first, short intro, natural human language, a hard
  banned-word blacklist (enforced again locally, with an automatic rewrite
  retry), and a near-miss title guard so a new title can't collide with an
  existing song ("Hold Me Close" vs "Hold Me in the Summer" is caught).
- **The driver** (`SunoDriver.kt`) fills Suno's create form inside a WebView
  and clicks Create. Your Suno login is done by hand in the app and persists
  via cookies. A state poller watches for Cloudflare challenges, the create
  form, and generation results, and reports everything to the on-screen log.
- **Suno Page screen** — the Suno WebView at full size: log in, watch
  generation, listen to takes.
- **Live VU meter** — the meter strip under the tabs syncs to the music: the
  driver polls the page's audio element (playing state + exact position) and
  the meter pulses on a beat grid locked to that playback clock — 104 bpm for
  DeepSoundRealm, 60 bpm for StillMind. It never touches the audio stream
  itself (browser security would mute playback).

Manual mode is also there: write your own description and style and tap
**Fill & Create**.

## Design

UI designed in Google Stitch ("studio at night"): warm near-black `#151311`,
one amber accent `#F59E0B`, Bricolage Grotesque / Plus Jakarta Sans /
JetBrains Mono (bundled in `res/font`). All decorative readouts in the design
(engine state, log line count, char counter, auth state) show real data.

## Build

Standard Android project (Kotlin, framework Views — no AndroidX runtime
deps beyond the Kotlin stdlib, minSdk 26, targetSdk 34):

```
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`. The debug build is what runs on
the phone; install it over any previous version — the Suno login and saved
API key carry over.

## Notes

- Package: `com.megah.sunopoc` (kept from the proof of concept so installs
  upgrade in place).
- Take-picking stays human: listen in the app, pick the take, and the MP3
  goes to cover art / lyric video / YouTube upload as usual.
