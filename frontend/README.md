# Карта районов Астаны

Карта районов Астаны с показателями и расчётом сценария через backend.

Для запуска всей платформы из корня репозитория выполните `docker compose --env-file .env.example up -d --build` и откройте http://localhost:3000. Порт интерфейса задаётся через `FRONTEND_PORT` в Compose.

```bash
npm install
npm run dev
```

Для входа и регистрации запустите backend с PostgreSQL: из корня репозитория `docker compose --env-file .env.example up -d --build backend-akim`. Контейнерный API доступен на порту 8081, поэтому запустите frontend с `BACKEND_ORIGIN=http://127.0.0.1:8081 npm run dev` и откройте http://localhost:3000. Если backend запущен локально с профилем `postgres` на порту 8080, `BACKEND_ORIGIN` можно не задавать. При сборке для другого адреса задайте переменную до `npm run build`. Без профиля `postgres` формы получают от backend `503`. Для проверки frontend используйте `npm run lint` и `npm run build`.

Карта отображает границы районов из backend через `GET /api/v1/map/districts`. При выборе района она запрашивает `GET /api/v1/districts/{id}` и показывает `baselineScore` района. Итоговый Score города загружается отдельно из `GET /api/v1/simulation/baseline`.

## Вход и регистрация

Формы `/login` и `/register` отправляют данные через серверные маршруты Next.js в `POST /api/v1/auth/login` и `POST /api/v1/auth/register`. Регистрация передаёт `email`, `password`, `username`. Backend возвращает JWT; Next.js сохраняет его в `HttpOnly` cookie и не отдаёт токен браузерному коду. Маршрут `/api/session/me` проверяет профиль через backend `GET /api/v1/auth/me`, а кнопка «Выйти» удаляет cookie. После успешного входа или регистрации пользователь попадает на карту. Backend не реализует отзыв JWT: выход удаляет только локальную cookie, а сам токен действителен до истечения срока. Карта и расчёт остаются публичными.

Подложка загружается из OpenFreeMap; границы районов модели и числовые оценки — из backend.

Файлы MapLibre worker в `public/vendor/maplibre/` взяты из установленного пакета `maplibre-gl` версии 6.11.0. Они нужны для корректной загрузки worker в сборке Next.js.
