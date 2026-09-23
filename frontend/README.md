# Карта районов Астаны

Первый этап проекта: интерактивная карта шести районов с выбором по клику.

```bash
npm install
npm run dev
```

Откройте http://localhost:3000. Для проверки используйте `npm run lint` и `npm run build`.

## Вход и регистрация

Страницы `/login` и `/register` готовы к подключению авторизации. Формы отправляют JSON на `POST /api/v1/auth/login` (`email`, `password`) и `POST /api/v1/auth/register` (`name`, `email`, `password`). Запросы передают cookies через `credentials: "include"`; после успешного входа пользователь переходит на карту, после регистрации — к форме входа. Ожидается серверная сессия через cookie. Пока эти эндпоинты не реализованы в backend, формы показывают сообщение о недоступности авторизации. Доступ к карте сейчас не ограничен.

Границы сохранены в `public/data/astana-districts.geojson`. Источник — открытый слой «Районы» [ArcGIS FeatureServer](https://gis.esaulet.kz/server/rest/services/Hosted/raiony/FeatureServer/0). Файл получен однократным запросом к `/query` с `where=1=1`, `outFields=objectid,name_object,name_object_kaz`, `returnGeometry=true`, `outSR=4326`, `returnZ=false`, `returnM=false`, `f=json`, затем преобразован скриптом `scripts/convert-districts.mjs` через `@terraformer/arcgis`. Приложение при открытии использует только локальный GeoJSON; подложка загружается из OpenFreeMap.

Файлы MapLibre worker в `public/vendor/maplibre/` взяты из установленного пакета `maplibre-gl` версии 6.11.0. Они нужны для корректной загрузки worker в сборке Next.js.
