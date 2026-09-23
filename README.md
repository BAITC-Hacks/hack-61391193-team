# «Аким на 5 часов»

Hackathon team repository for «Алем жив».

AI-симулятор помогает распределить единый виртуальный бюджет между городскими инициативами, выбрать пять управленческих решений и оценить их влияние на качество жизни в шести районах Астаны, включая Сарайшык (`saraishyk`). Текущая расчётная модель — `v2-saraishyk`; адреса API сохраняют префикс `/api/v1`.

## Что отдаёт backend

Backend рассчитывает **Astana Quality of Life Score**, проверяет выбранные решения, формирует объяснение на русском и предоставляет справочники и геоданные для карты. По умолчанию симулятор работает без БД; профиль `postgres` подключает PostgreSQL. API-ключ LLM не требуется. Базовый адрес локального API:

```text
http://localhost:8080/api/v1
```

Frontend на Next.js проксирует запросы к backend. При запуске backend на другом порту задайте адрес до запуска frontend:

```env
BACKEND_ORIGIN=http://127.0.0.1:8081
```

Для запуска Java-backend из IDE на порту 8080 переменная не требуется. Вход и регистрация доступны при запуске backend с профилем `postgres` и PostgreSQL; подробности для frontend — в [frontend/README.md](frontend/README.md).

### Backend и PostgreSQL в Docker Desktop

Из корня репозитория, при запущенном Docker Desktop:

```powershell
docker compose --env-file .env.example up -d --build backend-akim
```

Команда собирает Java-backend и поднимает только `backend-akim` и зависимый `postgres`. В Docker Desktop они появятся в группе `hack-61391193`. Первый запуск скачивает образы и Maven-зависимости. Контейнер backend ожидает готовности PostgreSQL и использует профиль `postgres`.

- [Проверка соединения с PostgreSQL](http://localhost:8081/actuator/health/db) — при успешном подключении `{"status":"UP"}`; при недоступной БД — HTTP 503 и `DOWN`.
- [Общее состояние backend](http://localhost:8081/actuator/health).
- [Swagger контейнерного backend](http://localhost:8081/swagger-ui/index.html).

В Docker Desktop у обоих контейнеров должен появиться статус `healthy`. Проверка здоровья backend обращается именно к состоянию соединения с БД. Docker-версия API доступна на `http://localhost:8081/api/v1`; порт `8080` остаётся для запуска из IDE. Внешний порт Docker меняется через `BACKEND_PORT`.

Для собственного локального конфига можно скопировать `.env.example` в `.env` и использовать `docker compose up -d --build backend-akim`. Не перезаписывайте существующий `.env`. Примерные реквизиты предназначены для локальной разработки.

При запуске профиля `postgres` Flyway создаёт схему `akim` с пользователями, историей сценариев и каталогом шести районов. Миграция V3 заполняет `akim.districts`; backend читает каталог из БД при старте. Без PostgreSQL используются те же данные из `catalog/districts-v2.json`. Выбор пользователя и полный результат сохраняются вместе; при чтении история не пересчитывается, включая прежние снимки модели `v1`. Подробнее: [регистрация и авторизация](Readme-backend.md#регистрация-и-авторизация), [сохранение и история](Readme-backend.md#сохранение-и-история), [проверка PostgreSQL в Docker Desktop](Readme-backend.md#postgresql-и-docker-desktop).

### Регистрация и вход через JWT

| Метод | URL | Данные запроса / назначение |
|---|---|---|
| `POST` | `/api/v1/auth/register` | JSON: `email`, `password`, `username`; создаёт пользователя и возвращает JWT (`201`) |
| `POST` | `/api/v1/auth/login` | JSON: `email`, `password`; возвращает новый JWT (`200`) |
| `GET` | `/api/v1/auth/me` | Текущий пользователь по JWT |
| `GET` | `/api/v1/admin/me` | Проверка доступа администратора по JWT; обычному пользователю — `403` |

Локальный администратор создаётся при первом запуске с PostgreSQL: email **`admin@example.com`**, пароль **`Admin`**, username `admin`. Для входа отправьте в `/api/v1/auth/login`:

```json
{"email":"admin@example.com","password":"Admin"}
```

Для регистрации нового пользователя:

```json
{"email":"user@example.com","password":"UserPass123!","username":"user"}
```

Оба ответа содержат `accessToken`, `tokenType: "Bearer"`, `expiresIn` (по умолчанию 3600 секунд) и `user` с `id`, `email`, `username`, `role`, `createdAt`. Передавайте JWT в `Authorization: Bearer <accessToken>`. Новый вход возвращает доступ к прежней истории того же аккаунта. Регистрация разрешает только роль `USER`; `ADMIN` назначается сервером. Пароли хранятся как BCrypt-хеши.

В `.env.example` уже есть локальный `JWT_SECRET`. Если используется ранее созданный `.env`, добавьте туда `JWT_SECRET` и при необходимости `JWT_TTL_SECONDS`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `ADMIN_USERNAME`. Секрет — Base64 от минимум 32 случайных байт; он должен оставаться одинаковым при перезапусках. Для размещения задайте собственные секрет и пароль администратора. `ADMIN_*` создают отсутствующего администратора и не меняют пароль существующего аккаунта. [Все ограничения и настройки](Readme-backend.md#регистрация-и-авторизация).

### Сохранить сценарий и посмотреть историю

В [Swagger контейнера](http://localhost:8081/swagger-ui/index.html):

1. В разделе **Auth** выполните `POST /api/v1/auth/register` или `/api/v1/auth/login`. Для администратора используйте реквизиты выше.
2. Нажмите **Authorize**, в поле **BearerAuth** вставьте только `accessToken` и подтвердите. Swagger сам добавит `Authorization: Bearer ...`.
3. Выполните `POST /api/v1/simulations` с готовым примером пяти решений. Ответ `201` содержит `id`, `createdAt`, исходный `request` и полный `result` со Score и объяснением.
4. Вызовите `GET /api/v1/simulations` для списка или `GET /api/v1/simulations/{id}` для полного сохранённого результата.

Когда JWT истечёт, повторите вход и замените токен; история останется у того же аккаунта. Старый `/api/v1/users/anonymous` оставлен для совместимости, его токен вводится в **AnonymousBearer**; перенос анонимной истории в зарегистрированный аккаунт пока не реализован. `/api/v1/simulation/calculate` по-прежнему только считает, без сохранения. Без профиля `postgres` маршруты регистрации, входа и истории возвращают `503`. Сравнение с лучшим набором и итоговое объяснение сохраняются в `result` вместе с расчётом пользователя.

### Попробовать в Swagger UI

После запуска откройте **[Swagger UI](http://localhost:8080/swagger-ui/index.html)**. В разделе `Simulation` выберите `POST /api/simulation/calculate`, нажмите **Try it out → Execute**. Пример из конкурсного датасета уже заполнен: 5 мероприятий, бюджет 95; на модели шести районов итог **56.31781049** (для интерфейса **56.32**).

OpenAPI генерируется из работающего кода:

- [JSON](http://localhost:8080/v3/api-docs)
- [YAML](http://localhost:8080/v3/api-docs.yaml)

В репозитории также есть [`docs1/openapi.yaml`](docs1/openapi.yaml) — экспорт схемы для импорта в Postman или Swagger Editor. После изменения API обновите его при запущенном backend из корня репозитория:

```bash
curl http://localhost:8080/v3/api-docs.yaml --output docs1/openapi.yaml
```

Запрос сохранён в [`docs1/simulation-example.json`](docs1/simulation-example.json). Отдельный пример с мероприятием в Сарайшыке: [`docs1/simulation-saraishyk-example.json`](docs1/simulation-saraishyk-example.json); в Swagger для `calculate` выберите пример **«Школа в Сарайшыке: 95 единиц → 54.95216719»**. Число районов увеличилось до шести; выбирать по-прежнему нужно ровно пять мероприятий.

### Расчёт симуляции

| Метод | URL | Назначение |
|---|---|---|
| `POST` | `/api/simulation/calculate` | Проверить ровно 5 решений и рассчитать результат |
| `POST` | `/api/v1/simulation/calculate` | Тот же расчёт с существующим префиксом frontend |
| `GET` | `/api/simulation/baseline` | Полный исходный результат без действий |
| `GET` | `/api/v1/simulation/baseline` | Тот же исходный результат с префиксом v1 |

Тело запроса:

```json
{
  "decisions": [
    { "measureId": "M7", "districtId": "nura" },
    { "measureId": "M8", "districtId": "nura" },
    { "measureId": "M10", "districtId": "nura" },
    { "measureId": "M12" },
    { "measureId": "M5", "districtId": "saryarka" }
  ]
}
```

Для районной меры `districtId` обязателен; для городской его нужно опустить или передать `null`. ID должны точно совпадать с каталогом. Одну меру нельзя повторять даже в разных районах. Порядок решений не влияет на результат.

Выполнить из корня репозитория (на Windows используйте `curl.exe`):

```bash
curl -X POST http://localhost:8080/api/simulation/calculate -H "Content-Type: application/json" --data-binary @docs1/simulation-example.json
```

Основные поля ответа на этот пример (полный ответ дополнительно содержит детализацию):

```json
{
  "metricName": "Astana Quality of Life Score",
  "modelVersion": "v2-saraishyk",
  "finalScore": 56.31781049,
  "displayScore": 56.32,
  "baselineScore": 52.33242049,
  "scoreDelta": 3.98539,
  "budget": { "limit": 100, "spent": 95, "remaining": 5 },
  "horizonQuarters": 8
}
```

Для карточки итогового результата используйте `displayScore`; для сравнения сценариев — точные `finalScore` и `scoreDelta`. `summary` содержит `dAvg`, `dMin`, самый слабый район, `nCrit`, список критических показателей и слагаемые итоговой формулы. `districts` содержит показатели и баллы до/после и их дельты. `measureEffects` показывает эффекты каждой меры с учётом лага; `synergies` — отдельные фиксированные бонусы. Прямой вклад меры в балл района указан **до clip**; его нельзя считать вкладом в нелинейный итоговый Score.

`explanation` содержит русское резюме, сильные стороны, риски и рекомендации. По умолчанию `source = "template"`: объяснение строится из рассчитанных чисел. При настройке LLM `source = "llm"`, резюме пишет модель, а структурированные сильные стороны, риски и рекомендации остаются основанными на расчёте. Числа всегда вычисляет backend.

### Лучший набор и итоговый ответ LLM

После отправки всех пяти решений оба маршрута `calculate` и сохранение через `POST /api/v1/simulations` выполняют цепочку:

```text
5 решений пользователя → валидация → точный расчёт пользователя
  → полный поиск лучшего допустимого набора → сравнение результатов
  → один запрос LLM с обоими результатами → итоговое объяснение
```

Лучшим считается набор с максимальным `finalScore` при общем бюджете 100 и горизонте 8 кварталов. Поиск рассматривает все комбинации пяти уникальных мер и все назначения районов; отсекает превышение бюджета, лимита направления и конфликты. При равном Score выбирает меньшую стоимость, затем стабильный порядок ID мер и районов. Полный набор пользователя не ограничивает поиск: это сравнение с глобальным максимумом модели. Совпадение Score означает оптимальность, даже если наборы различаются.

Новые поля ответа:

- `bestSolution`: пять решений, точный и отображаемый Score, бюджет, показатели районов, эффекты, синергии; `provenOptimal = true` и число проверенных вариантов.
- `comparison.scoreGap`: точная разница между лучшим Score и Score пользователя.
- `comparison.isOptimal`: достиг ли пользователь максимального Score.

Для неизменного каталога поиск выполняется один раз и кешируется в памяти процесса. `baseline` не запускает поиск или LLM и возвращает `bestSolution = null`, `comparison = null`. Старые сохранения без этих полей читаются без пересчёта.

Текущий максимум модели `v2-saraishyk` — **57.01147549**, бюджет **98**: M2 и M14 для города, M3, M8 и M9 в Нуре. Проверено **1 580 316** допустимых вариантов; разница с исходным примером — **0.693665**. Эти сведения возвращаются в `bestSolution` и `comparison`. Готовый запрос: [`docs1/simulation-optimal-example.json`](docs1/simulation-optimal-example.json); для повторения максимума также можно передать `bestSolution.decisions` в следующий POST.

Для подключения задайте переменные окружения backend (в Docker Compose они передаются из `.env`):

```env
SIMULATION_LLM_URL=https://your-provider.example/v1/chat/completions
SIMULATION_LLM_MODEL=your-model-id
SIMULATION_LLM_API_KEY=
SIMULATION_LLM_TIMEOUT_MS=10000
```

URL — полный адрес OpenAI-совместимого chat-completions API; ключ необязателен для локального сервера. Backend отправляет исходные решения, расчёт пользователя, лучший набор, разницу и правила модели. Инструкция просит объяснить результат на русском, используя готовые числа. Модель меняет только текст объяснения. Без URL или модели, при ошибке API или таймауте возвращаются точный результат и объяснение по шаблону. Текущий Laya `/v1/systemone` предназначен для типизированных решений и не является chat-completions endpoint.

Невалидный набор возвращает **HTTP 422** (`application/problem+json`), массив `errors` с `code`, `field`, `message` и **не содержит Score**. Проверяются ровно 5 решений, бюджет ≤100, повторы, максимум 2 меры одного направления, наличие/отсутствие района и все несовместимости. Некорректный JSON или отсутствующее тело — **HTTP 400**.

```json
{
  "title": "Невалидный сценарий",
  "status": 422,
  "detail": "Набор решений невалиден. Score не рассчитан.",
  "errors": [
    { "code": "DECISION_COUNT", "field": "decisions", "message": "Нужно выбрать ровно 5 мероприятий" }
  ]
}
```

### Справочники

| Метод | URL | Назначение |
|---|---|---|
| `GET` | `/api/v1/simulation/bootstrap` | Правила симуляции и ссылки на справочники и слои карты |
| `GET` | `/api/v1/districts` | Шесть районов с исходными синтетическими показателями и описанием происхождения данных |
| `GET` | `/api/v1/measures` | Каталог из 14 мероприятий |
| `GET` | `/api/v1/districts/{districtId}/measures` | Все 14 мероприятий в контексте выбранного района, например `nura` |
| `GET` | `/api/v1/measures/M3/context` | Датированная справка о ЛРТ Астаны с официальными источниками |

Допустимые идентификаторы районов: `esil`, `almaty`, `saryarka`, `baikonur`, `nura`, `saraishyk`. Данные Сарайшыка: `GET /api/v1/districts/saraishyk`, его мероприятия: `GET /api/v1/districts/saraishyk/measures`.

### Данные карты

| Метод | URL | Содержимое |
|---|---|---|
| `GET` | `/api/v1/map/layers` | Каталог доступных слоёв и их URL |
| `GET` | `/api/v1/map/districts` | Границы шести районов, GeoJSON `FeatureCollection` |
| `GET` | `/api/v1/map/city-boundary` | Граница города, GeoJSON `FeatureCollection` |
| `GET` | `/api/v1/map/district-stats` | Агрегированная пространственная статистика по районам |
| `GET` | `/api/v1/map/pois` | Школы, детсады, больницы, поликлиники, остановки и объекты безопасности, GeoJSON |
| `GET` | `/api/v1/map/parks` | Парки и скверы, GeoJSON |
| `GET` | `/api/v1/map/roads` | Основные дороги и ЛРТ, GeoJSON |
| `GET` | `/api/v1/map/lrt` | Отдельный слой M3: 31 исходный сегмент ЛРТ, полнота покрытия не подтверждена |

Тяжёлые слои `pois`, `parks` и `roads` frontend может загружать лениво — только после включения соответствующего слоя пользователем.

### ЛРТ для M3

В обоих каталогах мероприятий карточка M3 содержит `contextUrl` и `mapLayerUrl`. Загрузите справку
по `contextUrl`, а геометрию — по `mapLayerUrl`; `bootstrap.map.lrt` и манифест карты также содержат ссылку на слой.
Для остальных мер эти два поля равны `null`.

[Справочные данные](backend-akim/src/main/resources/measure-data/M3/lrt-context.json) проверены 23 сентября 2026 года.
Они включают маршрут, параметры первой очереди и сведения о расширении. Каждый факт связан с официальным
источником и датой; планы, объявления и сообщения об эксплуатации помечены отдельно. Источники —
[Акорда](https://mirror.akorda.kz/kz/memleket-basshysy-lrt-zhuyesin-iske-kosu-rasimine-katysty-164741),
[CTS](https://cts.gov.kz/ru/press-center/news/v-astane-proshel-press-tur-po-obektam-lrt/) и реестр экологических слушаний.
Это сохранённый справочный снимок, а не оперативное расписание.

[Геометрия](data1/overture/astana/lrt.geojson) извлечена из существующего Overture-слоя и сохраняет его координаты.
Она покрывает только часть линии, без станций и второй очереди; сумма длин сегментов не равна длине маршрута.
Пересобрать слой: `python scripts/prepare_lrt.py`. Справка не меняет конкурсную стоимость, лаг и эффекты M3
и не ограничивает выбор района по неполным геоданным. [Контракт API](Readme-backend.md#данные-лрт-для-m3).

## Запуск

Требуется JDK 21. Проверить активную версию Java:

```powershell
java -version
```

Запуск на Windows из корня репозитория:

```powershell
cd backend-akim
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21' # замените на путь к своему JDK 21
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
cd backend-akim
./mvnw spring-boot:run
```

По умолчанию сервер доступен на `http://localhost:8080`.

Для запуска собранного backend:

```powershell
cd backend-akim
.\mvnw.cmd package
& "$env:JAVA_HOME/bin/java.exe" -jar target/backend-akim-0.0.1-SNAPSHOT.jar
```

При размещении на сервере доступны переменные `PORT` (по умолчанию `8080`) и `CORS_ALLOWED_ORIGINS` (URL frontend через запятую; по умолчанию localhost и 127.0.0.1). Например, `CORS_ALLOWED_ORIGINS=https://your-frontend.example`. Геоданные включаются в JAR при сборке.

## Примеры запросов

Получить правила симуляции и ссылки на остальные API:

```bash
curl http://localhost:8080/api/v1/simulation/bootstrap
```

Получить районы и каталог мероприятий:

```bash
curl http://localhost:8080/api/v1/districts
curl http://localhost:8080/api/v1/measures
```

Получить 14 вариантов мероприятий для Нуры:

```bash
curl http://localhost:8080/api/v1/districts/nura/measures
```

Получить каталог слоёв, границы районов и статистику:

```bash
curl http://localhost:8080/api/v1/map/layers
curl http://localhost:8080/api/v1/map/districts
curl http://localhost:8080/api/v1/map/district-stats
```

Сохранить крупный GeoJSON-слой в файл:

```bash
curl http://localhost:8080/api/v1/map/pois --output pois.geojson
```

## Данные и расчёт Score

В модели `v2-saraishyk` индексы прежних пяти районов, стоимость и эффекты 14 мероприятий сохранены из конкурсного синтетического датасета `docs1/Датасет районов.docx`. Для Сарайшыка добавлены явно синтетические индексы `0–100`, исходный районный балл — `54.23`. Они не являются официальной статистикой и не вычислены переводом числа школ или площади в баллы.

Прежний модельный вес Алматы `0.24` разделён на `almaty = 0.126290` и `saraishyk = 0.113710` по соотношению планировочных оценок населения 236 000 / 212 490 на начало 2025 года. Веса остальных районов сохранены. Поле `dataProvenance` описывает методы, допущения и источники рядом с данными района. Официальные справочные сведения отделены от синтетических индексов; [источники и расчёт весов](docs1/saraishyk-research.md).

Пространственные слои из `data1/overture/astana/` нужны для карты и дополнительного контекста: границ, расположения объектов и ориентировочной статистики. Они собраны из открытых источников, могут быть неполными и **не являются официальной городской статистикой**. Поэтому количество найденных объектов нельзя напрямую превращать в индекс `0–100` или использовать вместо конкурсной формулы Score.

LLM может объяснять уже рассчитанный результат, компромиссы и риски, но не должен самостоятельно придумывать или вычислять итоговые числа.

Расчёт реализован в `simulation/ScoreCalculator.java`, отдельно от валидатора и объяснения:

```text
POST /api/simulation/calculate
  → SimulationValidator
  → ScoreCalculator
  → SimulationOptimizer + сравнение с лучшим набором
  → SimulationLlmClient (если настроен) / объяснение по шаблону
  → SimulationResult
```

Формула строго из датасета:

```text
I'[d,k] = clip(I[d,k] + Σ(fullEffect[m,k] × (8 − lag[m]) / 8) + synergy[d,k], 0, 100)
D[d] = Σ(metricWeight[k] × I'[d,k])
D_avg = Σ(populationShare[d] × D[d])
N_crit = количество пар район × показатель со значением строго < 40
Score = 0.7 × D_avg + 0.3 × min(D[d]) − N_crit
```

Синергии `M1+M2`, `M10+M12`, `M5+M6` дают фиксированные +2 в районе первой меры; лаг к бонусу не применяется. `M1+M3` запрещены во всём городе, `M4+M7` и `M5+M13` — только в одном районе. Clip применяется после суммирования всех эффектов. Бюджет и горизонт фиксированы на backend; клиент передаёт только выбранные меры и районы.

Все вычисления выполняются через `BigDecimal`, без промежуточного округления. В модели шести районов базовый Score — **52.33242049**, для прежнего примера пяти мероприятий — **56.31781049**, прирост — **3.98539**. Слабейшим исходным районом остаётся Нура (`49.18`), исходных критических показателей — два. Старые значения **52.55768** и **56.54307** относятся к конкурсной модели пяти районов `v1`; сохранённые результаты этой версии не изменяются.

`GET /api/v1/simulation/bootstrap` также возвращает веса показателей, синергии, конфликты и ссылки на расчёт и OpenAPI. Swagger UI подключён через [springdoc-openapi](https://springdoc.org/).

Подробности о составе геоданных и их пересборке: [`data1/overture/astana/README.md`](data1/overture/astana/README.md).

## Тесты

```powershell
cd backend-akim
.\mvnw.cmd test
```

## Атрибуция карты

При показе слоёв в интерфейсе обязательно отображать:

> © OpenStreetMap contributors, Overture Maps Foundation

Использован релиз Overture Maps `2026-08-19.0`. Слои `building`, `segment` и `land_use` распространяются по ODbL, слой `place` — по CDLA-Permissive-2.0.
