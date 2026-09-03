# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

独自在手机上打开网页、播放视频、把已能看到的媒体存到本机的人。使用情境是夜里或外出时单手操作，目标明确：找到片子、下下来、之后能打开。

（推断自应用文案、现有四个底栏入口与本机-only 隐私声明，未做用户访谈。）

## Product Purpose

在内置 WebView 里检测并下载用户已经能播放的非 DRM 媒体，全部在本机完成。成功是：打开页面、捕获到真实片子、下载进片库、能打开文件。

## Positioning

检测发生在用户自己的 WebView 会话里（网络请求、Service Worker、DOM probe），不经过账号或开发者服务器。不做 DRM 许可证提取。

## Operating Context

底栏：浏览、任务、片库（设置入口暂时隐藏）。浏览含起始页（地址栏 + 搜索记录）和网页 chrome（后退/前进/刷新/解析）。地址栏可粘贴抖音分享口令或短链并自动下载。播放后看捕获结果再下载。任务列表显示状态与进度。片库只列已完成文件。搜索记录只在地址栏点前往时写入，页面内翻页不记。

## Capabilities and Constraints

- 已有：HLS / DASH / 直链 / 可选 yt-dlp，WorkManager 前台下载，Room 任务与搜索记录，广告/浮层/预览过滤，捕获列表按时长从长到短，片库内置播放与分享/导出/重命名/转 MP4，任务和片库封面，抖音链接解析下载。
- 不做：账号、分析、广告、远端同步、DRM。
- 实现栈：Kotlin + 原生 Android View / Material 组件，非 Compose。
- 视觉世界：深色科技（夜色底、青强调、协议芯片）。审阅对照：`ui-review/index.html`。

## Brand Commitments

产品名 Capture（`app_name`）。品牌色为青 `#3DEFF8`，铺在夜色底 `#070B18` 上。系统无衬线 + 等宽用于进度与协议。

## Evidence on Hand

- 界面文案：`app/src/main/res/values/strings.xml`
- 结构：`activity_browser.xml` 与 `panel_*.xml`
- 本文件其余用户事实标为仓库推断。无真实用户研究、无竞品授权素材。审阅稿中的任务名、进度数字为示意，已在稿面标明。

## Product Principles

1. 本机优先：记录与媒体不出设备。
2. 先找到片子再下载，捕获列表是决策面。
3. 任务状态必须一眼可读，且不因换行挤掉标题。
4. 搜索记录是用户主动提交的，不是浏览轨迹。
5. 不碰 DRM，不假装能下受保护内容。
