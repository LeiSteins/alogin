# Alogin

校园网自动登录工具，一键完成 Dr.COM 认证，实时显示当前 WiFi 与 IP 信息。

## 功能

- **一键登录** — 填入用户名和密码，点击按钮完成校园网认证
- **实时网络信息** — 自动显示当前连接的 WiFi 名称和设备 IP 地址
- **切换感知** — WiFi 切换后自动刷新名称和 IP，无需手动操作

## 构建

```bash
./gradlew assembleDebug      # 构建 Debug APK
./gradlew test               # 运行单元测试
./gradlew installDebug       # 安装到已连接的设备
```

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **最低版本**：Android 8.0（API 26）
- **架构**：单 Activity + Compose Navigation
