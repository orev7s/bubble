# Bubble

A floating dictation bubble for Android. Tap a text field anywhere, speak, and the
transcript lands in that field.

Transcription runs on [Groq](https://groq.com) using `whisper-large-v3` (the full
model, not the turbo variant). Bring your own API key — nothing is bundled, and
nothing is sent anywhere except Groq.

## What it does

- A draggable circle sits on top of every app and appears when a text field is focused.
- Two trigger modes: **hold to talk** (press, speak, release) or **tap to toggle**.
- An optional dwell delay before listening, so brushing past the bubble does nothing.
- The result is written into the focused field, using clipboard paste as a fallback.
  If the field is gone, the text is copied to the clipboard instead of being lost.
- Multiple API keys with four rotation strategies: per request, every N requests,
  every N minutes, or only when a key fails. Keys are validated before they are saved.
- Light and dark themes, and a layout that stays readable on tablets.

## Requirements

- Android 8.0 (API 26) or newer
- A Groq API key from https://console.groq.com/keys

## Build

```bash
git clone https://github.com/<you>/bubble.git
cd bubble
```

Create `local.properties` with your SDK path:

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew :app:assembleDebug
```

Release builds are signed only if you provide a key. Add these to `local.properties`
or `keystore.properties` — none of these files are committed:

```properties
RELEASE_STORE_FILE=keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Without them, `assembleRelease` produces an unsigned APK.

## Setup on the device

1. Open the app and grant **Microphone**.
2. Grant **Overlay** (display over other apps) — required for the bubble.
3. Enable **Accessibility** for Bubble — required to detect text fields and insert text.
   Android only allows this to be switched on by hand in system settings.
4. Tap **Start**.

If the bubble disappears after a while, exclude Bubble from your OEM's battery
optimisation; some vendors kill foreground services aggressively.

## How it works

| Piece | Role |
| --- | --- |
| `OverlayService` | Foreground service holding the bubble, recording, and calling Groq |
| `InputWatchService` | Accessibility service: watches focused fields, inserts the transcript |
| `AudioRecorder` | 16 kHz mono PCM captured to memory and wrapped as WAV |
| `GroqApi` | Transcription and key validation over OkHttp |
| `KeyManager` | Picks the next key according to the rotation strategy |

## Notes and limits

- Insertion relies on `ACTION_SET_TEXT`, then clipboard paste. A few apps use custom
  editors that refuse both; those end up on the clipboard.
- Audio is capped at 120 seconds per utterance.
- Keys are stored in plain `SharedPreferences` on the device, not encrypted.

## License

MIT — see [LICENSE](LICENSE). Fork it, change it, ship it.
