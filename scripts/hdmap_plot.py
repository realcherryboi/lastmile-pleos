#!/usr/bin/env python3
"""정밀도로지도 노면선표시 그림: 상수역 1번 출구(37.5477, 126.9226) 반경 170 m (docs/hdmap-sangsu-curb-lines.png).
사용: python scripts/hdmap_plot.py <B2_SURFACELINEMARK 를 구간별 폴더로 풀어 둔 폴더> <out.png>
필요 패키지: pyshp, pyproj, matplotlib. UTMK(EPSG:5179) 버전만 쓰고 ID·Type 으로 중복을 합친다.
"""
import shapefile
from pyproj import Transformer
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
ext, out = sys.argv[1], sys.argv[2]
LAT, LNG, R = 37.5477, 126.9226, 170
font_manager.fontManager.addfont("/System/Library/Fonts/AppleSDGothicNeo.ttc")
plt.rcParams["font.family"] = "Apple SD Gothic Neo"; plt.rcParams["axes.unicode_minus"] = False
to5179 = Transformer.from_crs("EPSG:4326", "EPSG:5179", always_xy=True)
cx, cy = to5179.transform(LNG, LAT)
STY = {  # type → (label, color, width, dashes, z)
 "212": ("백색 점선", "#a0a0a0", 1.0, (4, 3), 1), "211": ("백색 실선", "#707070", 1.2, None, 2),
 "121": ("황색 점선 · 5분 정차", "#e6a700", 2.6, (6, 4), 3), "111": ("황색 실선 · 주·정차 금지", "#e6a700", 2.6, None, 3),
 "112": ("황색 복선 · 절대 금지", "#c0392b", 3.6, None, 4), "122": ("황색 복선 · 절대 금지", "#c0392b", 3.6, None, 4)}
seen=set(); used=set(); fig, ax = plt.subplots(figsize=(11.5, 9), dpi=110)
nearest=(1e9,None)
for shp in glob.glob(os.path.join(ext, "*", "B2_SURFACELINEMARK.shp")):
    folder=os.path.dirname(shp)
    try: r = shapefile.Reader(shp, encodingErrors="replace")
    except Exception: continue
    prj=open(os.path.join(folder,"B2_SURFACELINEMARK.prj"),errors="ignore").read()
    if "UTM zone 52" in prj or "UTM_Zone_52" in prj or "52N" in prj: continue   # UTMK(5179) 만
    names=[f[0] for f in r.fields[1:]]
    for sr in r.iterShapeRecords():
        rec=dict(zip(names, sr.record)); t=str(rec.get("Type","")).strip(); key=(str(rec.get("ID","")), t)
        if key in seen: continue
        seen.add(key)
        pts=[(x-cx, y-cy) for x,y,*_ in sr.shape.points]
        if not pts or all(abs(x)>R*1.3 or abs(y)>R*1.3 for x,y in pts): continue
        st=STY.get(t)
        if not st: continue
        lab,col,w,dash,z=st
        xs,ys=zip(*pts)
        ln,=ax.plot(xs,ys,color=col,lw=w,zorder=z,label=lab if lab not in used else None,solid_capstyle="butt")
        if dash: ln.set_dashes(dash)
        used.add(lab)
        if t in ("111","112","121","122"):
            for x,y in pts:
                dd=(x*x+y*y)**0.5
                if dd<nearest[0]: nearest=(dd,t)
ax.scatter([0],[0],s=160,color="#1f6fff",zorder=6,label="상수역 1번 출구(근사)")
ax.set_xlim(-R,R); ax.set_ylim(-R,R); ax.set_aspect("equal"); ax.grid(alpha=0.25)
ax.set_xlabel("동서 (m)"); ax.set_ylabel("남북 (m)")
ax.set_title("정밀도로지도 노면선표시 — 독막로·상수역 주변 (국토지리정보원, 서울특별시_마포구)", fontsize=13)
h,l=ax.get_legend_handles_labels(); order=sorted(range(len(l)), key=lambda i: ["백색 점선","백색 실선","황색 점선 · 5분 정차","황색 실선 · 주·정차 금지","황색 복선 · 절대 금지","상수역 1번 출구(근사)"].index(l[i]))
ax.legend([h[i] for i in order],[l[i] for i in order],loc="lower left",fontsize=10,framealpha=0.95)
ax.annotate(f"가장 가까운 황색선 {nearest[0]:.1f} m · {STY[nearest[1]][0]}", xy=(0,0), xytext=(-160,-40), fontsize=11,
            arrowprops=dict(arrowstyle="->",color="#333"), bbox=dict(boxstyle="round",fc="white",ec="#999"))
fig.tight_layout(); fig.savefig(out); print("nearest", nearest, "lines", len(seen))
