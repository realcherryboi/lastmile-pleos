#!/usr/bin/env python3
"""정밀도로지도(국토지리정보원) 노면선표시(B2_SURFACELINEMARK)에서 '정문 앞 차선'을 읽는다.

사용:
  python scripts/hdmap_curb.py <extract_dir> <lat> <lng> [반경 m]
      → 좌표에서 가장 가까운 황색 규제선(주정차 관련)과 CurbLine 키(white_solid / yellow_dashed / yellow_solid / yellow_double)
  python scripts/hdmap_curb.py <extract_dir> --export out.json
      → 황색선(Kind 515·505 등 주정차 규제 계열)만 WGS84 좌표로 묶은 JSON (앱 내장용, 선마다 type·kind·좌표열)

extract_dir: 정밀도로지도 zip에서 꺼낸 *B2_SURFACELINEMARK.{shp,dbf,prj,cpg} 들이 들어 있는 폴더 (UTMK 정표고 버전 기준)
필요 패키지: pyshp, pyproj

코드 체계 (2026-11-07 서울특별시_마포구 패키지 실측으로 확인):
  Type 3자리 = 색(1 황색, 2 백색) + 선형(1 실선, 2 점선) + 겹(1 단선, 2 복선)
      111 황색 실선 · 112 황색 복선 · 121 황색 점선 · 122 황색 점선 겹선 · 211 백색 실선 · 212 백색 점선
  Kind  = 노면표시 일련번호: 501 중앙선 · 502 유턴구역선 · 503 차선 · 505 길가장자리구역선 · 506 진로변경제한선 · 515 주정차 규제선(황색) · 525 유도선 · 530 정지선 · 531 안전지대
  → 도로교통법 시행규칙 별표 6: 황색 점선 = 주차 금지·5분 정차 가능, 황색 실선 = 주·정차 금지(표지 허용 시간 예외), 황색 복선 = 주·정차 절대 금지, 흰 실선(길가장자리구역선) = 주·정차 가능
"""
import glob, json, math, os, sys

import shapefile
from pyproj import Transformer

TO_WGS = Transformer.from_crs("EPSG:5179", "EPSG:4326", always_xy=True)
TO_UTMK = Transformer.from_crs("EPSG:4326", "EPSG:5179", always_xy=True)
CURB_KINDS = {"515", "505", "516"}   # 주정차 규제선 · 길가장자리구역선 (516은 패키지에 따라 있을 수 있음)

def curb_key(t: str) -> str:
    if t.startswith("2"): return "white_solid" if t[1:2] == "1" else "white_dashed"
    if t == "112" or t == "122": return "yellow_double"
    if t[1:2] == "2": return "yellow_dashed"
    return "yellow_solid"

LABEL = {"white_solid": "흰색 실선 · 주·정차 가능", "white_dashed": "흰색 점선", "yellow_dashed": "황색 점선 · 5분 정차 가능, 주차 금지",
         "yellow_solid": "황색 실선 · 주·정차 금지 (표지 허용 시간 예외)", "yellow_double": "황색 복선 · 주·정차 절대 금지"}

def layers(extract_dir):
    for shp in sorted(glob.glob(os.path.join(extract_dir, "*UTMK*B2_SURFACELINEMARK.shp"))):
        yield shp[:-4]

def open_shp(base):
    """구간마다 .cpg 가 EUC-KR 또는 UTF-8 로 다르다 (제작사별)."""
    cpg = open(base + ".cpg").read().strip().upper() if os.path.exists(base + ".cpg") else "EUC-KR"
    enc = {"EUC-KR": "cp949", "CP949": "cp949", "UTF-8": "utf-8", "UTF8": "utf-8"}.get(cpg, "cp949")
    return shapefile.Reader(base, encoding=enc, encodingErrors="replace")

def iter_lines(extract_dir, kinds=None):
    for base in layers(extract_dir):
        sec = os.path.basename(base).split("__")[0]
        sf = open_shp(base)
        for rec, shp in zip(sf.records(), sf.shapes()):
            if kinds and rec["Kind"] not in kinds: continue
            yield sec, rec, shp.points

def nearest(extract_dir, lat, lng, radius_m=40.0):
    x, y = TO_UTMK.transform(lng, lat)
    best = {}
    for sec, rec, pts in iter_lines(extract_dir, CURB_KINDS):
        d = min(math.hypot(px - x, py - y) for px, py in pts)
        if d <= radius_m and rec["ID"] not in best: best[rec["ID"]] = (d, sec, rec["ID"], rec["Type"], rec["Kind"])   # 같은 선이 정표고·타원체고 두 벌로 있어 ID로 합친다
    return sorted(best.values())

def export(extract_dir, out):
    feats = []; seen = set()
    for sec, rec, pts in iter_lines(extract_dir, CURB_KINDS):
        if rec["ID"] in seen: continue
        seen.add(rec["ID"])
        coords = [[round(v, 6) for v in TO_WGS.transform(px, py)] for px, py in pts]
        feats.append({"id": rec["ID"], "sec": sec, "type": rec["Type"], "kind": rec["Kind"], "curb": curb_key(rec["Type"]), "pts": coords})
    json.dump({"_source": "국토지리정보원 정밀도로지도 B2_SURFACELINEMARK (공공누리 1유형), Kind 515/505 황색·가장자리선만", "lines": feats}, open(out, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    return len(feats)

if __name__ == "__main__":
    if len(sys.argv) < 3: print(__doc__); sys.exit(1)
    d = sys.argv[1]
    if sys.argv[2] == "--export":
        n = export(d, sys.argv[3]); print(f"{n} lines → {sys.argv[3]} ({os.path.getsize(sys.argv[3])//1024} KB)"); sys.exit(0)
    lat, lng = float(sys.argv[2]), float(sys.argv[3]); r = float(sys.argv[4]) if len(sys.argv) > 4 else 40.0
    hits = nearest(d, lat, lng, r)
    if not hits: print(f"반경 {r:.0f} m 안에 규제선 없음 (정밀도로지도 미구축 구간이거나 흰 실선 없는 도로)"); sys.exit(0)
    for dm, sec, i, t, k in hits[:6]: print(f"{dm:5.1f} m  {sec}  {i}  Type {t}  Kind {k}  → {LABEL[curb_key(t)]}")
    print("가장 가까운 규제선 →", curb_key(hits[0][3]))
