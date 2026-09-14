# 验证器

跨平台 TOTP 客户端（Windows / Linux / macOS），Rust + Tauri 2。

下载：[GitHub Releases](https://github.com/Xunzi229/open-authenticator/releases)

- 主密码加密保险库（Argon2id + AES-256-GCM）
- 导入 Google 验证器二维码 / `otpauth` 链接
- Touch ID、Windows Hello 可选解锁
- HTTPS WebDAV 同步密文；空闲自动锁定

保险库：Windows `%APPDATA%\Authenticator\vault.enc` · macOS `~/Library/Application Support/Authenticator/vault.enc` · Linux `~/.local/share/authenticator/vault.enc`

```sh
npm install
npm run tauri dev
```

打 `v*` 标签会走 Actions 发 GitHub Release。
