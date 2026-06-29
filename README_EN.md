<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>NekoHub</h1>

Forked from [RikkaHub](https://github.com/re-ovo/rikkahub) — extended with system-level AI tools.

[简体中文](README.md) | [繁體中文](README_ZH_TW.md)
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 🚀 Download

🔗 [Download Debug APK](https://github.com/SlightNeko/rikkahub/actions) — Latest CI build artifacts

This is a fork with added features. Not on Google Play.

## ✨ What's New (Fork Features)

This fork adds 20+ local system tools enabling the AI assistant to interact with your Android device:

| Category | Tools |
|----------|-------|
| 📷 **Media** | Camera (silent photo), Music Control |
| 📍 **Location** | GPS, Nearby POI (via Amap), Location Tracking |
| 📅 **Schedule** | Calendar, Alarm, Time Info |
| 📱 **Device** | Battery, Screen Time, App Usage Trajectory, Screen Events, Clipboard |
| 💬 **Communication** | SMS, Notification Listener, Proactive Messaging |
| 💪 **Health** | Gadgetbridge Health Data |
| ☁️ **Sync** | Supabase Cloud Sync |
| ⚙️ **Settings** | Permission Manager, Integration Config (Amap API Key, Health DB, Supabase) |

### Settings Additions
- **Permissions** — Check & grant all app permissions in one place
- **Local Tools** — Browse all available system tools
- **Integrations** — Configure Amap API key, Gadgetbridge DB path, Supabase credentials
- **Proactive Messaging** — Configure proactive message push settings

> 🐱 Custom cat logo — NekoHub fork brand identity

## ✨ Features (from original RikkaHub)

- 🎨 Material You Design and 🌙 Dark mode
- 📦 Workspace: a proot-based Linux agent environment
- 🔄 Multiple AI Provider Support
- 🖼️ Multimodal input support
- 🖥️ Web access for multi-platform use
- 🛠️ MCP support
- 📝 Markdown Rendering
- 🪾 Message Branching
- 🔍 Search capabilities
- 🧩 Prompt variables
- 🤳 QR code config sharing
- 🤖 Agent customization
- 🧠 ChatGPT-like memory
- 📝 AI Translation

## 🔧 Building

Fork of [re-ovo/rikkahub](https://github.com/re-ovo/rikkahub). See [AGENTS.md](CLAUDE.md).

```bash
# Clone
git clone https://github.com/SlightNeko/rikkahub.git
```

Builds automatically via GitHub Actions on push to master.

> [!TIP]
> You need a `google-services.json` file at `app` folder for Firebase. CI creates a dummy one.

## 📄 License

Same as upstream: [AGPL v3.0](LICENSE)
