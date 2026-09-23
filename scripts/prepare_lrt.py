"""Extract the existing Overture light-rail geometry for simulation measure M3.

Run ``python scripts/prepare_lrt.py`` after updating roads_main.geojson. This
stdlib-only script preserves the source geometries; it does not infer stations,
connect missing segments, or present the extract as a complete LRT route.
"""

import argparse
from collections import Counter
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DATA_DIRECTORY = ROOT / "data1" / "overture" / "astana"
SOURCE_RELEASE = "2026-08-19.0"
EARTH_RADIUS_KM = 6371.0088


def segment_length_km(start: list[float], end: list[float]) -> float:
    """Approximate surface distance using haversine on a mean-radius sphere."""
    lon1, lat1 = map(math.radians, start[:2])
    lon2, lat2 = map(math.radians, end[:2])
    haversine = (math.sin((lat2 - lat1) / 2) ** 2
                 + math.cos(lat1) * math.cos(lat2)
                 * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(min(1.0, haversine)))


def prepare_lrt(source: Path = DATA_DIRECTORY / "roads_main.geojson",
                destination: Path = DATA_DIRECTORY / "lrt.geojson",
                release: str = SOURCE_RELEASE) -> dict:
    """Write a deterministic, attributed subset and return the collection."""
    source = source.resolve()
    destination = destination.resolve()
    if source == destination:
        raise ValueError("Source and destination must be different files")
    original = json.loads(source.read_text(encoding="utf-8"))
    if original.get("type") != "FeatureCollection":
        raise ValueError("Source must be a GeoJSON FeatureCollection")

    features = []
    positions = []
    length_km = 0.0
    districts = Counter()
    for feature in original["features"]:
        properties = feature.get("properties") or {}
        if properties.get("class") != "light_rail":
            continue
        geometry = feature.get("geometry") or {}
        if geometry.get("type") != "LineString":
            raise ValueError("Expected a LineString for each light_rail feature")
        coordinates = geometry["coordinates"]
        if len(coordinates) < 2:
            raise ValueError("A light_rail LineString must have at least two positions")
        for position in coordinates:
            if (len(position) < 2 or not all(math.isfinite(value) for value in position[:2])
                    or not -180 <= position[0] <= 180 or not -90 <= position[1] <= 90):
                raise ValueError("Invalid longitude/latitude in light_rail geometry")
        length_km += sum(segment_length_km(start, end)
                         for start, end in zip(coordinates, coordinates[1:]))
        positions.extend(coordinates)
        if properties.get("district_id"):
            districts[properties["district_id"]] += 1
        features.append({**feature, "properties": {**properties, "measure_id": "M3"}})

    try:
        source_file = source.relative_to(ROOT).as_posix()
    except ValueError:
        source_file = source.name
    result = {
        "type": "FeatureCollection",
        "metadata": {
            "measure_id": "M3",
            "source": "Overture Maps",
            "release": release,
            "source_file": source_file,
            "source_url": "https://overturemaps.org/",
            "attribution": "© OpenStreetMap contributors, Overture Maps Foundation",
            "license": "ODbL-1.0",
            "feature_count": len(features),
            "geometry_length_km": round(length_km, 3),
            "length_method": "Sum of all source LineString segments; haversine, mean Earth radius 6371.0088 km",
            "district_ids": sorted(districts),
            "features_by_district": dict(sorted(districts.items())),
            "coverage": "partial",
            "contains_stations": False,
            "warning_ru": (
                "Частичные сегменты из существующей выгрузки Overture, а не полная трасса ЛРТ. "
                "Пробелы не соединены, координаты станций отсутствуют. Длина — сумма геометрий "
                "сегментов (возможны параллельные пути), а не официальная длина линии. "
                "Отсутствие сегментов в районе не означает отсутствия там ЛРТ. "
                "Слой служит географическим контекстом M3 и не меняет коэффициенты симуляции."
            ),
        },
        "features": features,
    }
    if positions:
        result["bbox"] = [min(point[0] for point in positions), min(point[1] for point in positions),
                          max(point[0] for point in positions), max(point[1] for point in positions)]
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DATA_DIRECTORY / "roads_main.geojson")
    parser.add_argument("--output", type=Path, default=DATA_DIRECTORY / "lrt.geojson")
    parser.add_argument("--release", default=SOURCE_RELEASE,
                        help="Overture release of the input file, recorded without downloading")
    args = parser.parse_args()
    result = prepare_lrt(args.source, args.output, args.release)
    print(f"{args.output}: {len(result['features'])} features, "
          f"{result['metadata']['geometry_length_km']} km of partial source geometry")


if __name__ == "__main__":
    main()
