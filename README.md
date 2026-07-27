# Alogin

一款 Android 网络认证助手，用于在已配置的目标 Wi-Fi 上完成认证，并查看网络与账号状态。

## 功能

- 一键认证：保存账号和密码后，可在目标 Wi-Fi 上发起认证。
- 认证门户适配：自动探测并使用项目已实现的认证门户流程。
- 目标 Wi-Fi 管理：支持手动添加、删除或扫描附近 Wi-Fi 后加入目标列表。
- 网络状态：显示当前连接的 Wi-Fi、设备 IP 地址和认证状态；网络切换后会自动刷新。
- 账号概览：认证成功后可查看账号、流量、余额及关联设备信息。
- 设备管理：可在服务端支持时使关联设备下线。
- 调试与更新：内置 HTTP 请求日志查看和应用内更新检查。

## 使用方法

1. 在“账号管理”中填写并保存认证账号和密码。
2. 在“设置 → 目标 Wi-Fi”中添加可使用的 Wi-Fi；也可以扫描附近网络后选择加入。
3. 连接已配置的目标 Wi-Fi，并在主页点击“登录”。
4. 认证完成后，主页会显示网络状态和可获取的账号信息。

> Android 需要位置权限才能读取 Wi-Fi 名称和扫描附近网络。仅在需要这些功能时授权即可。

## 构建与测试

环境要求：JDK 17、Android SDK，以及项目 Gradle Wrapper 所需的网络依赖。

在项目根目录执行：

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 运行本地单元测试
./gradlew test

# 在已连接的设备或模拟器上运行测试
./gradlew connectedAndroidTest

# 安装 Debug APK
./gradlew installDebug
```

Windows PowerShell 请将 `./gradlew` 替换为 `.\gradlew.bat`。

Debug APK 的默认输出位置为 `app/build/outputs/apk/debug/`。

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- 单 Activity 与 Compose Navigation
- ViewModel、StateFlow 与 Kotlin Coroutines
- OkHttp 网络请求
- 最低支持 Android 8.0（API 26）

## 项目结构

```text
app/src/main/java/top/steins/autologin/
├── data/       # 本地设置与目标 Wi-Fi 配置
├── network/    # 认证、账号服务、网络信息与更新逻辑
├── navigation/ # Compose 导航定义
└── ui/         # Compose 页面、组件与主题
```

## 隐私与安全

- 账号和密码仅保存在设备本地应用存储中；请妥善保管设备。
- HTTP 日志可用于排查认证问题，可能包含网络请求信息；排查完成后请避免向他人分享敏感内容。
- 本项目仅适用于其内置认证流程所兼容的网络环境。使用前请确认你有权使用对应的网络和账号服务。

## 许可证

本项目采用用户分段双重许可，具体条款请见 [LICENSE](LICENSE)。
