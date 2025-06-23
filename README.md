# Mobile Next Device Kit

An Android app that is a set of tools for controlling Android devices, changing configuration
that is not possible through adb-shell alone.

## Features

- Control clipboard content with support for utf8

## Installing

Simply download the latest release from [github releases](https://github.com/mobile-next/devicekit-android/releases), install the package onto your device, and see **Usage** section below for commands.

You may also just automate it all by running the copy-pasting the following script onto your terminal:
```bash
curl -s -O -J -L https://github.com/mobile-next/devicekit-android/releases/download/0.0.10/mobilenext-devicekit.apk

adb install -r mobilenext-devicekit.apk
```

## Usage

### Setting Clipboard via ADB

Once the app is installed on your device or emulator, you can set the clipboard content using the following ADB command:

```bash
adb shell 'am broadcast -a devicekit.clipboard.set -n com.mobilenext.devicekit/.ClipboardBroadcastReceiver -e text "this can be pasted now"'
```

### Clearing Clipboard via ADB

```bash
adb shell am broadcast -a devicekit.clipboard.clear -n com.mobilenext.devicekit/.ClipboardBroadcastReceiver
```

### Examples

```bash
# Set clipboard content
adb shell 'am broadcast -a devicekit.clipboard.set -n com.mobilenext.devicekit/.ClipboardBroadcastReceiver -e text "Hello World"'

# Using base64 for complex text (set 'encoding' to 'base64')
adb shell am broadcast -a devicekit.clipboard.set -n com.mobilenext.devicekit/.ClipboardBroadcastReceiver -e encoding "base64" -e text "4pyM77iP"

# Set special characters
adb shell 'am broadcast -a devicekit.clipboard.set -n com.mobilenext.devicekit/.ClipboardBroadcastReceiver -e text "こんにちは世界"'
```

Since **devicekit** cannot force a keypress, use `adb shell input keyevent KEYCODE_PASTE` to paste clipboard onto current input text field.

Note that the single quotes after `adb shell` are required if your text includes spaces. The base64 encoding allows you to safely transfer whatever utf8 you wish to paste.

## Installation

1. Build the APK using Android Studio or Gradle
2. Install on your device: `adb install app/build/outputs/apk/debug/app-debug.apk`
3. Launch the app to verify it's running
4. Use the ADB commands above to set clipboard content

## Debugging

The app logs all broadcast reception events. You can view logs using:

```bash
adb logcat -s ClipboardReceiver
```

## Requirements

- Android 10 (API 29) minimum
