# 用高德导航 SDK 拿“路线 + 摄像头 + 距离 + 车速”

> 目标数据：路线轨迹、路线上的限速摄像头、摄像头到我的距离、我的实时车速。
> 结论：这四样**高德导航 SDK 全部提供**；手机版高德 App 不提供给第三方。

## 为什么不能旁听手机版高德

手机版高德的通知/无障碍只能拿到“前方 200 米左转”这类文字，拿不到：

- 完整路线几何
- 摄像头坐标/类型/限速
- 精确的摄像头距离
- 导航引擎内部的车速/剩余信息

`AUTONAVI_STANDARD_BROADCAST_SEND` 是车机版协议，能拿到摄像头距离/限速/车速，但拿不到完整路线几何和全部摄像头坐标。

## SDK 数据映射

| 你要的数据 | SDK API | 说明 |
|---|---|---|
| 路线轨迹 | `AMapNaviPath.getCoordList()` | 整条路线的坐标点 |
| 路线上的全部摄像头 | `AMapNaviPath.getAllCameras()` | `List<AMapNaviCameraInfo>` |
| 摄像头类型 | `AMapNaviCameraInfo.getCameraType()` | 测速/区间/闯红灯等 |
| 摄像头限速 | `AMapNaviCameraInfo.getCameraSpeed()` | km/h，-2/-1/0 表示无数据/禁行/非限速 |
| 摄像头坐标 | `AMapNaviCameraInfo.getX()/getY()` | 用于地图打点 |
| 到摄像头距离 | `getCameraDistance()` / `updateCameraInfo()` | 实时回调里是当前位置到摄像头 |
| 实时车速 | `AMapNaviLocation.getSpeed()` | `onLocationChange` 回调，km/h |
| 转向/剩余距离 | `NaviInfo` | `getIconType()`、`getPathRetainDistance()` 等 |
| 偏航重算 | `onReCalculateRouteForYaw` | 重算后重新取 path/cameras |

官方文档：

- [AMapNaviPath](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/AMapNaviPath.html)
- [AMapNaviCameraInfo](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/AMapNaviCameraInfo.html)
- [AMapNaviLocation](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/AMapNaviLocation.html)
- [AMapNaviListener](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/AMapNaviListener.html)

## 最小流程

```text
1. AMapNavi.getInstance(context)
2. setAMapNaviListener(listener)
3. calculateDriveRoute(start, end, waypoints, strategy)
4. onCalculateRouteSuccess:
     path = AMapNavi.getNaviPath()
     route = path.getCoordList()
     cameras = path.getAllCameras()
5. startNavi(AMapNavi.GPSNaviMode)   // 或 EmulatorNaviMode 做模拟
6. onLocationChange(location):
     speed = location.getSpeed()
     current = location.getCoord()
7. updateCameraInfo(infos):
     每个 info.getCameraDistance() 就是实时距离
8. onReCalculateRouteForYaw / onCalculateRouteSuccess:
     重新取 path/cameras，更新路线
```

说明：

- `getAllCameras()` 一次性给出全路线摄像头，适合**出发前打点/建列表**。
- `updateCameraInfo()` 在导航中持续给“即将到达”的摄像头距离，适合**实时报警**。
- 如果 `getCameraDistance()` 在 `getAllCameras()` 里语义不明确，就在手机端用 `getCoordList()` 做沿线里程计算，或直接用 `updateCameraInfo()` 的实时距离。
- `NaviInfo.getCurrentSpeed()` 已废弃，车速从 `AMapNaviLocation.getSpeed()` 取。

## 代价：你的 App 要自己导航

这不是“旁听高德 App”，而是：

- 你的 App 用自己的 AMap SDK 算路 + 导航
- 需要高德开放平台 Key（绑定包名 + SHA1）
- 需要隐私合规调用（`updatePrivacyShow/updatePrivacyAgree`）
- SDK 体积较大（几十 MB）
- 可以不用 `AMapNaviView`，只注册 listener 跑“无界面导航”，语音可关；但路线/摄像头数据仍来自你自己的导航会话

如果坚持继续用手机版高德 App，同时要这四样数据，只剩：

1. **高德车机版**：有摄像头/车速/距离，但没有完整路线几何
2. **Root/Xposed hook 高德 App**：能拿到内部数据，但版本绑定、不稳定、不建议
3. **放弃完整数据**：只做通知转向提醒（现有 v0.4）

## 推荐架构

```text
手机 App（AMap SDK）
  ├─ 算路：getCoordList() + getAllCameras()
  ├─ 导航：onLocationChange().getSpeed()
  ├─ 摄像头：updateCameraInfo().getCameraDistance()
  └─ 输出 JSON
        │ BLE / Wi-Fi UDP / USB
        ▼
ESP32 显示器
  ├─ 车速（大字）
  ├─ 限速 / 摄像头类型
  ├─ 到摄像头距离
  ├─ 转向箭头
  └─ 超速/摄像头蜂鸣
```

- 地图/完整路线放手机（AMap Map SDK 或自绘）。
- ESP32 只做仪表 + 报警，不要试图在 240×240 上渲染完整地图。
- 如果 ESP32 要简易路线，手机发 `route` 简化折线 + `camera` 坐标，ESP32 画“带状路线 + 车标 + 摄像头点”。

## 建议的里程碑

1. **M1 数据探针**：只算路，打印 `coordList.size / cameras.size / 每个摄像头类型+限速+坐标`，不导航
2. **M2 实时数据**：启动无界面导航，打印 `speed / cameraDistance / turn / remain`
3. **M3 手机 UI**：地图画路线 + 摄像头 + 当前车标
4. **M4 手环/ESP32**：把 JSON 发出去，ESP32 显示 + 蜂鸣
5. **M5 真机路测**：偏航重算、区间测速、隧道 GPS、功耗

## 需要准备

- 高德开放平台账号 + Key（Android 包名 + SHA1）
- Android 定位权限、前台服务
- 测试路线（最好包含固定测速/区间测速）
- 如果是个人测试，先用 `EmulatorNaviMode` 在室内跑通数据，再上路

## 结论

你列的四个数据是**完整且正确的**，而且高德导航 SDK 正好一一对应。真正要做的不是“想办法偷高德 App 的数据”，而是**把导航数据引擎换成自己的 AMap SDK 会话**。这是唯一稳定、可维护、能上路的路线。
