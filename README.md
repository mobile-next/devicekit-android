# Mobile Next Device Kit

A set of tools for controlling Android devices and reading device state that is
not possible through `adb shell` alone. The tools run **headless** via
`app_process` straight from a single `classes.dex` — nothing needs to be
installed on the device.

<p align="center">
  <a href="https://github.com/mobile-next/devicekit-android">
    <img src="https://img.shields.io/github/stars/mobile-next/devicekit-android" alt="GitHub Stars" />
  </a>
  <a href="https://github.com/mobile-next/devicekit-android/releases">
    <img src="https://img.shields.io/github/release/mobile-next/devicekit-android" alt="Latest Release" />
  </a>
  <a href="https://github.com/mobile-next/devicekit-android/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/license-FSL--1.1--Apache--2.0-blue.svg" alt="License" />
  </a>
  <a href="http://mobilenexthq.com/join-slack">
    <img src="https://img.shields.io/badge/join-Slack-blueviolet?logo=slack&style=flat" alt="Slack community channel" />
  </a>
</p>

## Features

- **Clipboard** — get, set (incl. base64), and clear the system clipboard
- **UI tree dump** — dump the on-screen accessibility hierarchy as JSON
- **Package list** — list installed packages with app name and version
- **Screen streaming** — H.264 (AVC) or MJPEG, with scale/fps/quality options

All of the above run from the published `devicekit.dex` with no app install.

## Installing

Download the latest `devicekit.dex` from
[GitHub releases](https://github.com/mobile-next/devicekit-android/releases) and
push it to the device:

```bash
curl -s -O -J -L https://github.com/mobile-next/devicekit-android/releases/latest/download/devicekit.dex
adb push devicekit.dex /data/local/tmp/
```

## Usage

Every tool is launched the same way — `app_process` with the dex on the
classpath:

```bash
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.<Tool> [args]
```

The tools run as the `shell` user (uid 2000); no root is required.

> For tools that emit **binary** output (the screen streamers) use
> `adb exec-out` instead of `adb shell`, otherwise the stream is corrupted by
> newline translation.

### Clipboard

```bash
# Set the clipboard
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.Clipboard set "Hello World"

# Set from base64 (safest for spaces, emoji, and other UTF-8) — "✌️"
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.Clipboard set --base64 "4pyM77iP"

# Print the current clipboard text
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.Clipboard get

# Clear the clipboard
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.Clipboard clear
```

Since devicekit cannot force a keypress, paste with:

```bash
adb shell input keyevent KEYCODE_PASTE
```

### Dump the UI view tree

Prints the current accessibility hierarchy as JSON to stdout:

```bash
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.UiDump
```

Optionally wait up to N milliseconds for the UI to go idle first (useful right
after a navigation or transition):

```bash
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.UiDump 2000
```

### List installed packages

Prints a JSON array of `{packageName, appName, version}`:

```bash
adb shell CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.PackageLister
```

### Stream the screen as H.264 (AVC)

Writes a raw H.264 (Annex-B) elementary stream to stdout:

```bash
adb exec-out CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.AvcServer --scale 0.5 --fps 30 > screen.h264
```

Pipe it straight into a player:

```bash
adb exec-out CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.AvcServer | ffplay -probesize 32 -sync ext -
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `--bitrate` | int (bps) | 10000000 | Target bitrate (min 100000) |
| `--scale` | float | 1.0 | Output scale, 0.1–2.0 |
| `--fps` | int | 30 | Frame rate, 1–60 |

### Stream the screen as MJPEG

Writes a `multipart/x-mixed-replace` MJPEG stream to stdout:

```bash
adb exec-out CLASSPATH=/data/local/tmp/devicekit.dex app_process / com.mobilenext.devicekit.MjpegServer --quality 80 --scale 0.5 --fps 30 > screen.mjpeg
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `--quality` | int | 80 | JPEG quality, 1–100 |
| `--scale` | float | 1.0 | Output scale, 0.1–2.0 |
| `--fps` | int | 30 | Frame rate, 1–60 |

## Resident JSON-RPC server (advanced)

For repeated UI queries, `DeviceKitServer` starts once and stays resident,
answering JSON-RPC over a persistent socket instead of paying process startup on
every call. It is instrumentation-based, so it requires the **APK** to be
installed (build it from source — see below):

```bash
# Start the server (leave running or background it)
adb shell am instrument -w com.mobilenext.devicekit/.DeviceKitServer
```

It listens on `localabstract:devicekit`. Forward a local port and query it:

```bash
adb forward tcp:8080 localabstract:devicekit

curl -s http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"device.dump.ui","params":{"waitUntilIdle":2000}}'

adb forward --remove tcp:8080
```

## Building from source

```bash
./gradlew assembleDebug
```

The dex used by all the `app_process` tools is inside the built APK:

```bash
unzip -o -j app/build/outputs/apk/debug/app-debug.apk classes.dex -d .
adb push classes.dex /data/local/tmp/devicekit.dex
```

To use the instrumentation-based resident server, install the APK instead:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Android 10 (API 29) or newer
- Tools run in the `adb shell` (uid 2000) context; no root required
