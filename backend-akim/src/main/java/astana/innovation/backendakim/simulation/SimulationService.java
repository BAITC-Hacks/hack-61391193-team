package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.DistrictDataset;

import org.springframework.stereotype.Service;
import java.math.RoundingMode;
import java.util.List;

@Service
public class SimulationService {
    private final SimulationValidator validator;
    private final ScoreCalculator calculator;
    private final SimulationExplanationService explanations;
    private final SimulationOptimizer optimizer;
    private final SimulationLlmClient llm;

    public SimulationService(SimulationValidator validator, ScoreCalculator calculator,
                             SimulationExplanationService explanations, SimulationOptimizer optimizer,
                             SimulationLlmClient llm) {
        this.validator = validator;
        this.calculator = calculator;
        this.explanations = explanations;
        this.optimizer = optimizer;
        this.llm = llm;
    }

    public SimulationResult calculate(SimulationRequest request) {
        // The final submission contains all five decisions. Validate before search or network calls.
        var selected = validator.validate(request);
        var calculation = calculator.calculate(selected);
        var optimum = optimizer.optimal();
        var best = optimum.calculation();
        var bestScore = best.summary().finalScore();
        var bestSolution = new SimulationResult.OptimalSolution("exhaustive-search", true,
                optimum.evaluatedCandidates(), optimum.request().decisions(), bestScore,
                bestScore.setScale(2, RoundingMode.HALF_UP), bestScore.subtract(best.baseline().finalScore()),
                budget(best), best.summary(), best.districts(), best.effects(), best.synergies());
        var gap = bestScore.subtract(calculation.summary().finalScore());
        var comparison = new SimulationResult.Comparison(gap, gap.signum() == 0);
        var fallback = explanations.explain(calculation, bestSolution, comparison);
        var deterministic = result(calculation, fallback, bestSolution, comparison);
        var normalized = new SimulationRequest(selected.stream()
                .map(s -> new SimulationRequest.Decision(s.measure().id(), s.districtId())).toList());
        var explanation = llm.explain(normalized, deterministic).orElse(fallback);
        return result(calculation, explanation, bestSolution, comparison);
    }

    public SimulationResult baseline() {
        var calculation = calculator.calculate(List.of());
        return result(calculation, explanations.explain(calculation), null, null);
    }

    private SimulationResult result(ScoreCalculator.Calculation calculation, SimulationResult.Explanation explanation,
                                    SimulationResult.OptimalSolution bestSolution, SimulationResult.Comparison comparison) {
        var score = calculation.summary().finalScore();
        var baseline = calculation.baseline().finalScore();
        return new SimulationResult("Astana Quality of Life Score", DistrictDataset.MODEL_VERSION, score,
                score.setScale(2, RoundingMode.HALF_UP), baseline, score.subtract(baseline),
                budget(calculation),
                SimulationRules.HORIZON, calculation.baseline(), calculation.summary(), calculation.districts(),
                calculation.effects(), calculation.synergies(), explanation, bestSolution, comparison);
    }

    private SimulationResult.Budget budget(ScoreCalculator.Calculation calculation) {
        return new SimulationResult.Budget(SimulationRules.BUDGET, calculation.spent(), SimulationRules.BUDGET - calculation.spent());
    }
}
