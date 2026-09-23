# Карта Астаны (Overture Maps)

Источник: [Overture Maps Foundation](https://overturemaps.org), релиз `2026-08-19.0`, bbox `71.20,50.98,71.75,51.30`.
Пересобрать: `pip install overturemaps duckdb && python scripts/overture_astana.py`
(только обработка без скачивания: `--skip-download`).

## Файлы

| Файл | Что внутри |
|---|---|
| `districts.geojson` | Границы 5 районов. `district_id`: `esil`, `almaty`, `saryarka`, `baikonur`, `nura`, то есть те же районы, что в `docs/Датасет районов.docx` |
| `city_boundary.geojson` | Граница города |
| `district_stats.json` / `.csv` | Статистика по районам: площадь, здания, школы, детсады, больницы, поликлиники, остановки, светофоры, переходы, фонари, парки и зелень (км²), дороги (км по классам) |
| `pois.geojson` | Точки с полями `kind` (school, kindergarten, hospital, clinic, bus_stop, traffic_signals, crossing, street_lamp) и `district_id` |
| `parks.geojson` | Полигоны парков и скверов |
| `roads_main.geojson` | Магистрали (trunk, primary, secondary, tertiary) и ЛРТ |
| `raw/*.parquet` | Сырые слои Overture в формате GeoParquet: building (124k), segment, connector, place (8.3k), land_use, land_cover, water, infrastructure, division* |

## Оговорки

- Данные собраны из OSM и других открытых источников, поэтому они неполные. Их можно использовать для карты и
  как ориентир, но это не официальная статистика. Индексы 0–100 в симуляторе берутся из синтетического датасета.
- Район Сарайшык (выделен в 2022 году) в Overture хранится как `locality` и не входит в 5 районов датасета.
- Объект относится к району, если его центроид или середина сегмента попадает в полигон района.

## Лицензия / атрибуция

© OpenStreetMap contributors, Overture Maps Foundation. Слои building, segment и land_use распространяются по ODbL,
place — по CDLA-Permissive-2.0. На карте в UI нужна атрибуция.
