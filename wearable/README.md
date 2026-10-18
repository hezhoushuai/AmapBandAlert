# 手环端（P1 原型，尚未完成）

当前 **0.3.0 不包含可用的手环快应用**。手环提醒走系统通知转发：

`AmapBandAlert 通知 → 小米运动健康转发 → 手环震动/显示`

本目录只保留 P1 的页面原型，用于后续接入：

- 小米穿戴互联 SDK（XMS）手机端 `launchWearApp` / `sendMessage`
- Vela `@system.interconnect` 的 `onmessage` / `send`
- 手机 APK 与 RPK 使用相同的包名 `com.amapbandalert` 和同一套签名证书

当前 `src/` 下的页面没有接入互联通道，**不能构建成可用 RPK，也不要在手环上安装**。P1 的协议、签名和安装链路参考 [wrist-navigation](https://github.com/Gu-Mie/wrist-navigation)。
