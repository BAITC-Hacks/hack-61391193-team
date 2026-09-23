# «Аким на 5 часов»

Hackathon team repository for «Алем жив».

AI-симулятор помогает распределить единый виртуальный бюджет между городскими инициативами, выбрать пять управленческих решений и оценить их влияние на качество жизни в пяти районах Астаны.

## Что отдаёт backend

Backend рассчитывает **Astana Quality of Life Score**, проверяет выбранные решения, формирует объяснение на русском и предоставляет справочники и геоданные для карты. Для работы не нужны БД или API-ключ LLM. Базовый адрес локального API:

```text
http://localhost:8080/api/v1
```

Для frontend удобно задать его через переменную окружения:

```env
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

### Попробовать в Swagger UI

После запуска откройте **[Swagger UI](http://localhost:8080/swagger-ui/index.html)**. В разделе `Simulation` выберите `POST /api/simulation/calculate`, нажмите **Try it out → Execute**. Пример из конкурсного датасета уже заполнен: 5 мероприятий, бюджет 95, итог **56.54307** (для интерфейса **56.54**).

OpenAPI генерируется из работающего кода:

- [JSON](http://localhost:8080/v3/api-docs)
- [YAML](http://localhost:8080/v3/api-docs.yaml)

В репозитории также есть [`docs1/openapi.yaml`](docs1/openapi.yaml) — экспорт схемы для импорта в Postman или Swagger Editor. После изменения API обновите его при запущенном backend из корня репозитория:

```bash
curl http://localhost:8080/v3/api-docs.yaml --output docs1/openapi.yaml
```

Запрос сохранён в [`docs1/simulation-example.json`](docs1/simulation-example.json).

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
  "modelVersion": "v1",
  "finalScore": 56.54307,
  "displayScore": 56.54,
  "baselineScore": 52.55768,
  "scoreDelta": 3.98539,
  "budget": { "limit": 100, "spent": 95, "remaining": 5 },
  "horizonQuarters": 8
}
```

Для карточки итогового результата используйте `displayScore`; для сравнения сценариев — точные `finalScore` и `scoreDelta`. `summary` содержит `dAvg`, `dMin`, самый слабый район, `nCrit`, список критических показателей и слагаемые итоговой формулы. `districts` содержит показатели и баллы до/после и их дельты. `measureEffects` показывает эффекты каждой меры с учётом лага; `synergies` — отдельные фиксированные бонусы. Прямой вклад меры в балл района указан **до clip**; его нельзя считать вкладом в нелинейный итоговый Score.

`explanation` содержит русское резюме, сильные стороны, риски и рекомендации. Сейчас `source = "template"`: объяснение строится из рассчитанных чисел без внешнего LLM. Для последующего подключения LLM ему следует передавать этот результат, сохраняя вычисление чисел на backend.

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
| `GET` | `/api/v1/districts` | Пять районов с исходными синтетическими показателями |
| `GET` | `/api/v1/measures` | Каталог из 14 мероприятий |
| `GET` | `/api/v1/districts/{districtId}/measures` | Все 14 мероприятий в контексте выбранного района, например `nura` |

Допустимые идентификаторы районов: `esil`, `almaty`, `saryarka`, `baikonur`, `nura`.

### Данные карты

| Метод | URL | Содержимое |
|---|---|---|
| `GET` | `/api/v1/map/layers` | Каталог доступных слоёв и их URL |
| `GET` | `/api/v1/map/districts` | Границы пяти районов, GeoJSON `FeatureCollection` |
| `GET` | `/api/v1/map/city-boundary` | Граница города, GeoJSON `FeatureCollection` |
| `GET` | `/api/v1/map/district-stats` | Агрегированная пространственная статистика по районам |
| `GET` | `/api/v1/map/pois` | Школы, детсады, больницы, поликлиники, остановки и объекты безопасности, GeoJSON |
| `GET` | `/api/v1/map/parks` | Парки и скверы, GeoJSON |
| `GET` | `/api/v1/map/roads` | Основные дороги и ЛРТ, GeoJSON |

Тяжёлые слои `pois`, `parks` и `roads` frontend может загружать лениво — только после включения соответствующего слоя пользователем.

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

Числовые индексы районов по направлениям `0–100`, стоимость и эффекты 14 мероприятий берутся из конкурсного синтетического датасета `docs1/Датасет районов.docx`. Именно эти данные должны использоваться для детерминированного расчёта **Astana Quality of Life Score**.

Пространственные слои из `data/overture/astana/` нужны для карты и дополнительного контекста: границ, расположения объектов и ориентировочной статистики. Они собраны из открытых источников, могут быть неполными и **не являются официальной городской статистикой**. Поэтому количество найденных объектов нельзя напрямую превращать в индекс `0–100` или использовать вместо конкурсной формулы Score.

LLM может объяснять уже рассчитанный результат, компромиссы и риски, но не должен самостоятельно придумывать или вычислять итоговые числа.

Расчёт реализован в `simulation/ScoreCalculator.java`, отдельно от валидатора и объяснения:

```text
POST /api/simulation/calculate
  → SimulationValidator
  → ScoreCalculator
  → SimulationResult + объяснение по шаблону
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

Все вычисления выполняются через `BigDecimal`, без промежуточного округления. Базовый `D_avg = 56.8624`, `D_min = 49.18`, `N_crit = 2`, поэтому точный Score = **52.55768**. В примере из документа `D_avg = 58.0776`, `D_min = 52.9625`, `N_crit = 0`: Score = **56.54307**, прирост = **3.98539**. Значения 52.56 и ≈56.5 в документе округлены.

`GET /api/v1/simulation/bootstrap` также возвращает веса показателей, синергии, конфликты и ссылки на расчёт и OpenAPI. Swagger UI подключён через [springdoc-openapi](https://springdoc.org/).

Подробности о составе геоданных и их пересборке: `data/overture/astana/README.md`.

## Тесты

```powershell
cd backend-akim
.\mvnw.cmd test
```

## Атрибуция карты

При показе слоёв в интерфейсе обязательно отображать:

> © OpenStreetMap contributors, Overture Maps Foundation

Использован релиз Overture Maps `2026-08-19.0`. Слои `building`, `segment` и `land_use` распространяются по ODbL, слой `place` — по CDLA-Permissive-2.0.
