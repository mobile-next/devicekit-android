## [1.2.0](https://github.com/mobile-next/devicekit-android/releases/tag/1.2.0) (2026-05-17)

* Added UI view tree dump service via instrumentation, as a workaround for UIAutomator2 timeouts during animations ([#14](https://github.com/mobile-next/devicekit-android/pull/14))
* Reduced APK size by ~50% by removing over-broad ProGuard keep rules ([#14](https://github.com/mobile-next/devicekit-android/pull/14))

## [1.1.5](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.5) (2025-12-07)

* Fixed AVC encoder latency issues with reduced timeout
* Fixed repeating frames bug, where screen had to change in order for stream to send bytes
* Fixed proper shutdown and resource cleanup when stdout pipe is broken
* Fixed AVC stream shutdown when stdout closes

## [1.1.4](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.4) (2025-11-29)

* Added support for AVC (H.264) codec for screencapture
* Added --fps flag to control frame rate
* Stop screen capture and streaming when stdout is closed

## [1.1.3](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.3) (2025-09-04)

* New --scale and --quality parameters when encoding mjpeg stream ([#5](https://github.com/mobile-next/devicekit-android/pull/5))

## [1.1.2](https://github.com/mobile-next/mobile-mcp/releases/tag/1.1.2) (2025-07-31)

* Enabled proguard optimization to reduce .apk file size

