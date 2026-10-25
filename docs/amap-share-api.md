# 实测：高德路线分享链接里有什么

> 结论：**路线分享链接确实能直接拿到“你选的那条路线 + 路线上的摄像头”**，不需要重新算路。
> 但它是高德 H5 分享页的内部接口，不是开放平台 API；只适合个人研究，不适合作为商业产品的稳定依赖。

## 链接怎么变成数据

以 `https://surl.amap.com/xxxx` 为例：

```text
短链
  → 302 到 static3.amap.com/activity/2020NewDriveSafeV5/index.html
  → 页面参数里带 urlParameter=<token>
  → 分享页调用：
       GET  https://m5.amap.com/ws/user/route/share/info?param=<token>
       GET  https://m5.amap.com/ws/user/route/share/restoration?param=<token>
```

`share/info` 给元数据：

- `status`、`naviType`（2=驾车）
- `routeDistance`
- 起终点名称/坐标
- `backSchema`（回到高德的 URI）

`share/restoration` 给真正的路线数据：

```text
data.restorationInfo.route
  ├─ paths[]                     多条候选路线
  │    ├─ distance / travel_time
  │    ├─ coords[]               整条路线的坐标点（lon/lat）
  │    ├─ segments[].links[]     begin_index/end_index、kmph、traffic_state
  │    └─ status / traffic_light_count
  ├─ facilities.cameras[]        lat/lon/type/speed/path_index/segment_index/link_index
  ├─ facilities.general[]        其它设施，部分带 speed
  ├─ roads_names[]
  └─ incident[]                  交通事件
```

实测一条 15.4km 的驾车分享：

- `paths` 有 3 条；用 `share/info.routeDistance` 选中其中一条
- 选中路线 `distance=15368`、`coords=273`、`segments=16`
- `cameras` 共 298 个，`type` 分布 `{3:159, 4:89, 0:48, 1:2}`
- 其中 `path_index` 匹配、`type=0` 且 `speed>0` 的有 20 个，限速值 80/100
- `type=0` 且带 `speed` 的最像**测速/限速摄像头**；`type=3/4` 可能是其它摄像头/监控，需要继续标定

## 这条链接不是“实时位置共享”

页面标题叫“路线实时分享”，但这个 `shareType` 是**路线分享**：

- `safeInfoV2`（实时位置/速度）对它返回 `unsupported shareType`
- `share/restoration` 是静态路线，不包含当前车速/当前位置
- 页面每 60 秒轮询 `share/info` 看状态，但不会持续更新你的 GPS 位置

所以正确用法是：

```text
分享链接 → 路线 + 摄像头（高德给）
手机 GPS → 当前速度 + 当前位置（自己算）
沿线匹配 → 到下一个摄像头的距离
```

这样既不用重新规划，也不用 SDK Key；路线就是你分享的那条。

## 摄像头距离怎么算

`cameras[]` 里有：

- `path_index`：属于哪条候选路线
- `segment_index` / `link_index`：在路线分段/链路中的位置
- `lat/lon`：摄像头坐标
- `speed`：限速（部分 type 有）

路线 `coords[]` 是按顺序的点；`segments[].links[]` 有 `begin_index/end_index` 指向 coords。可以：

1. 把当前 GPS 投影到 `coords` 折线，得到沿线进度 `s`
2. 找到摄像头所在 `link_index` 对应的沿线里程 `s_cam`
3. 距离 = `s_cam - s`（如果为负，说明已过，取下一个）

如果不想做投影，先用“当前 GPS 到摄像头坐标的直线距离”也能跑，只是弯道会偏大。

**坐标系**：分享数据里的 `lon/lat` 是高德坐标系（GCJ-02）；Android 原生 GPS 通常是 WGS-84。两者直接混用会差几百米。要么先把 GPS 转成 GCJ-02，要么用高德定位 SDK 拿 GCJ-02 坐标，再做投影。

## 限制与风险

- **未公开接口**：高德随时可能改路径/加签名/加登录；分享页改版就失效。
- **ToS/反爬**：只处理你自己分享的链接，不要批量抓取；商业产品不要依赖。
- **token 会过期**：分享状态变化、过期后接口可能不可用。
- **不是实时位置**：要实时看别人的位置/速度，需要另一种“位置共享/组队”shareType；这条链接不是。
- **路线不会自动更新**：如果你在导航中偏航，高德重新规划了，分享数据不会自动变；需要重新分享或自己重算。
- **type 枚举未完全标定**：`type=0 + speed` 先用；`type=3/4` 需要看分享页图标/更多样本确认。
- **隐私**：链接里有起终点、昵称、头像；App 只应本地处理，不要上传。

## 工具

仓库里有一个只读探针：

```bash
python3 tools/probe_amap_share.py 'https://surl.amap.com/xxxx'
```

默认隐藏地点名和坐标；`--show-names --show-coords` 才会打印。它只调用 `share/info` 和 `share/restoration`，不写入任何服务器。

## 如果要做进 App

1. `ACTION_SEND` 接收文本/链接，或让用户粘贴
2. 解析短链 302，取 `urlParameter`
3. 调 `share/info` + `share/restoration`
4. 用 `routeDistance` 选路线，解析 `coords` + `cameras`
5. 手机 GPS 做沿线匹配，算当前速度/下一个摄像头距离
6. 手机画地图；ESP32/手环只收车速、限速、摄像头距离、超速报警

这是一条“能用、但依赖内部接口”的捷径。要稳定、可发布，还是得回到高德导航 SDK 或车机版广播。
