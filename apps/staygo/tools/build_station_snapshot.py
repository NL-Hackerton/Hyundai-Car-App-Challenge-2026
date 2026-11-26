#!/usr/bin/env python3
"""Builds app/src/main/assets/stations.tsv from the public EV charger API.

Source: 한국환경공단 전기자동차 충전소 정보 OpenAPI, getChargerInfo
(https://www.data.go.kr/data/15076352/openapi.do). The service key must have this API approved
(활용신청) and is read from the DATA_GO_KR_KEY environment variable; it is never written to the output.

Output: `#asOf=<today>` and one fast-charging station per line,
`latitude<TAB>longitude<TAB>maxPowerKw<TAB>fastChargerCount<TAB>name`. Chargers under 50 kW are ignored.
The snapshot holds station data only and the app looks it up on the device.
"""
import datetime
import json
import os
import sys
import urllib.parse
import urllib.request

URL = "http://apis.data.go.kr/B552584/EvCharger/getChargerInfo"
PAGE = 9999
MIN_FAST_KW = 50


def fetch(key: str, page: int) -> dict:
    query = urllib.parse.urlencode({"serviceKey": key, "numOfRows": PAGE, "pageNo": page, "dataType": "JSON"})
    with urllib.request.urlopen(f"{URL}?{query}", timeout=120) as response:
        body = response.read().decode("utf-8")
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        raise SystemExit(f"unexpected response on page {page}: {body[:300]}")


def main() -> None:
    key = os.environ.get("DATA_GO_KR_KEY")
    if not key:
        raise SystemExit("set DATA_GO_KR_KEY")
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "stations.tsv")

    stations: dict[str, dict] = {}
    page, total = 1, None
    while total is None or (page - 1) * PAGE < total:
        data = fetch(key, page)
        if "items" not in data:
            raise SystemExit(f"API error: {json.dumps(data, ensure_ascii=False)[:300]}")
        total = int(data["totalCount"])
        for item in data["items"]["item"]:
            if item.get("delYn") == "Y":
                continue
            try:
                kw = int(float(item.get("output") or 0))
                lat, lng = float(item["lat"]), float(item["lng"])
            except (TypeError, ValueError):
                continue
            if kw < MIN_FAST_KW or not (33.0 <= lat <= 39.0 and 124.0 <= lng <= 132.0):
                continue
            s = stations.setdefault(item["statId"], {"name": item["statNm"].replace("\t", " ").strip(), "lat": lat, "lng": lng, "kw": 0, "n": 0})
            s["kw"] = max(s["kw"], kw)
            s["n"] += 1
        print(f"page {page}: {len(stations)} fast stations so far (of {total} chargers)", file=sys.stderr)
        page += 1

    if not stations:
        raise SystemExit("no stations parsed; nothing written")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write(f"#asOf={datetime.date.today().isoformat()}\n")
        for s in sorted(stations.values(), key=lambda s: (s["lat"], s["lng"], s["name"])):
            f.write(f"{s['lat']:.6f}\t{s['lng']:.6f}\t{s['kw']}\t{s['n']}\t{s['name']}\n")
    print(f"wrote {len(stations)} stations to {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
