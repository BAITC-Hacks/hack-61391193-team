"""
Скачивает карту Астаны из Overture Maps и собирает производные данные по 5 районам.

Использование:
    pip install overturemaps duckdb
    python scripts/overture_astana.py              # скачать + обработать
    python scripts/overture_astana.py --skip-download   # только обработать уже скачанное

Результат:
    data/overture/astana/raw/*.parquet     — сырые слои Overture (GeoParquet, bbox Астаны)
    data/overture/astana/districts.geojson — границы 5 районов (коды совпадают с датасетом)
    data/overture/astana/city_boundary.geojson
    data/overture/astana/pois.geojson      — школы, детсады, больницы, поликлиники, остановки, светофоры...
    data/overture/astana/parks.geojson     — парки/скверы
    data/overture/astana/roads_main.geojson — магистрали (trunk/primary/secondary/tertiary) + ЛРТ
    data/overture/astana/district_stats.json / .csv — статистика по районам
"""
import argparse
import csv
import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import duckdb

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "overture" / "astana"
RAW = OUT / "raw"

# minx, miny, maxx, maxy — покрывает всю Астану
BBOX = "71.20,50.98,71.75,51.30"
LAYERS = [
    "division", "division_area", "division_boundary", "place", "building",
    "segment", "connector", "land_use", "land_cover", "water", "infrastructure",
]

# Название района в Overture (names.primary, каз.) -> id района в датасете
DISTRICTS = {
    "Есіл ауданы": ("esil", "Есиль"),
    "Алматы ауданы": ("almaty", "Алматы"),
    "Сарыарқа ауданы": ("saryarka", "Сарыарка"),
    "Байқоңыр ауданы": ("baikonur", "Байконур"),
    "Нұра ауданы": ("nura", "Нура"),
}

# UTM 42N — метрическая проекция для площадей и длин в Астане
UTM = "EPSG:32642"


def download_layer(layer: str) -> str:
    target = RAW / f"{layer}.parquet"
    # --no-stac: STAC-каталог последнего релиза возвращает пусто для этого bbox
    cmd = [sys.executable, "-m", "overturemaps.cli", "download", f"--bbox={BBOX}",
           "-f", "geoparquet", "-t", layer, "-o", str(target), "--no-stac"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    status = "ok" if res.returncode == 0 and target.exists() else f"FAILED: {res.stderr[-300:]}"
    return f"{layer}: {status}"


def download_all() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=len(LAYERS)) as pool:
        for line in pool.map(download_layer, LAYERS):
            print(line)
    for state in RAW.glob("*.state"):
        state.unlink()


def raw(layer: str) -> str:
    return f"read_parquet('{(RAW / f'{layer}.parquet').as_posix()}')"


def export_geojson(con, sql: str, path: Path) -> int:
    rows = con.execute(sql).fetchall()
    cols = [d[0] for d in con.description]
    features = []
    for row in rows:
        props = dict(zip(cols, row))
        geom = json.loads(props.pop("geojson"))
        features.append({"type": "Feature", "geometry": geom, "properties": props})
    path.write_text(json.dumps({"type": "FeatureCollection", "features": features},
                               ensure_ascii=False), encoding="utf-8")
    return len(features)


def build(con) -> None:
    con.execute("INSTALL spatial; LOAD spatial;")

    names = ", ".join(f"('{k}', '{v[0]}', '{v[1]}')" for k, v in DISTRICTS.items())
    con.execute(f"""
        CREATE TABLE districts AS
        SELECT m.id AS district_id, m.name_ru, d.names.primary AS name_kk, d.geometry AS geom
        FROM {raw('division_area')} d
        JOIN (VALUES {names}) m(overture_name, id, name_ru) ON d.names.primary = m.overture_name
        WHERE d.subtype = 'county' AND d.class = 'land'
    """)
    found = [r[0] for r in con.execute("SELECT district_id FROM districts").fetchall()]
    print("Районы найдены:", found)

    con.execute(f"""
        CREATE TABLE city AS
        SELECT geometry AS geom FROM {raw('division_area')}
        WHERE subtype = 'region' AND names.primary = 'Астана' LIMIT 1
    """)

    # Точки интереса, сгруппированные в понятные для симулятора категории
    con.execute(f"""
        CREATE TABLE pois AS
        WITH p AS (
            SELECT id, names.primary AS name, categories.primary AS cat,
                   ST_Centroid(geometry) AS geom, 'place' AS src
            FROM {raw('place')}
            UNION ALL
            SELECT id, names.primary, class, ST_Centroid(geometry), 'land_use'
            FROM {raw('land_use')} WHERE subtype IN ('education', 'medical')
            UNION ALL
            SELECT id, names.primary, class, geometry, 'infrastructure'
            FROM {raw('infrastructure')}
            WHERE class IN ('bus_stop', 'traffic_signals', 'crossing', 'street_lamp')
        )
        SELECT id, name, cat, src, geom,
            CASE
                WHEN cat IN ('school', 'elementary_school', 'middle_school', 'high_school') THEN 'school'
                WHEN cat IN ('preschool', 'kindergarten', 'child_care_and_day_care') THEN 'kindergarten'
                WHEN cat IN ('hospital', 'clinic') THEN 'hospital'
                WHEN cat IN ('health_and_medical', 'medical_center', 'doctor',
                             'family_practice', 'emergency_room') THEN 'clinic'
                WHEN cat IN ('bus_stop', 'traffic_signals', 'crossing', 'street_lamp') THEN cat
            END AS kind
        FROM p
    """)
    con.execute("DELETE FROM pois WHERE kind IS NULL")
    con.execute("""
        CREATE TABLE pois_d AS
        SELECT p.*, d.district_id FROM pois p
        LEFT JOIN districts d ON ST_Within(p.geom, d.geom)
    """)

    stats = {r[0]: {"district_id": r[0], "name_ru": r[1], "area_km2": round(r[2], 1)}
             for r in con.execute(f"""
                 SELECT district_id, name_ru,
                        ST_Area(ST_Transform(geom, 'EPSG:4326', '{UTM}', always_xy := true)) / 1e6
                 FROM districts""").fetchall()}

    for did, kind, n in con.execute("""
            SELECT district_id, kind, count(*) FROM pois_d
            WHERE district_id IS NOT NULL GROUP BY ALL""").fetchall():
        stats[did][f"{kind}_count"] = n

    # Здания
    for did, n, area in con.execute(f"""
            SELECT d.district_id, count(*),
                   sum(ST_Area(ST_Transform(b.geometry, 'EPSG:4326', '{UTM}', always_xy := true))) / 1e6
            FROM {raw('building')} b JOIN districts d ON ST_Within(ST_Centroid(b.geometry), d.geom)
            GROUP BY ALL""").fetchall():
        stats[did]["building_count"] = n
        stats[did]["building_footprint_km2"] = round(area, 2)

    # Зелёные зоны: парки + управляемые газоны + сады + лес из land_cover
    for did, park, green in con.execute(f"""
            WITH g AS (
                SELECT geometry, subtype = 'park' AS is_park FROM {raw('land_use')}
                WHERE subtype IN ('park', 'managed', 'horticulture') OR class = 'forest'
                UNION ALL
                SELECT geometry, false FROM {raw('land_cover')} WHERE subtype = 'forest'
            )
            SELECT d.district_id,
                   sum(CASE WHEN is_park THEN a ELSE 0 END), sum(a)
            FROM (SELECT geometry, is_park,
                         ST_Area(ST_Transform(geometry, 'EPSG:4326', '{UTM}', always_xy := true)) / 1e6 AS a
                  FROM g) g
            JOIN districts d ON ST_Within(ST_Centroid(g.geometry), d.geom)
            GROUP BY ALL""").fetchall():
        stats[did]["park_area_km2"] = round(park, 2)
        stats[did]["green_area_km2"] = round(green, 2)

    # Дороги: протяжённость по классам (сегмент относится к району по середине)
    for did, cls, km in con.execute(f"""
            SELECT d.district_id, s.class,
                   sum(ST_Length(ST_Transform(s.geometry, 'EPSG:4326', '{UTM}', always_xy := true))) / 1e3
            FROM {raw('segment')} s
            JOIN districts d ON ST_Within(ST_PointOnSurface(s.geometry), d.geom)
            WHERE s.subtype = 'road' OR s.class = 'light_rail'
            GROUP BY ALL""").fetchall():
        stats[did][f"road_km_{cls}"] = round(km, 1)

    for s in stats.values():
        s["road_km_main"] = round(sum(s.get(f"road_km_{c}", 0)
                                      for c in ("motorway", "trunk", "primary", "secondary", "tertiary")), 1)
        s["road_km_total"] = round(sum(v for k, v in s.items()
                                       if k.startswith("road_km_") and k not in ("road_km_main", "road_km_light_rail")), 1)
        s["green_share_pct"] = round(100 * s.get("green_area_km2", 0) / s["area_km2"], 1)

    result = sorted(stats.values(), key=lambda s: list(DISTRICTS.values()).index(
        next(v for v in DISTRICTS.values() if v[0] == s["district_id"])))
    (OUT / "district_stats.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    keys = sorted({k for s in result for k in s}, key=lambda k: (k not in ("district_id", "name_ru", "area_km2"), k))
    with open(OUT / "district_stats.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(result)

    n = export_geojson(con, "SELECT district_id, name_ru, name_kk, ST_AsGeoJSON(geom) AS geojson FROM districts",
                       OUT / "districts.geojson")
    print("districts.geojson:", n)
    n = export_geojson(con, "SELECT 'Астана' AS name, ST_AsGeoJSON(geom) AS geojson FROM city",
                       OUT / "city_boundary.geojson")
    print("city_boundary.geojson:", n)
    n = export_geojson(con, """
        SELECT id, name, kind, cat AS category, src AS source, district_id, ST_AsGeoJSON(geom) AS geojson
        FROM pois_d WHERE district_id IS NOT NULL""", OUT / "pois.geojson")
    print("pois.geojson:", n)
    n = export_geojson(con, f"""
        SELECT l.id, l.names.primary AS name, l.class, d.district_id, ST_AsGeoJSON(l.geometry) AS geojson
        FROM {raw('land_use')} l JOIN districts d ON ST_Within(ST_Centroid(l.geometry), d.geom)
        WHERE l.subtype = 'park'""", OUT / "parks.geojson")
    print("parks.geojson:", n)
    n = export_geojson(con, f"""
        SELECT s.id, s.names.primary AS name, s.class, d.district_id, ST_AsGeoJSON(s.geometry) AS geojson
        FROM {raw('segment')} s JOIN districts d ON ST_Within(ST_PointOnSurface(s.geometry), d.geom)
        WHERE s.class IN ('motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'light_rail')""",
                       OUT / "roads_main.geojson")
    print("roads_main.geojson:", n)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-download", action="store_true")
    args = ap.parse_args()
    if not args.skip_download:
        download_all()
    build(duckdb.connect())
    print("Готово:", OUT)


if __name__ == "__main__":
    main()
