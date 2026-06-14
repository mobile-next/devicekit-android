## [1.2.1](https://github.com/mobile-next/devicekit-android/releases/tag/1.2.1) (2026-06-14)
* Feat: DeviceKit HTTP server for faster actions without spawning a process per call ([#22](https://github.com/mobile-next/devicekit-android/pull/22))
* Feat: Package lister with app name and versions ([#13](https://github.com/mobile-next/devicekit-android/pull/13))
* Fix: Return hint (placeholder) in view tree ([#22](https://github.com/mobile-next/devicekit-android/pull/22))

## [1.2.0](https://github.com/mobile-next/devicekit-android/releases/tag/1.2.0) (2026-05-17)
* Feat: Added UI view tree dump service via instrumentation, as a workaround for UIAutomator2 timeouts during animations ([#14](https://github.com/mobile-next/devicekit-android/pull/14))
* Fix: Reduced APK size by ~50% by removing over-broad ProGuard keep rules ([#14](https://github.com/mobile-next/devicekit-android/pull/14))

## [1.1.5](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.5) (2025-12-07)
* Fixed: AVC encoder latency issues with reduced timeout
* Fixed: Repeating frames bug, where screen had to change in order for stream to send bytes
* Fixed: Proper shutdown and resource cleanup when stdout pipe is broken
* Fixed: AVC stream shutdown when stdout closes

## [1.1.4](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.4) (2025-11-29)
* Feat: Added support for AVC (H.264) codec for screencapture
* Feat: Added --fps flag to control frame rate
* Fix: Stop screen capture and streaming when stdout is closed

## [1.1.3](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.3) (2025-09-04)
* Feat: New --scale and --quality parameters when encoding mjpeg stream ([#5](https://github.com/mobile-next/devicekit-android/pull/5))

## [1.1.2](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.2) (2025-07-31)
* Fix: Enabled proguard optimization to reduce .apk file size

