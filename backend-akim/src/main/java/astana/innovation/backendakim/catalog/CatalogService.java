package astana.innovation.backendakim.catalog;

import lombok.Getter;
import astana.innovation.backendakim.simulation.SimulationRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CatalogService {

    private static final int SIMULATION_HORIZON = SimulationRules.HORIZON;
    private static final String DISTRICT_SCOPE = "district";
    private static final String CITY_SCOPE = "city";

    @Getter
    private final List<DistrictResponse> districts = List.of(
            district("esil", "Есиль", 0.27, 62.99,
                    "T1", 45, "T2", 62, "E1", 68, "E2", 72, "S1", 48,
                    "S2", 55, "B1", 78, "B2", 60, "C1", 75, "C2", 70),
            district("almaty", "Алматы", 0.24, 57.06,
                    "T1", 40, "T2", 75, "E1", 50, "E2", 55, "S1", 60,
                    "S2", 65, "B1", 62, "B2", 52, "C1", 50, "C2", 60),
            district("saryarka", "Сарыарка", 0.20, 54.65,
                    "T1", 50, "T2", 70, "E1", 42, "E2", 40, "S1", 62,
                    "S2", 68, "B1", 58, "B2", 55, "C1", 45, "C2", 55),
            district("baikonur", "Байконур", 0.13, 56.63,
                    "T1", 52, "T2", 68, "E1", 55, "E2", 50, "S1", 58,
                    "S2", 60, "B1", 52, "B2", 58, "C1", 55, "C2", 58),
            district("nura", "Нура", 0.16, 49.18,
                    "T1", 55, "T2", 40, "E1", 45, "E2", 65, "S1", 38,
                    "S2", 35, "B1", 55, "B2", 50, "C1", 60, "C2", 50)
    );

    private final List<String> districtIds = districts.stream()
            .map(DistrictResponse::id)
            .toList();

    @Getter
    private final List<MeasureResponse> measures = List.of(
            measure("M1", "transport", "Транспорт", "Выделенные полосы для автобусов", DISTRICT_SCOPE, 18, 2,
                    "T1", 6, "T2", 9),
            measure("M2", "transport", "Транспорт", "Умные светофоры (адаптивное управление)", CITY_SCOPE, 22, 2,
                    "T1", 4, "B2", 3),
            measure("M3", "transport", "Транспорт", "Линия ЛРТ / расширение", DISTRICT_SCOPE, 30, 4,
                    "T1", 16, "T2", 20, "E2", 4),
            measure("M4", "ecology", "Экология", "Парк / сквер", DISTRICT_SCOPE, 15, 2,
                    "E1", 12, "E2", 3, "B1", 2),
            measure("M5", "ecology", "Экология", "Перевод частного сектора на чистое топливо", DISTRICT_SCOPE, 25, 3,
                    "E2", 14, "C1", 4),
            measure("M6", "ecology", "Экология", "Городская программа озеленения и ветрозащитных полос", CITY_SCOPE, 20, 4,
                    "E1", 5, "E2", 3),
            measure("M7", "social", "Соцсфера", "Школа + детсад (модульное строительство)", DISTRICT_SCOPE, 24, 3,
                    "S1", 16),
            measure("M8", "social", "Соцсфера", "Центр семейного здоровья / поликлиника", DISTRICT_SCOPE, 20, 3,
                    "S2", 14),
            measure("M9", "social", "Соцсфера", "Дворовые спорт-хабы", DISTRICT_SCOPE, 10, 1,
                    "S1", 3, "S2", 3, "B1", 3),
            measure("M10", "safety", "Безопасность", "Освещение и камеры (расширение Safe City)", DISTRICT_SCOPE, 12, 1,
                    "B1", 12, "B2", 2),
            measure("M11", "safety", "Безопасность", "Безопасные переходы и школьные зоны", DISTRICT_SCOPE, 10, 1,
                    "B2", 12, "T1", -2),
            measure("M12", "services", "Сервисы", "Единая цифровая платформа обращений", CITY_SCOPE, 14, 1,
                    "C2", 5),
            measure("M13", "services", "Сервисы", "Модернизация тепло- и водосетей", DISTRICT_SCOPE, 28, 4,
                    "C1", 18, "E2", 2),
            measure("M14", "services", "Сервисы", "Аварийные бригады ЖКХ + раннее оповещение", CITY_SCOPE, 16, 1,
                    "C1", 5, "C2", 2)
    );

    public DistrictResponse getDistrict(String id) {
        String normalizedId = id.toLowerCase(Locale.ROOT);
        return districts.stream()
                .filter(district -> district.id().equals(normalizedId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Район с id '%s' не найден".formatted(id)
                ));
    }

    public List<DistrictMeasureResponse> getMeasuresForDistrict(String districtId) {
        DistrictResponse targetDistrict = getDistrict(districtId);
        return measures.stream()
                .map(measure -> toDistrictMeasure(measure, targetDistrict.id()))
                .toList();
    }

    private DistrictMeasureResponse toDistrictMeasure(MeasureResponse measure, String districtId) {
        boolean cityWide = CITY_SCOPE.equals(measure.scope());
        return new DistrictMeasureResponse(
                measure.id(),
                measure.categoryId(),
                measure.categoryName(),
                measure.name(),
                measure.scope(),
                measure.cost(),
                measure.lagQuarters(),
                measure.realizationFactor(),
                measure.fullEffects(),
                measure.realizedEffects(),
                cityWide ? null : districtId,
                cityWide ? districtIds : List.of(districtId)
        );
    }

    private static DistrictResponse district(
            String id,
            String name,
            double populationShare,
            double baselineScore,
            Object... metricEntries
    ) {
        return new DistrictResponse(
                id,
                name,
                populationShare,
                integerMap(metricEntries),
                baselineScore
        );
    }

    private static MeasureResponse measure(
            String id,
            String categoryId,
            String categoryName,
            String name,
            String scope,
            int cost,
            int lag,
            Object... effectEntries
    ) {
        Map<String, Integer> fullEffects = integerMap(effectEntries);
        Map<String, Double> realizedEffects = new LinkedHashMap<>();
        double realizationFactor = (double) (SIMULATION_HORIZON - lag) / SIMULATION_HORIZON;
        fullEffects.forEach((metric, effect) -> realizedEffects.put(metric, effect * realizationFactor));

        return new MeasureResponse(
                id,
                categoryId,
                categoryName,
                name,
                scope,
                cost,
                lag,
                realizationFactor,
                fullEffects,
                Collections.unmodifiableMap(realizedEffects)
        );
    }

    private static Map<String, Integer> integerMap(Object... entries) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], (Integer) entries[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }
}
