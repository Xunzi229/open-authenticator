# 验证器

Rust + Tauri 2 跨平台 TOTP 客户端（Windows / Linux / macOS）。系统 WebView，不内嵌 Chromium。

## 安全

- 默认不持久化主密码。Argon2id 派生密钥，AES-256-GCM 加密整个保险库
- 错误密码递增延迟；空闲自动锁定；复制后可清空剪贴板
- 列表不带密钥；验证码默认打码
- WebDAV 只通过 HTTPS（localhost 可用 HTTP）同步 `vault.enc` 密文，并使用 ETag 防止并发覆盖

Windows Hello 为可选便利功能。开启后，主密码会由当前 Windows 用户的系统凭据存储保护；Hello 验证通过后应用才会读取它。该模式的安全边界是 Windows 用户账户，不等同于硬件密钥直接解密保险库。

WebDAV 拉取前会在保险库目录的 `backups/` 中保存一份本地加密备份。

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

推送和拉取请求由 `.github/workflows/ci.yml` 在 Windows、Linux、macOS 上执行格式检查、Clippy、测试和无 bundle 编译。给仓库打 `v*` 标签会由 `.github/workflows/release.yml` 重新测试、打包并发布 GitHub Release；带 `-` 的标签发布为 prerelease。

稳定版发布要求配置 Windows Authenticode 证书和 Apple Developer ID/公证 secrets。prerelease 在未配置正式证书时允许生成未签名 Windows 包和 macOS ad-hoc 签名包。
