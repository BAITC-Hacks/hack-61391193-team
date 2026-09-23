"""
Скачивает карту Астаны из Overture Maps и собирает производные данные по 6 районам.

Использование:
    pip install overturemaps duckdb
    python scripts/overture_astana.py              # скачать + обработать
    python scripts/overture_astana.py --skip-download   # только обработать уже скачанное

Результат:
    data1/overture/astana/raw/*.parquet     — сырые слои Overture (GeoParquet, bbox Астаны)
    data1/overture/astana/districts.geojson — границы 6 районов (коды совпадают с датасетом)
    data1/overture/astana/city_boundary.geojson
    data1/overture/astana/pois.geojson      — школы, детсады, больницы, поликлиники, остановки, светофоры...
    data1/overture/astana/parks.geojson     — парки/скверы
    data1/overture/astana/roads_main.geojson — магистрали (trunk/primary/secondary/tertiary) + ЛРТ
    data1/overture/astana/lrt.geojson       — существующие неполные сегменты ЛРТ для M3
    data1/overture/astana/district_stats.json / .csv — статистика по районам
"""
import argparse
import csv
import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import duckdb

from prepare_lrt import SOURCE_RELEASE, prepare_lrt

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data1" / "overture" / "astana"
RAW = OUT / "raw"

# Исторический bbox выгрузки не покрывает всю территорию города.
# Границы возвращаются целиком, наблюдения объектов вне bbox неполны.
BBOX = "71.20,50.98,71.75,51.30"
LAYERS = [
    "division", "division_area", "division_boundary", "place", "building",
    "segment", "connector", "land_use", "land_cover", "water", "infrastructure",
]

# Сарайшық имеет subtype locality; остальные пять районов — county.
# Классификация источника не является основанием исключать шестой район.
DISTRICTS = {
    "Есіл ауданы": ("esil", "Есиль", "Есіл", "county"),
    "Алматы ауданы": ("almaty", "Алматы", "Алматы", "county"),
    "Сарыарқа ауданы": ("saryarka", "Сарыарка", "Сарыарқа", "county"),
    "Байқоңыр ауданы": ("baikonur", "Байконур", "Байқоңыр", "county"),
    "Нұра ауданы": ("nura", "Нура", "Нұра", "county"),
    "Сарайшық ауданы": ("saraishyk", "Сарайшык", "Сарайшық", "locality"),
}

# UTM 42N — метрическая проекция для площадей и длин в Астане
UTM = "EPSG:32642"


def download_layer(layer: str) -> str:
    target = RAW / f"{layer}.parquet"
    # --no-stac: STAC-каталог последнего релиза возвращает пусто для этого bbox
    cmd = [sys.executable, "-m", "overturemaps.cli", "download", f"--bbox={BBOX}",
           "-f", "geoparquet", "-t", layer, "-o", str(target),
           "--release", SOURCE_RELEASE, "--no-stac"]
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


def export_geojson(con, sql: str, path: Path, input_feature_count: int | None = None,
                   extra_metadata: dict | None = None) -> int:
    rows = con.execute(sql).fetchall()
    cols = [d[0] for d in con.description]
    features = []
    for row in rows:
        props = dict(zip(cols, row))
        geom = json.loads(props.pop("geojson"))
        features.append({"type": "Feature", "geometry": geom, "properties": props})
    features.sort(key=lambda feature: (feature["properties"].get("district_id", ""),
                                      feature["properties"].get("id", "")))
    metadata = {"source": "Overture Maps", "source_release": SOURCE_RELEASE,
                "observation_bbox": BBOX, "feature_count": len(features)}
    metadata.update(extra_metadata or {})
    if input_feature_count is not None:
        ids = [feature["properties"]["id"] for feature in features]
        if len(ids) != len(set(ids)):
            raise ValueError(f"Repeated source feature assigned to multiple districts: {path.name}")
        metadata.update({
            "input_feature_count": input_feature_count,
            "unassigned_input_feature_count": input_feature_count - len(features),
            "assignment_method": "within_centroid_or_line_point_on_surface",
            "unassigned_note": "Объекты за пределами районов или на границах не распределяются искусственно.",
        })
    path.write_text(json.dumps({"type": "FeatureCollection", "metadata": metadata, "features": features},
                               ensure_ascii=False), encoding="utf-8")
    return len(features)


def build(con) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    try:
        con.execute("LOAD spatial")
    except duckdb.Error:
        con.execute("INSTALL spatial; LOAD spatial;")

    names = ", ".join(f"('{k}', '{v[0]}', '{v[1]}', '{v[2]}', '{v[3]}')" for k, v in DISTRICTS.items())
    con.execute(f"""
        CREATE TABLE districts AS
        SELECT m.id AS district_id, m.name_ru, m.name_kk, d.geometry AS geom,
               d.id AS source_id, d.subtype AS source_subtype,
               d.sources[1].record_id AS source_record_id,
               d.sources[1].update_time AS source_update_time
        FROM {raw('division_area')} d
        JOIN (VALUES {names}) m(overture_name, id, name_ru, name_kk, subtype)
          ON d.names.primary = m.overture_name AND d.subtype = m.subtype
        WHERE d.class = 'land'
    """)
    found = [r[0] for r in con.execute("SELECT district_id FROM districts").fetchall()]
    if len(found) != len(DISTRICTS) or set(found) != {v[0] for v in DISTRICTS.values()}:
        raise ValueError(f"Expected one boundary for every district; found {found}")
    invalid = con.execute("SELECT district_id FROM districts WHERE NOT ST_IsValid(geom)").fetchall()
    if invalid:
        raise ValueError(f"Invalid district boundaries: {invalid}")
    overlaps = con.execute(f"""
        SELECT a.district_id, b.district_id,
               ST_Area(ST_Transform(ST_Intersection(a.geom, b.geom),
                   'EPSG:4326', '{UTM}', always_xy := true)) AS overlap_m2
        FROM districts a JOIN districts b
          ON a.district_id < b.district_id AND ST_Intersects(a.geom, b.geom)
        WHERE overlap_m2 > 0.01
    """).fetchall()
    if overlaps:
        # A future stale parent boundary must not silently double-count the new district.
        raise ValueError(f"District interiors overlap; reconcile source boundaries first: {overlaps}")
    print("Районы найдены:", found)

    con.execute(f"""
        CREATE TABLE city AS
        SELECT geometry AS geom FROM {raw('division_area')}
        WHERE subtype = 'region' AND names.primary = 'Астана' LIMIT 1
    """)
    if con.execute("SELECT count(*) FROM city").fetchone()[0] != 1:
        raise ValueError("Expected one city boundary")
    boundary_areas = con.execute(f"""
        WITH g AS (SELECT ST_Union_Agg(geom) AS districts FROM districts)
        SELECT ST_Area(ST_Transform(g.districts, 'EPSG:4326', '{UTM}', always_xy := true)) / 1e6,
               ST_Area(ST_Transform(c.geom, 'EPSG:4326', '{UTM}', always_xy := true)) / 1e6,
               ST_Area(ST_Transform(ST_Difference(c.geom, g.districts),
                   'EPSG:4326', '{UTM}', always_xy := true)) / 1e6,
               ST_Area(ST_Transform(ST_Difference(g.districts, c.geom),
                   'EPSG:4326', '{UTM}', always_xy := true)) / 1e6
        FROM g CROSS JOIN city c
    """).fetchone()
    boundary_metadata = dict(zip(("district_union_area_km2", "city_area_km2",
                                 "city_area_without_district_km2", "district_area_outside_city_km2"),
                                (round(area, 3) for area in boundary_areas)))
    boundary_metadata["coverage_note"] = (
        "Город и районы — отдельные границы источника; незакрытая районами территория не распределяется искусственно."
    )

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

    xmin, ymin, xmax, ymax = (float(value) for value in BBOX.split(","))
    for did, source_id, subtype, record_id, updated_at, coverage in con.execute(f"""
            SELECT district_id, source_id, source_subtype, source_record_id, source_update_time,
                   100 * ST_Area(ST_Transform(
                       ST_Intersection(geom, ST_MakeEnvelope({xmin}, {ymin}, {xmax}, {ymax})),
                       'EPSG:4326', '{UTM}', always_xy := true))
                       / ST_Area(ST_Transform(geom, 'EPSG:4326', '{UTM}', always_xy := true))
            FROM districts""").fetchall():
        stats[did].update({
            "data_source": "Overture Maps / OpenStreetMap",
            "source_release": SOURCE_RELEASE,
            "data_quality": "open_map_observations_not_official_statistics",
            "boundary_source_id": source_id,
            "boundary_source_subtype": subtype,
            "boundary_source_record_id": record_id,
            "boundary_source_updated_at": updated_at,
            "boundary_synthetic": False,
            "observation_bbox": BBOX,
            "observation_bbox_coverage_pct": round(coverage, 2),
            "coverage_note": ("Граница района целиком внутри bbox; полнота объектов источником не гарантируется."
                              if coverage >= 99.99 else
                              "Bbox покрывает часть района; числа объектов, площади зелени и длины дорог неполны."),
            "assignment_method": "within_centroid_or_line_point_on_surface",
        })

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

    # Весь сегмент относится к району по ST_PointOnSurface, без обрезки по границе.
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

    order = {district[0]: index for index, district in enumerate(DISTRICTS.values())}
    result = sorted(stats.values(), key=lambda s: order[s["district_id"]])
    (OUT / "district_stats.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    keys = sorted({k for s in result for k in s}, key=lambda k: (k not in ("district_id", "name_ru", "area_km2"), k))
    with open(OUT / "district_stats.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        w.writerows(result)

    n = export_geojson(con, """SELECT district_id, name_ru, name_kk, source_id, source_subtype,
                              source_record_id, source_update_time, false AS boundary_synthetic,
                              ST_AsGeoJSON(geom) AS geojson FROM districts""",
                       OUT / "districts.geojson", extra_metadata=boundary_metadata)
    print("districts.geojson:", n)
    n = export_geojson(con, "SELECT 'Астана' AS name, ST_AsGeoJSON(geom) AS geojson FROM city",
                       OUT / "city_boundary.geojson", extra_metadata=boundary_metadata)
    print("city_boundary.geojson:", n)
    n = export_geojson(con, """
        SELECT id, name, kind, cat AS category, src AS source, district_id, ST_AsGeoJSON(geom) AS geojson
        FROM pois_d WHERE district_id IS NOT NULL""", OUT / "pois.geojson",
        con.execute("SELECT count(*) FROM pois").fetchone()[0])
    print("pois.geojson:", n)
    n = export_geojson(con, f"""
        SELECT l.id, l.names.primary AS name, l.class, d.district_id, ST_AsGeoJSON(l.geometry) AS geojson
        FROM {raw('land_use')} l JOIN districts d ON ST_Within(ST_Centroid(l.geometry), d.geom)
        WHERE l.subtype = 'park'""", OUT / "parks.geojson",
        con.execute(f"SELECT count(*) FROM {raw('land_use')} WHERE subtype = 'park'").fetchone()[0])
    print("parks.geojson:", n)
    n = export_geojson(con, f"""
        SELECT s.id, s.names.primary AS name, s.class, d.district_id, ST_AsGeoJSON(s.geometry) AS geojson
        FROM {raw('segment')} s JOIN districts d ON ST_Within(ST_PointOnSurface(s.geometry), d.geom)
        WHERE s.class IN ('motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'light_rail')""",
                       OUT / "roads_main.geojson", con.execute(f"""
        SELECT count(*) FROM {raw('segment')}
        WHERE class IN ('motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'light_rail')
    """).fetchone()[0])
    print("roads_main.geojson:", n)
    lrt = prepare_lrt(OUT / "roads_main.geojson", OUT / "lrt.geojson", SOURCE_RELEASE)
    print("lrt.geojson:", len(lrt["features"]), "частичных сегментов")


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
