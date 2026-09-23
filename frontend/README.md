# Карта районов Астаны

Карта районов Астаны с показателями и расчётом сценария через backend.

```bash
npm install
npm run dev
```

Запустите backend из `backend-akim` командой `mvn spring-boot:run`, затем откройте http://localhost:3000. Next.js перенаправляет `/api/*` на `http://127.0.0.1:8080`; другой адрес задаётся через `BACKEND_ORIGIN`. Для проверки используйте `npm run lint` и `npm run build`.

При выборе района карта запрашивает `GET /api/v1/districts/{id}` и показывает `baselineScore` района. Итоговый Score города загружается отдельно из `GET /api/v1/simulation/baseline`. Административная карта содержит шесть районов; для района Сарайшык в текущей модели backend балл не предусмотрен. В переключателе доступен слой пяти районов модели из `GET /api/v1/map/districts`.

## Вход и регистрация

Страницы `/login` и `/register` готовы к подключению авторизации. Формы отправляют JSON на `POST /api/v1/auth/login` (`email`, `password`) и `POST /api/v1/auth/register` (`name`, `email`, `password`). Запросы передают cookies через `credentials: "include"`; после успешного входа пользователь переходит на карту, после регистрации — к форме входа. Ожидается серверная сессия через cookie. Пока эти эндпоинты не реализованы в backend, формы показывают сообщение о недоступности авторизации. Доступ к карте сейчас не ограничен.

Административные границы сохранены в `public/data/astana-districts.geojson`. Источник — открытый слой «Районы» [ArcGIS FeatureServer](https://gis.esaulet.kz/server/rest/services/Hosted/raiony/FeatureServer/0). Файл получен однократным запросом к `/query` с `where=1=1`, `outFields=objectid,name_object,name_object_kaz`, `returnGeometry=true`, `outSR=4326`, `returnZ=false`, `returnM=false`, `f=json`, затем преобразован скриптом `scripts/convert-districts.mjs` через `@terraformer/arcgis`. Подложка загружается из OpenFreeMap; геоданные пяти районов модели и числовые оценки — из backend.

Файлы MapLibre worker в `public/vendor/maplibre/` взяты из установленного пакета `maplibre-gl` версии 6.11.0. Они нужны для корректной загрузки worker в сборке Next.js.
