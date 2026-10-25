#!/usr/bin/env python3
"""
探测高德“路线分享”链接里的路线/摄像头数据（个人研究用）。

用法：
    python3 tools/probe_amap_share.py 'https://surl.amap.com/xxxx'
    python3 tools/probe_amap_share.py 'https://surl.amap.com/xxxx' --show-names --show-coords

注意：
- 这是高德 H5 分享页使用的内部接口，不是开放平台 API；随时可能变化。
- 只处理你自己分享出来的链接，不要批量抓取。
- 默认隐藏地点名和坐标，避免把个人路线打印到日志/截图里。
"""
import argparse
import json
import ssl
import sys
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
REFERER = "https://static3.amap.com/activity/2020NewDriveSafeV5/index.html"


def fetch(url, data=None):
    headers = {"User-Agent": UA, "Referer": REFERER}
    req = urllib.request.Request(url, data=data, headers=headers)
    context = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=30, context=context) as resp:
        return resp.geturl(), resp.read()


def resolve_share(url):
    final_url, _ = fetch(url)
    query = urllib.parse.parse_qs(urllib.parse.urlparse(final_url).query)
    token = (query.get("urlParameter") or [""])[0]
    if not token:
        # 也允许直接传长链接
        query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        token = (query.get("urlParameter") or [""])[0]
    if not token:
        raise SystemExit("没有在链接里找到 urlParameter；请确认这是高德路线分享链接")
    return final_url, token


def api(path, token):
    params = urllib.parse.urlencode({"param": token})
    url = "https://m5.amap.com" + path + "?" + params
    _, raw = fetch(url)
    return json.loads(raw.decode("utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("--show-names", action="store_true")
    parser.add_argument("--show-coords", action="store_true")
    args = parser.parse_args()

    final_url, token = resolve_share(args.url)
    print("final:", urllib.parse.urlparse(final_url).netloc +
          urllib.parse.urlparse(final_url).path)
    print("token length:", len(token))

    info = api("/ws/user/route/share/info", token)
    if str(info.get("code")) != "1":
        print("info error:", info)
        return 1
    d = info.get("data") or {}
    print("\n[share info]")
    print("  status:", d.get("status"), " naviType:", d.get("naviType"),
          " routeDistance:", d.get("routeDistance"))
    if args.show_names:
        print("  start:", d.get("startPointName"), " end:", d.get("endPointName"))
    else:
        print("  start/end: <hidden> (--show-names to print)")

    rest = api("/ws/user/route/share/restoration", token)
    if str(rest.get("code")) != "1":
        print("restoration error:", rest)
        return 1
    route = ((rest.get("data") or {}).get("restorationInfo") or {}).get("route") or {}
    paths = route.get("paths") or []
    print("\n[restoration]")
    print("  paths:", len(paths), " cameras:", len((route.get("facilities") or {}).get("cameras") or []))

    if not paths:
        print("  no paths")
        return 0

    # 用 share info 的 routeDistance 选中的路线，避免硬编码 path 0
    route_distance = d.get("routeDistance") or 0
    p = min(paths, key=lambda x: abs((x.get("distance") or 0) - route_distance))
    selected_index = p.get("path_index", paths.index(p))
    coords = p.get("coords") or []
    segs = p.get("segments") or []
    print("  selected path:", selected_index,
          " distance", p.get("distance"),
          " travel_time", p.get("travel_time"),
          " coords", len(coords),
          " segments", len(segs))
    if args.show_coords and coords:
        print("  first coord:", coords[0], " last coord:", coords[-1])

    cams = (route.get("facilities") or {}).get("cameras") or []
    from collections import Counter
    print("  camera types:", dict(Counter(c.get("type") for c in cams)))
    speed_cams = [c for c in cams
                  if c.get("path_index") == selected_index and c.get("type") == 0
                  and isinstance(c.get("speed"), (int, float)) and c["speed"] > 0]
    print(f"  path{selected_index} type0 speed cameras:", len(speed_cams),
          " limits:", sorted({c["speed"] for c in speed_cams}))
    for c in speed_cams[:8]:
        loc = f"({c.get('lat')},{c.get('lon')})" if args.show_coords else "<hidden>"
        print(f"    speed={c.get('speed')} type={c.get('type')} "
              f"path={c.get('path_index')} seg={c.get('segment_index')} "
              f"link={c.get('link_index')} {loc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
