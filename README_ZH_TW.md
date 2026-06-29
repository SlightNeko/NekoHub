<div align="center">
  <img src="docs/icon.png" alt="App 圖標" width="100" />
  <h1>NekoHub</h1>

Fork 自 [RikkaHub](https://github.com/re-ovo/rikkahub) — 擴展了系統級 AI 工具。

[English](README_EN.md) | [简体中文](README.md)
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 🚀 下載

🔗 [下載 Debug APK](https://github.com/SlightNeko/rikkahub/actions) — 最新 CI 構建產物

此為 Fork 版本，未上架 Google Play。

## ✨ 新增功能（Fork 特性）

本 Fork 新增了 20+ 本地系統工具，讓 AI 助手能與你的 Android 裝置互動：

| 類別 | 工具 |
|----------|-------|
| 📷 **媒體** | 相機（靜音拍照）、音樂控制 |
| 📍 **位置** | GPS、附近 POI（高德）、位置追蹤 |
| 📅 **日程** | 日曆、鬧鐘、時間資訊 |
| 📱 **裝置** | 電池、螢幕時間、應用使用軌跡、螢幕事件、剪貼簿 |
| 💬 **通訊** | 簡訊、通知監聽、主動訊息 |
| 💪 **健康** | Gadgetbridge 健康資料 |
| ☁️ **同步** | Supabase 雲端同步 |
| ⚙️ **設定** | 權限管理、整合配置（高德 API Key、健康資料庫、Supabase） |

### 設定新增
- **權限** — 一站式檢視與授權所有應用權限
- **本地工具** — 瀏覽所有可用的系統工具
- **整合** — 配置高德 API Key、Gadgetbridge 資料庫路徑、Supabase 憑證
- **主動訊息** — 配置主動訊息推送設定

> 🐱 自訂貓貓圖示 — NekoHub Fork 專屬品牌標識

## ✨ 功能特色（來自原版 RikkaHub）

- 🎨 Material You 設計和 🌙 暗色模式
- 📦 工作區：基於 proot 的 Linux 智能體環境
- 🔄 多種 AI 供應商支援
- 🖼️ 多模態輸入支援
- 🖥️ Web 多端存取
- 🛠️ MCP 支援
- 📝 Markdown 渲染
- 🪾 訊息分支
- 🔍 搜尋功能
- 🧩 Prompt 變數
- 🤳 二維碼配置分享
- 🤖 智能體自訂
- 🧠 類 ChatGPT 記憶
- 📝 AI 翻譯

## 🔧 構建

Fork 自 [re-ovo/rikkahub](https://github.com/re-ovo/rikkahub)。詳見 [AGENTS.md](CLAUDE.md)。

```bash
# 複製
git clone https://github.com/SlightNeko/rikkahub.git
```

推送至 master 分支後透過 GitHub Actions 自動構建。

> [!TIP]
> 需要在 `app` 資料夾下放置 `google-services.json` 檔案以使用 Firebase。CI 會建立一個佔位檔案。

## 📄 許可證

與上游一致：[AGPL v3.0](LICENSE)
