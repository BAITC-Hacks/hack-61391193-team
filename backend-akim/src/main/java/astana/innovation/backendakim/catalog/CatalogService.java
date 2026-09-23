package astana.innovation.backendakim.catalog;

import lombok.Getter;
import astana.innovation.backendakim.simulation.SimulationRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final List<DistrictResponse> districts;

    private final List<String> districtIds;

    /** Standalone and pure calculation tests use the same versioned seed as PostgreSQL. */
    public CatalogService() { this(DistrictDataset.load()); }

    @Autowired
    public CatalogService(ObjectProvider<DistrictRepository> repository) {
        this(repository.getIfAvailable() == null ? DistrictDataset.load() : repository.getObject().findCurrent());
    }

    private CatalogService(List<DistrictResponse> districts) {
        this.districts = districts;
        this.districtIds = districts.stream().map(DistrictResponse::id).toList();
    }

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
                    "C1", 5, "C2", 2),
            // Synthetic municipal-service extensions; rationale and calibration: docs1/municipal-measures.md.
            measure("M15", "services", "Сервисы", "Приоритетная уборка снега и наледи", DISTRICT_SCOPE, 12, 1,
                    "T1", 3, "T2", 3, "B2", 8),
            measure("M16", "services", "Сервисы", "Строительство и очистка ливневой канализации", DISTRICT_SCOPE, 22, 3,
                    "C1", 12, "T1", 3, "B2", 4)
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
