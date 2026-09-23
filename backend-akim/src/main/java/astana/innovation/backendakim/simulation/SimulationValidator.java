package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import astana.innovation.backendakim.catalog.MeasureResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static astana.innovation.backendakim.simulation.SimulationValidationException.Violation;

@Component
public class SimulationValidator {
    private final CatalogService catalog;

    public SimulationValidator(CatalogService catalog) { this.catalog = catalog; }

    public List<SelectedMeasure> validate(SimulationRequest request) {
        List<Violation> errors = new ArrayList<>();
        if (request == null || request.decisions() == null || request.decisions().size() != SimulationRules.DECISIONS) {
            throw new SimulationValidationException(List.of(new Violation("DECISION_COUNT", "decisions",
                    "Нужно выбрать ровно 5 мероприятий")));
        }
        List<SelectedMeasure> selected = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        int cost = 0;
        for (int i = 0; i < request.decisions().size(); i++) {
            SimulationRequest.Decision decision = request.decisions().get(i);
            String field = "decisions[" + i + "]";
            if (decision == null || decision.measureId() == null || decision.measureId().isBlank()) {
                errors.add(new Violation("MEASURE_REQUIRED", field + ".measureId", "Укажите ID мероприятия"));
                continue;
            }
            MeasureResponse measure = catalog.getMeasures().stream()
                    .filter(m -> m.id().equals(decision.measureId())).findFirst().orElse(null);
            if (measure == null) {
                errors.add(new Violation("UNKNOWN_MEASURE", field + ".measureId", "Неизвестное мероприятие: " + decision.measureId()));
                continue;
            }
            if (!ids.add(measure.id())) {
                errors.add(new Violation("DUPLICATE_MEASURE", field + ".measureId", "Повтор мероприятия " + measure.id() + " запрещён"));
            }
            if ("city".equals(measure.scope())) {
                if (decision.districtId() != null) {
                    errors.add(new Violation("CITY_DISTRICT_FORBIDDEN", field + ".districtId", "Для городской меры район не указывается"));
                }
            } else if (decision.districtId() == null || decision.districtId().isBlank()) {
                errors.add(new Violation("DISTRICT_REQUIRED", field + ".districtId", "Для районной меры выберите район"));
            } else if (catalog.getDistricts().stream().noneMatch(d -> d.id().equals(decision.districtId()))) {
                errors.add(new Violation("UNKNOWN_DISTRICT", field + ".districtId", "Неизвестный район: " + decision.districtId()));
            }
            categoryCounts.merge(measure.categoryId(), 1, Integer::sum);
            cost += measure.cost();
            selected.add(new SelectedMeasure(measure, decision.districtId()));
        }
        if (cost > SimulationRules.BUDGET) {
            errors.add(new Violation("BUDGET_EXCEEDED", "decisions", "Стоимость " + cost + " превышает бюджет 100"));
        }
        categoryCounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() > SimulationRules.MAX_PER_CATEGORY) {
                errors.add(new Violation("CATEGORY_LIMIT", "decisions", "Не более 2 мероприятий направления " + entry.getKey()));
            }
        });
        for (SimulationRules.ConflictRule conflict : SimulationRules.CONFLICTS) {
            for (SelectedMeasure first : selected) {
                for (SelectedMeasure second : selected) {
                    if (first.measure().id().equals(conflict.firstMeasureId())
                            && second.measure().id().equals(conflict.secondMeasureId())
                            && (!conflict.sameDistrictOnly()
                                || first.districtId() != null && Objects.equals(first.districtId(), second.districtId()))) {
                        errors.add(new Violation("CONFLICT", "decisions", conflict.reason()));
                    }
                }
            }
        }
        if (!errors.isEmpty()) throw new SimulationValidationException(errors);
        // Stable ordering also makes explanations independent of selection order.
        selected.sort(Comparator.comparingInt(s -> Integer.parseInt(s.measure().id().substring(1))));
        return List.copyOf(selected);
    }

    public record SelectedMeasure(MeasureResponse measure, String districtId) { }
}
