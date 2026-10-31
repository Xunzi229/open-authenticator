# Android 验证器

独立的 Android 应用工程。使用 Java + Android 原生界面，最低 Android 8.0（API 26）。

## 功能

- 主密码创建、解锁、修改和空闲自动锁定
- 可在设置中开启指纹解锁或免密码进入；两种凭据仅加密保存在当前设备，修改主密码后需重新开启
- 与桌面端浅色界面一致的分组账号列表、彩色账号标识和设置分区；设置中可切换浅色 / 深色主题
- 添加、编辑、删除、搜索 TOTP 账号；支持 SHA1 / SHA256 / SHA512、6 / 8 位和自定义周期
- 主界面直接扫码添加账号；也可从图片或文件导入 `otpauth://`、Google 验证器迁移二维码、桌面端 JSON 备份
- 显示账号二维码、复制验证码，以及导出 JSON 备份
- 新增账号后自动上传到已配置的 HTTPS WebDAV；仍可手动上传与拉取，拉取前备份本地加密保险库

Android 使用与桌面端相同的 `GAE1` 保险库格式：Argon2id（32 MiB、3 次、2 路）派生密钥，AES-256-GCM 加密整库。账号和同步设置的 JSON 字段保持兼容。保险库只保存在应用私有目录；系统备份已关闭。

## 跨端同步

1. 在桌面端设置 WebDAV 地址、用户名、应用密码和远程路径，先上传。
2. Android 首次启动时点「从 WebDAV 恢复」，填写相同的 WebDAV 信息和桌面端主密码。
3. Android 通过扫码、手动添加、图片或文件导入新账号后，会立即自动上传；另一端再拉取。编辑或删除账号后仍需在设置中手动上传。

同步按整库进行，没有账号级合并。未配置 WebDAV 时新增账号只保存在本地；自动上传失败时本地账号仍会保存，并显示错误。上传会检查远程 ETag 或密文哈希；远程已变化时会报错，不会自动覆盖。此时需确认两端改动后再处理冲突。拉取会以远程账号覆盖本地账号，并在应用私有 `backups/` 目录保存原加密文件。各端必须使用相同的主密码；修改主密码后需重新同步。

WebDAV 只允许 HTTPS，或本机 `localhost` / `127.0.0.1` 的 HTTP。默认远程路径为 `/authenticator/vault.enc`。Android 的扫码在设备本地运行，不依赖 Google Play 服务。

## 开发

用 Android Studio 打开 `android/` 目录，让 IDE 安装所需的 Android SDK 35 和 Gradle 8.9，然后运行 `app`。命令行也可在该目录运行 `gradlew.bat :app:assembleDebug`（Windows）或 `sh gradlew :app:assembleDebug`（macOS / Linux）。本仓库不包含已构建安装包。

GitHub Actions 手动运行 `Cross-platform CI` 可生成 7 天有效的调试 APK。工作流从仓库 Secrets 读取固定签名密钥：`ANDROID_DEBUG_KEYSTORE_BASE64`、`ANDROID_DEBUG_STORE_PASSWORD`、`ANDROID_DEBUG_KEY_ALIAS`、`ANDROID_DEBUG_KEY_PASSWORD`。请长期备份原始 keystore 和密码；更换密钥后，旧安装包无法直接覆盖安装，须先备份应用数据。执行本地构建时如需与 CI 包互相覆盖安装，须设置同名环境变量，其中 `ANDROID_DEBUG_KEYSTORE` 指向原始 keystore 文件。

开启免密码进入后，应用启动时会使用 Android 密钥库保存的凭据解锁，空闲自动锁定在本机暂停；关闭后恢复原设置。指纹解锁需要设备录入强生物识别。Android 端没有后台自动同步。
