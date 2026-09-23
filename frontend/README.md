# Карта районов Астаны

Первый этап проекта: интерактивная карта шести районов с выбором по клику.

```bash
npm install
npm run dev
```

Откройте http://localhost:3000. Для проверки используйте `npm run lint` и `npm run build`.

Границы сохранены в `public/data/astana-districts.geojson`. Источник — открытый слой «Районы» [ArcGIS FeatureServer](https://gis.esaulet.kz/server/rest/services/Hosted/raiony/FeatureServer/0). Файл получен однократным запросом к `/query` с `where=1=1`, `outFields=objectid,name_object,name_object_kaz`, `returnGeometry=true`, `outSR=4326`, `returnZ=false`, `returnM=false`, `f=json`, затем преобразован скриптом `scripts/convert-districts.mjs` через `@terraformer/arcgis`. Приложение при открытии использует только локальный GeoJSON; подложка загружается из OpenFreeMap.

Файлы MapLibre worker в `public/vendor/maplibre/` взяты из установленного пакета `maplibre-gl` версии 6.11.0. Они нужны для корректной загрузки worker в сборке Next.js.
