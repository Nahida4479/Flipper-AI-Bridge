# Flipper Claude Bridge

Ask Claude from a Flipper Zero. The Flipper has no Wi-Fi, so an Android phone acts as the bridge: the Flipper sends the question over Bluetooth LE or USB, the phone asks the Claude API and sends the answer back to the Flipper screen.

Polish version: [README.pl.md](README.pl.md)

## Features

- Chat with Claude on the Flipper screen: on-screen keyboard, chat strip above the input line, scrolling.
- Bluetooth LE and USB. When the Flipper is plugged into the phone with USB, USB is used automatically, even if Bluetooth is also on. Without USB, Bluetooth is used.
- Word and phrase hints while typing, in English and Polish (selectable: English, Polish or both).
  - Built-in dictionaries (about 9000 words per language, 216 KB each) stored on the SD card.
  - The app learns from what you send. A word is suggested from your own history first. A whole sentence is suggested only after you used it at least twice.
  - Polish letters are matched exactly (`cz` suggests `cześć`, while `zolw` does not suggest `żółw`).
  - Hold OK (or press the `Tab` key on the screen) to accept a hint.
- Two saved chats. Starting a new chat when both slots are used replaces the oldest one. Each chat is limited to 500 KB.
- The phone app keeps the API key encrypted with the Android Keystore (AES-256-GCM).
- The phone service runs with the screen off, uses no wake locks, no polling and no continuous scanning, so battery use is minimal.
- Claude is asked for very short plain-text answers. It answers in Polish or English (the language you write in) and in English for any other language.

## Repository layout

| Path | Content |
| --- | --- |
| `flipper-app/` | Flipper Zero app (C, `ufbt`) |
| `android-app/` | Android app (Kotlin, Jetpack Compose) |
| `release/` | Prebuilt `claude_bridge.fap` and dictionary files |
| `tools/make_dict.py` | Generates the dictionary files |
| `.github/workflows/` | GitHub Actions that build the APK and the `.fap` |

Branches: `main` is the stable first version, `beta` receives new changes for testing.

## Install

### Flipper

1. Copy `release/claude_bridge.fap` to `SD:/apps/Tools/`.
2. Copy the folder `release/apps_assets/claude_bridge/` to `SD:/apps_assets/claude_bridge/` (it must contain `dict/en.dict` and `dict/pl.dict`).
3. Open `Apps -> Tools -> Claude Bridge`.

Or build it yourself: `cd flipper-app && ufbt` (or `ufbt launch` with the Flipper connected).

### Android

Download the APK from the GitHub Actions run of the `Build Android APK` workflow (artifact `flipper-claude-bridge-apk`) and install it. To build locally: install JDK 17 and the Android SDK, then run `gradle :app:assembleDebug` in `android-app/`.

## Use

1. On the phone: open the app, grant permissions, enter your Claude API key and press `Save key`, then press `Start`.
2. On the Flipper: open Claude Bridge.
3. Bluetooth: on the phone press `Scan for Flipper` and pick your Flipper. Accept the pairing request (the Flipper shows a PIN). The connection is restored automatically later.
4. USB: plug the Flipper into the phone with a USB-C cable (OTG). Accept the USB permission once and choose "always". USB is then used automatically.
5. On the Flipper choose `Chat` and type.

### Flipper controls

| Key | Action |
| --- | --- |
| D-pad | Move over the keyboard. Up from the top row selects the chat strip, then Up/Down scroll |
| OK | Press the key |
| Hold OK | Accept the hint |
| Back | Delete a character. When the input is empty: back to the menu |
| Hold Back | Back to the menu |

The bottom keyboard row: layer (`abc` / `ABC` one letter / `PL1` Polish letters, digits, symbols), `Del`, `Space`, `Tab` (accept hint), `Send`.

Polish letters are typed on the `PL1` layer. The Flipper screen font has no Polish letters, so they are displayed without accents on the Flipper, but the text sent to Claude keeps them.

### Language

The official Flipper firmware has no interface language setting that apps can read, so the hint language is set in the app (`Settings -> Hints language`: English, Polish, Both). The default is English. The interface of the app is in English.

## How it works

```
Flipper app  <-- BLE serial / USB CDC -->  Android service  --HTTPS-->  Claude API
```

Both links carry the same line protocol (UTF-8, one message per line, `\n` escaped as `\\n`):

| Direction | Line | Meaning |
| --- | --- | --- |
| Flipper to phone | `N` | New conversation |
| Flipper to phone | `U:<text>` / `B:<text>` | Earlier user / Claude message (context replay when a saved chat is resumed) |
| Flipper to phone | `Q:<lang>:<text>` (`lang` is `en`, `pl` or `both`) | Question |
| Phone to Flipper | `S:<text>` | Status |
| Phone to Flipper | `A:<chunk>` | Part of the answer |
| Phone to Flipper | `E` | End of the answer |
| Phone to Flipper | `X:<text>` | Error |

The Flipper selects USB when the phone has opened the USB port, otherwise Bluetooth.

## Security

- The API key is stored encrypted with a non-exportable Android Keystore key. Backups are disabled.
- The key is only sent to `https://api.anthropic.com`.
- Chat history on the Flipper is stored on the SD card as plain text, like everything else on it.
- The Bluetooth link uses the Flipper pairing with a PIN.

## Notes

- The Flipper app and the Android app were written without access to the hardware in the build environment, so test them on your devices and report problems.
- The Flipper UI layout (128x64 px) may need small adjustments after the first real test.
