# NekoHub Release Notes

## v2.4.0-3 (2026-06-30)

### 🎨 深色模式适配
- 通知栏图标 `small_icon.xml` 改为白色剪影，适配深色/浅色系统栏
- 文件类型图标 `docx.xml`、相机图标 `ic_lucide_camera.xml` 改为白色线条，通过 Compose tint 自动着色
- `docs/icon.png` 和全部 7 个密度的 launcher 图标（mdpi~xxxhdpi）改为白色线条版本

### 🔄 自动上下文压缩
- 新增自动压缩设置页面（`AutoCompressPage.kt`）
- 支持开关、触发轮数（默认 20）、触发 Token 数（默认 8000）、目标 Token 数（默认 2000）、保留最近消息数（默认 32）
- 所有默认值尊重原版手动压缩对话框的选项

### 📡 更新检测
- 更新 API 切换到 GitHub Releases：`https://api.github.com/repos/SlightNeko/rikkahub/releases/latest`
- 修复版本解析：支持 `v` 前缀标签的 SemVer 比较

### 🐱 品牌重塑
- 应用名：RikkaHub → NekoHub
- 包名保持 `me.rerere.rikkahub` 不变（兼容覆盖安装）
- 全新猫猫图标（用户提供原图，Lanczos 缩放到所有密度）

### 🔔 主动消息设置
- 新增主动消息间隔设置 UI

### 🔧 修复
- 修复 `UpdateChecker` 中 `toaster.show()` 不存在的 `duration` 参数
- 修复 `ChatPage.kt` 中 `stringResource()` 在非 Composable 上下文中调用的问题
- 修复 `ChatPage.kt` 中 `Duration` 类型参数传递错误
- 删除损坏的 `mipmap-anydpi-v26/ic_launcher.xml`（adaptive icon XML 导致显示默认图标）

### 🏗️ CI/CD
- 签名 keystore 永久提交到仓库（`app/keystore.jks`）
- CI 自动复制 keystore 到预期路径并签名 Release APK
- CI 自动创建 GitHub Pre-release 并上传 APK

### 📝 README
- README 重组：中文为主，英文/繁体中文为辅
- 修复语言切换器自引用链接问题
- 更新日志移至 GitHub Releases

---

## v2.3.4-1
- Initial fork release
