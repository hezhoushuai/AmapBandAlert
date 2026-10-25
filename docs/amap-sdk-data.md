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

## 路线分享 / 群组能利用到什么程度

结论：**路线分享可以利用，但只能当“起终点/途经点导入”，不能直接拿到摄像头数据；群组没有公开数据接口。**

| 功能 | 能拿到 | 拿不到 | 怎么用 |
|---|---|---|---|
| 路线分享链接 | 起点、终点、途经点、出行方式/策略（取决于链接格式） | 摄像头列表、实时距离、车速 | 解析后交给 AMap SDK 重新算路 |
| 群组/组队 | 群成员位置/目的地（仅在高德 App 内） | 任何第三方 API | 不要依赖；可在群里发路线链接，各自导入 |

### 推荐用法：分享链接当“目的地输入”

```text
高德 App 选好路线
   → 分享 / 复制链接
   → AmapBandAlert 接收 ACTION_SEND 文本
   → 解析 from/to/via（或 slat/slon/dlat/dlon）
   → AMap SDK calculateDriveRoute()
   → getCoordList() + getAllCameras()
   → 路线/摄像头/距离/车速 → 手机地图 + ESP32
```

这样用户不用在我们 App 里重新搜目的地，但**摄像头数据仍然来自我们自己的 SDK 导航会话**，不是分享链接。

### 需要兼容的链接格式

官方 URI API 的路径规划格式（最可靠）：

```text
https://uri.amap.com/navigation?from=lon,lat,name&to=lon,lat,name&via=lon,lat,name&mode=car&policy=0&callnative=1
```

注意 `from/to/via` 是 **经度,纬度,名称**，不是纬度在前。

旧版 Android URI 可能是：

```text
amapuri://route/plan/?slat=..&slon=..&sname=..&dlat=..&dlon=..&dname=..&dev=0&t=0
androidamap://navi?sourceApplication=..&poiname=..&lat=..&lon=..&dev=0&style=2
```

高德 App 实际“分享”出来的可能是短链（`s.amap.com` 之类）或网页，需要先解析跳转/页面参数；如果只是图片/二维码，就没有结构化数据。

### 关键限制

- 分享链接里的 `policy` 只是偏好，重新算路可能和高德 App 当前实际路线不完全一致；偏航/封路时会分叉。
- 如果链接只有地点名没有坐标，需要地理编码/POI 搜索补齐。
- 解析短链/网页可能受登录、反爬、页面改版影响；**优先解析官方 URI 参数**，不要把爬网页当长期方案。
- 群组的实时位置是社交数据，没有公开接口；读取它涉及隐私，不建议做。

### 实施建议

1. 给 App 加 `ACTION_SEND`（`text/plain`）接收，用户从高德“分享”到 AmapBandAlert。
2. 写一个纯 Java `RouteShareParser`，支持上面的 `uri.amap.com/navigation`、`amapuri://route/plan`、`androidamap://navi`。
3. 解析出坐标后调用 AMap SDK 算路；解析失败就提示用户手动输入终点。
4. 分享只作为入口，摄像头/距离/车速仍由 SDK 回调提供。

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
