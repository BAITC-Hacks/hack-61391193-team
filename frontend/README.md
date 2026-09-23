# Карта районов Астаны

Карта районов Астаны с показателями и расчётом сценария через backend.

```bash
npm install
npm run dev
```

Для входа и регистрации запустите backend с PostgreSQL: из корня репозитория `docker compose --env-file .env.example up -d --build backend-akim`. Контейнерный API доступен на порту 8081, поэтому запустите frontend с `BACKEND_ORIGIN=http://127.0.0.1:8081 npm run dev` и откройте http://localhost:3000. Если backend запущен локально с профилем `postgres` на порту 8080, `BACKEND_ORIGIN` можно не задавать. При сборке для другого адреса задайте переменную до `npm run build`. Без профиля `postgres` формы получают от backend `503`. Для проверки frontend используйте `npm run lint` и `npm run build`.

При выборе района карта запрашивает `GET /api/v1/districts/{id}` и показывает `baselineScore` района. Итоговый Score города загружается отдельно из `GET /api/v1/simulation/baseline`. Административная карта содержит шесть районов; для района Сарайшык в текущей модели backend балл не предусмотрен. В переключателе доступен слой пяти районов модели из `GET /api/v1/map/districts`.

## Вход и регистрация

Формы `/login` и `/register` отправляют данные через серверные маршруты Next.js в `POST /api/v1/auth/login` и `POST /api/v1/auth/register`. Регистрация передаёт `email`, `password`, `username`. Backend возвращает JWT; Next.js сохраняет его в `HttpOnly` cookie и не отдаёт токен браузерному коду. Маршрут `/api/session/me` проверяет профиль через backend `GET /api/v1/auth/me`, а кнопка «Выйти» удаляет cookie. После успешного входа или регистрации пользователь попадает на карту. Backend не реализует отзыв JWT: выход удаляет только локальную cookie, а сам токен действителен до истечения срока. Карта и расчёт остаются публичными.

Административные границы сохранены в `public/data/astana-districts.geojson`. Источник — открытый слой «Районы» [ArcGIS FeatureServer](https://gis.esaulet.kz/server/rest/services/Hosted/raiony/FeatureServer/0). Файл получен однократным запросом к `/query` с `where=1=1`, `outFields=objectid,name_object,name_object_kaz`, `returnGeometry=true`, `outSR=4326`, `returnZ=false`, `returnM=false`, `f=json`, затем преобразован скриптом `scripts/convert-districts.mjs` через `@terraformer/arcgis`. Подложка загружается из OpenFreeMap; геоданные пяти районов модели и числовые оценки — из backend.

Файлы MapLibre worker в `public/vendor/maplibre/` взяты из установленного пакета `maplibre-gl` версии 6.11.0. Они нужны для корректной загрузки worker в сборке Next.js.
