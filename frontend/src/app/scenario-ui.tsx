"use client";

import type { Bootstrap, Decision, District, Measure, SimulationResult } from "./api";

const categories = [
  { name: "Транспорт", metrics: ["T1", "T2"] },
  { name: "Экология", metrics: ["E1", "E2"] },
  { name: "Соцсфера", metrics: ["S1", "S2"] },
  { name: "Безопасность", metrics: ["B1", "B2"] },
  { name: "Сервисы", metrics: ["C1", "C2"] },
];

const metricNames: Record<string, string> = {
  T1: "Дороги", T2: "Общественный транспорт", E1: "Озеленение", E2: "Воздух",
  S1: "Школы и детсады", S2: "Медицина", B1: "Безопасность улиц",
  B2: "Безопасность движения", C1: "ЖКХ", C2: "Обращения жителей",
};

function format(value: number, digits = 2) {
  return value.toLocaleString("ru-RU", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

function signed(value: number, digits = 2) {
  return `${value >= 0 ? "+" : ""}${format(value, digits)}`;
}

function districtName(id: string | undefined, districts: District[]) {
  return id ? districts.find((district) => district.id === id)?.name ?? id : "Весь город";
}

export type ScenarioSelection = {
  decision: Decision;
  measure: Measure | undefined;
  district: string;
};

/** Adapter for InitiativeModal.onSelectInitiative({ initiativeId, districtId }). */
export function decisionFromInitiative(selection: { initiativeId: string; districtId: string }, measures: Measure[]): Decision | null {
  const measure = measures.find((item) => item.id === selection.initiativeId);
  if (!measure) return null;
  if (measure.scope === "city") return { measureId: measure.id };
  return selection.districtId ? { measureId: measure.id, districtId: selection.districtId } : null;
}

export function selectedDecisions(decisions: Decision[], measures: Measure[], districts: District[]): ScenarioSelection[] {
  return decisions.map((decision) => ({
    decision,
    measure: measures.find((measure) => measure.id === decision.measureId),
    district: districtName(decision.districtId, districts),
  }));
}

type HudProps = {
  selections: ScenarioSelection[];
  requiredCount: number;
  budgetLimit: number;
  calculating: boolean;
  ready: boolean;
  errors: string[];
  onRemove: (measureId: string) => void;
  onCalculate: () => void;
};

export function ScenarioHud({ selections, requiredCount, budgetLimit, calculating, ready, errors, onRemove, onCalculate }: HudProps) {
  const spent = selections.reduce((sum, selection) => sum + (selection.measure?.cost ?? 0), 0);
  const missing = Math.max(0, requiredCount - selections.length);
  return <>
    <section className="budget-hud floating-hud" aria-label="Бюджет">
      <span className="hud-label">Бюджет</span>
      <div className="budget-hud-numbers"><strong>{spent} <small>/ {budgetLimit}</small></strong><span>Осталось <b>{budgetLimit - spent}</b></span></div>
      <div className="budget-track" role="progressbar" aria-label="Потрачено бюджета" aria-valuenow={spent} aria-valuemin={0} aria-valuemax={budgetLimit}><span style={{ width: `${budgetLimit ? Math.min(100, spent / budgetLimit * 100) : 0}%` }} /></div>
    </section>
    <section className="turns-hud floating-hud" aria-label="Ходы">
      <div className="turns-progress"><div className="turn-dots" aria-hidden="true">{Array.from({ length: requiredCount }, (_, index) => <span className={index < selections.length ? "turn-dot active" : "turn-dot"} key={index} />)}</div><strong>Ходы {selections.length} / {requiredCount}</strong></div>
      {selections.length > 0 && <details className="turns-list"><summary>Мероприятия</summary><ol>{selections.map(({ decision, measure, district }) => <li key={decision.measureId}><span>{measure?.name ?? decision.measureId}<small>{district}</small></span><button type="button" onClick={() => onRemove(decision.measureId)} aria-label={`Удалить ${measure?.name ?? decision.measureId}`}>×</button></li>)}</ol></details>}
      <div className="turns-action"><button className="primary-button" type="button" disabled={!ready || calculating || missing > 0 || spent > budgetLimit} onClick={onCalculate}>{calculating ? "Подсчитываем…" : "Подвести итог"}</button>{missing > 0 && <span>Осталось {missing} {missing === 1 ? "ход" : missing < 5 ? "хода" : "ходов"}</span>}</div>
      {errors.length > 0 && <div className="error-message turns-error" role="alert">{errors.map((error, index) => <p key={`${index}-${error}`}>{error}</p>)}</div>}
    </section>
  </>;
}

type ResultsProps = {
  result: SimulationResult;
  selections: ScenarioSelection[];
  bootstrap: Bootstrap | null;
  onViewDistricts: () => void;
  onNewScenario: () => void;
};

function categoryValue(result: SimulationResult, metrics: string[], weights: Record<string, number>, phase: "before" | "after") {
  const metricWeight = metrics.reduce((sum, metric) => sum + (weights[metric] ?? 1), 0);
  const population = result.districts.reduce((sum, district) => sum + district.populationShare, 0);
  if (!metricWeight || !population) return 0;
  return result.districts.reduce((sum, district) => sum + district.populationShare *
    metrics.reduce((metricSum, metric) => metricSum + (district[phase][metric] ?? 0) * (weights[metric] ?? 1), 0) / metricWeight, 0) / population;
}

export function ResultsOverlay({ result, selections, bootstrap, onViewDistricts, onNewScenario }: ResultsProps) {
  const weights = bootstrap?.scoreRules.metricWeights ?? {};
  const explanation = result.explanation;
  const isAi = explanation?.source === "llm";
  return <section className="results-overlay" role="dialog" aria-modal="true" aria-labelledby="results-title">
    <div className="results-shell">
      <div className="results-topline"><span>АКИМ НА 5 ЧАСОВ / ИТОГ СЦЕНАРИЯ</span><button type="button" className="results-close" onClick={onViewDistricts} aria-label="Закрыть результаты">×</button></div>
      <header className="results-hero">
        <p id="results-title" className="results-eyebrow">ASTANA QUALITY OF LIFE SCORE</p>
        <div className="results-score"><strong>{format(result.displayScore)}</strong><span className={result.scoreDelta >= 0 ? "positive" : "negative"}>{signed(result.scoreDelta)}</span></div>
        <p className="results-transition">{format(result.baselineScore)} <span>→</span> {format(result.displayScore)}</p>
        <div className="results-summary-grid">
          <div><small>Изменение Score</small><strong>{signed(result.scoreDelta)}</strong></div>
          <div><small>До</small><strong>{format(result.baselineScore)}</strong></div>
          <div><small>После</small><strong>{format(result.displayScore)}</strong></div>
          <div><small>Слабейший район</small><strong>{result.summary.weakestDistrictName}</strong></div>
          <div><small>Критических показателей</small><strong>{result.baseline.nCrit} → {result.summary.nCrit}</strong></div>
          <div><small>Бюджет</small><strong>{result.budget.spent} / {result.budget.limit}</strong></div>
        </div>
      </header>
      <div className="results-columns">
        <section className="results-card"><h2>Изменения по направлениям</h2><p className="results-note">Средние значения показателей по районам с учётом населения и весов модели.</p>
          <div className="category-list">{categories.map((category) => {
            const before = categoryValue(result, category.metrics, weights, "before");
            const after = categoryValue(result, category.metrics, weights, "after");
            return <div className="category-row" key={category.name}>
              <div className="category-line"><strong>{category.name}</strong><span>{format(before, 1)} → {format(after, 1)} <b className={after >= before ? "positive" : "negative"}>{signed(after - before, 1)}</b></span></div>
              <div className="category-track"><span style={{ width: `${Math.max(0, Math.min(100, before))}%` }} /><strong style={{ width: `${Math.max(0, Math.min(100, after))}%` }} /></div>
            </div>;
          })}</div>
        </section>
        <section className="results-card"><h2>Выбранные решения</h2><ol className="results-decisions">{selections.map(({ decision, measure, district }, index) => {
          const effect = result.measureEffects.find((item) => item.measureId === decision.measureId);
          const mainEffect = effect && Object.entries(effect.realizedEffects).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))[0];
          return <li key={decision.measureId}><span className="results-decision-index">{index + 1}</span><div><strong>{measure?.name ?? effect?.name ?? decision.measureId} — {district}</strong><small>{effect?.cost ?? measure?.cost ?? 0} ед. · {mainEffect ? `${metricNames[mainEffect[0]] ?? mainEffect[0]} ${signed(mainEffect[1], 1)}` : "Эффект учтён в Score"}</small></div></li>;
        })}</ol></section>
      </div>
      <section className="results-card analysis-card"><h2>AI Analysis</h2>
        {isAi ? <><p>{explanation.summary}</p><div className="analysis-grid">
          <div><h3>Сильные стороны</h3><AnalysisItems items={explanation.strengths} /></div>
          <div><h3>Риски</h3><AnalysisItems items={explanation.risks} /></div>
          <div><h3>Возможные последствия</h3><p className="results-note">Отдельный анализ последствий пока недоступен.</p></div>
          <div><h3>Рекомендация</h3><AnalysisItems items={explanation.recommendations} /></div>
        </div></> : <div className="analysis-grid">
          <div><h3>Сильные стороны</h3><p className="results-note">Ожидается AI анализ.</p></div>
          <div><h3>Риски</h3><p className="results-note">Ожидается AI анализ.</p></div>
          <div><h3>Возможные последствия</h3><p className="results-note">Ожидается AI анализ.</p></div>
          <div><h3>Рекомендация</h3><p className="results-note">Ожидается AI анализ.</p></div>
        </div>}
        {!isAi && explanation?.summary && <p className="results-model-note"><strong>Пояснение модели:</strong> {explanation.summary}</p>}
      </section>
      <footer className="results-actions"><button type="button" className="results-secondary" onClick={onViewDistricts}>Посмотреть районы</button><button type="button" className="primary-button" onClick={onNewScenario}>Новый сценарий</button></footer>
    </div>
  </section>;
}

function AnalysisItems({ items }: { items: string[] }) {
  return items?.length ? <ul>{items.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul> : <p className="results-note">Данных пока нет.</p>;
}
