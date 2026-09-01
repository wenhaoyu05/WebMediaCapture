# WebMediaCapture

Local-first Android app for capturing and downloading **non-DRM** media that the user can already access in a built-in WebView. Detection is on-device. There is no account, analytics, ads, or developer server.

## 1. Project layout

```text
app/src/main/java/com/webmediacapture
├── browser/      WebView controller, request observer, cookie provider
├── detector/     URL / MIME / probe / HLS / DASH classifiers and dedup
├── extractor/    Direct, HLS, DASH, yt-dlp extractors
├── download/     Queue, WorkManager worker, Direct/HLS/DASH/yt-dlp engines, FFmpeg mux
├── network/      OkHttp client, header manager, cookie bridge, HTTP probe
├── database/     Room downloads + local history
├── repository/   In-memory candidate store keyed by pageSessionId
├── ui/           browser, media sheet, downloads sheet, settings
└── util/         SafeLog, AppSettings
```

## 2. Architecture

```text
WebView shouldInterceptRequest / ServiceWorker / DOM probe
        → RequestObserver (SharedFlow, never blocks the WebView thread)
        → MediaDetector (background)
            → UrlPatternDetector / MimeTypeDetector / HeaderProbeDetector
            → HlsDetector / DashDetector
        → CandidateDeduplicator
        → MediaRepository (StateFlow)
        → BrowserViewModel → UI FAB + bottom sheet
        → DownloadManager → WorkManager foreground worker
            → DirectDownloader | HlsDownloader | DashDownloader | YtDlpEngine
            → FfmpegMuxer (video+audio → MP4)
```

## 3. SurfSave research (clean-room)

SurfSave (`songsongshuo785-art/SurfSave`) is licensed **GPL-3.0**. This project is an independent implementation: architecture ideas only, no copied source.

Public SurfSave wiki / README conclusions:

1. **Capture chain:** Browser WebView intercepts requests → detector → chip/sheet → download queue → library/player.
2. **WebView:** browsing, tabs, cookies; `CustomWebViewClient` / request inspector do media detection.
3. **Network layer:** OkHttp + cookie jar; authenticated downloads reuse browser cookies.
4. **Recognition:** request intercept, not HTML-only `<video>` scraping.
5. **Cookie/Header:** WebView cookies and Referer/UA are attached for in-app download; they are not forwarded to external players.
6. **yt-dlp:** page-level parsing / complex sites / playlists.
7. **FFmpeg:** merge/remux after stream engines.
8. **HLS/DASH:** dedicated stream engines; DRM is not downloadable.
9. **Dedup:** download queue duplicate detection (SurfSave-specific UI/queue rules).
10. **Downloads:** multi-engine queue with concurrency and notifications.

This app does **not** include SurfSave’s Xray proxy, ML Kit translation, PiP player, or cookie profile import/export.

## 4. Differences from SurfSave

- Clean-room Kotlin modules named after the capture pipeline, not SurfSave packages.
- No player, PiP, proxy, ads, or remote config.
- Native HLS/DASH segment download with FFmpeg mux, yt-dlp as fallback.
- Authorization/Cookie never persisted to Room; logs are redacted.
- Probe concurrency capped at 4; static assets are not probed.

## 5. Capture data flow

ObservedRequest records url, method, mime, headers (Cookie/UA/Referer/Origin/Accept/Accept-Language), pageUrl, pageSessionId, timestamp. Candidates are isolated by `pageSessionId`. Blob URLs are ignored; underlying media requests are kept.

## 6. Engine chains

- **Direct:** URL/MIME/probe → OkHttp Range download with inherited headers.
- **HLS:** parse master/media → download init+segments (AES-128 decrypt when present) → FFmpeg/concat mux. SAMPLE-AES / FairPlay → `DRM_PROTECTED`.
- **DASH:** parse MPD Period/AdaptationSet/Representation/SegmentList|Template → download video+audio → FFmpeg mux. `ContentProtection` → `DRM_PROTECTED`.
- **yt-dlp:** optional post-load fallback and UNKNOWN/complex pages; output mapped to `MediaCandidate`.

## 7. Key types

`MediaCandidate`, `RequestContext`, `MediaDetector`, `CandidateDeduplicator`, `CookieBridge`, `DownloadWorker`, `HlsDownloader`, `DashDownloader`, `YtDlpExtractor`, `FfmpegMuxer`, `SafeLog`.

## 8. Implemented

Built-in browser; realtime WebView capture; multi-level detection; HLS/DASH/Direct/yt-dlp; header inheritance; download queue + foreground notifications; local history; privacy defaults; unit tests with MockWebServer.

## 9. Limits

No DRM circumvention. Live HLS sliding windows are captured from the current playlist only. Very exotic DASH (`$Time$` with incomplete timelines) falls back to yt-dlp. Multi-tab browsing is not implemented. SPA hash navigations may keep the same page session.

## 10. Privacy check

No server, account, analytics, telemetry, or ads. Backup disabled. Cookie/Authorization not stored in Room. SafeLog redacts Cookie, Authorization, token, signature, auth, key, expires. Cleartext traffic disabled in release.

## 11–15. Build artifacts

See the latest `./gradlew clean test lint assembleRelease` output. Release APK:

`app/build/outputs/apk/release/app-release.apk`
