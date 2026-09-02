# Capture

当前发布版本：**[1.1.5](https://github.com/wenhaoyu05/WebMediaCapture/releases/tag/v1.1.5)**（`versionCode` 7）

本机优先的 Android 应用：在内置 WebView 里打开你已经能看的网页，检测并下载其中的 **非 DRM** 媒体。检测与下载都在设备上完成，没有账号、分析、广告或开发者服务器。

最低系统：Android 7.0（API 24）。包名：`com.webmediacapture`。许可证：[GPL-3.0](LICENSE)。

## 安装

到 [Releases](https://github.com/wenhaoyu05/WebMediaCapture/releases/latest) 下载 `Capture-1.1.5.apk`，允许未知来源后安装。

## 能做什么

- 内置浏览：地址栏打开网址或关键词搜索；搜索记录只在点「前往」时写入。
- 实时捕获：拦截 WebView 网络请求、Service Worker 与页面探测，识别 HLS / DASH / 直链。
- 下载：WorkManager 前台任务；可选 yt-dlp 补充检测；音视频用 FFmpeg 合成 MP4。
- 四个底栏：浏览、任务、片库、设置。片库只列出已完成的本机文件。
- 界面：深色科技（夜色底、青色动作、紫色协议标记）。

## 不会做什么

不提取 DRM 许可证，不下载受保护内容。不做多标签、远端同步或内置播放器。Cookie 与 Authorization 不写入数据库；日志会脱敏。

## 使用

1. 输入网址或关键词，打开页面并播放视频。
2. 点右下角查看捕获结果，按时长从长到短排列。
3. 下载后在「任务」看进度，完成后在「片库」打开文件。

直播 HLS 只保存当前播放列表窗口。带 `ContentProtection` 的 DASH 或 SAMPLE-AES / FairPlay 会标为 DRM，不会下载。

## 从源码构建

```text
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。需要 JDK 17+ 与 Android SDK。

## 实现要点

检测链：WebView 拦截 / Service Worker / DOM probe → `MediaDetector` → 去重 → 捕获列表 → 下载队列。

引擎：Direct（Range + 继承请求头）、HLS（分片，AES-128 可解）、DASH（MPD 分轨再 mux）、yt-dlp（复杂页回退）。

这是独立的 clean-room 实现，参考过公开的 SurfSave 架构说明，不含其源码，也不含代理、翻译或画中画播放器。
