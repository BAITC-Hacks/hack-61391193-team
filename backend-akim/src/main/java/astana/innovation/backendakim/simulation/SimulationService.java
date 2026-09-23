package astana.innovation.backendakim.simulation;

import org.springframework.stereotype.Service;
import java.math.RoundingMode;
import java.util.List;

@Service
public class SimulationService {
    private final SimulationValidator validator;
    private final ScoreCalculator calculator;
    private final SimulationExplanationService explanations;

    public SimulationService(SimulationValidator validator, ScoreCalculator calculator, SimulationExplanationService explanations) {
        this.validator = validator;
        this.calculator = calculator;
        this.explanations = explanations;
    }

    public SimulationResult calculate(SimulationRequest request) {
        return result(calculator.calculate(validator.validate(request)));
    }

    public SimulationResult baseline() {
        return result(calculator.calculate(List.of()));
    }

    private SimulationResult result(ScoreCalculator.Calculation calculation) {
        var score = calculation.summary().finalScore();
        var baseline = calculation.baseline().finalScore();
        return new SimulationResult("Astana Quality of Life Score", "v1", score,
                score.setScale(2, RoundingMode.HALF_UP), baseline, score.subtract(baseline),
                new SimulationResult.Budget(SimulationRules.BUDGET, calculation.spent(), SimulationRules.BUDGET - calculation.spent()),
                SimulationRules.HORIZON, calculation.baseline(), calculation.summary(), calculation.districts(),
                calculation.effects(), calculation.synergies(), explanations.explain(calculation));
    }
}
