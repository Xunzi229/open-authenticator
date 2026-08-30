# 验证器

Rust + Tauri 2 跨平台 TOTP 客户端（Windows / Linux / macOS）。系统 WebView，不内嵌 Chromium。

## 安全

- 主密码不落盘。Argon2id 派生密钥，AES-256-GCM 加密整个保险库
- 错误密码递增延迟；空闲自动锁定；复制后可清空剪贴板
- 列表不带密钥；验证码默认打码
- WebDAV 只同步 `vault.enc` 密文

保险库位置：

- Windows: `%APPDATA%\Authenticator\vault.enc`
- macOS: `~/Library/Application Support/Authenticator/vault.enc`
- Linux: `~/.local/share/authenticator/vault.enc`

## 本地开发

需要：Node 18+、Rust stable、各平台 Tauri 系统依赖。

```sh
cd authenticator
npm install
npm run tauri dev
```

## 打包

用 GitHub Actions：给仓库打 `v*` 标签（例如 `v1.0.0`）会自动编 Win / Linux / macOS。

- 整个仓库推送：用根目录 `.github/workflows/authenticator-release.yml`
- 只把 `authenticator/` 单独当仓库：用里面的 `.github/workflows/release.yml`

产物会出现在 GitHub Release（草稿）。

本地：

```sh
npm run tauri build
```
