"use client";

import type { DistrictResult, MeasureEffect, ScoreSummary, SimulationResult } from "./api";

type Props = {
  result: SimulationResult;
  metricLabels: Record<string, string>;
  busy: boolean;
  onTryBest: () => void;
};

const CRITICAL = 40;

function number(value: number, digits = 2): string {
  return value.toLocaleString("ru-RU", { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

function signed(value: number, digits = 2): string {
  return `${value >= 0 ? "+" : "−"}${number(Math.abs(value), digits)}`;
}

/** The same measure in another district is a different decision. */
function planKey(effect: MeasureEffect): string {
  return `${effect.measureId}:${effect.targetDistrictId ?? "city"}`;
}

function PlanCard({ title, score, delta, spent, limit, summary, best }: {
  title: string; score: number; delta: number; spent: number; limit: number; summary: ScoreSummary; best?: boolean;
}) {
  return (
    <div className={best ? "plan-card best" : "plan-card"}>
      <span>{title}</span>
      <strong>{number(score)}</strong>
      <small>{signed(delta)} к базе</small>
      <dl>
        <div><dt>Бюджет</dt><dd>{spent}/{limit}</dd></div>
        <div><dt>Слабейший</dt><dd>{summary.weakestDistrictName} {number(summary.dMin)}</dd></div>
        <div><dt>Ниже 40</dt><dd>{summary.nCrit}</dd></div>
      </dl>
    </div>
  );
}

function MeasureDiff({ label, items, names, tone }: {
  label: string; items: MeasureEffect[]; names: Record<string, string>; tone: "user" | "best";
}) {
  if (items.length === 0) return null;
  return (
    <div className={`measure-diff ${tone}`}>
      <p>{label}</p>
      <ul>{items.map((effect) => (
        <li key={planKey(effect)}>
          <strong>{effect.measureId} · {effect.name}</strong>
          <span>
            {effect.targetDistrictId ? names[effect.targetDistrictId] ?? effect.targetDistrictId : "весь город"}
            {" · "}{effect.cost} ед. · лаг {effect.lagQuarters} кв. ({8 - effect.lagQuarters}/8 эффекта)
            {" · "}{Object.entries(effect.realizedEffects).map(([code, value]) => `${code} ${signed(value)}`).join(", ")}
          </span>
        </li>
      ))}</ul>
    </div>
  );
}

function valueCell(value: number, digits = 1) {
  return <td className={value < CRITICAL ? "critical" : undefined}>{number(value, digits)}</td>;
}

export default function ScenarioComparison({ result, metricLabels, busy, onTryBest }: Props) {
  const best = result.bestSolution;
  const comparison = result.comparison;
  if (!best || !comparison) return null;

  const optimal = comparison.isOptimal;
  const names = Object.fromEntries(result.districts.map((district) => [district.id, district.name]));
  const userKeys = new Set(result.measureEffects.map(planKey));
  const bestKeys = new Set(best.measureEffects.map(planKey));
  const onlyUser = result.measureEffects.filter((effect) => !bestKeys.has(planKey(effect)));
  const onlyBest = best.measureEffects.filter((effect) => !userKeys.has(planKey(effect)));
  const shared = result.measureEffects.filter((effect) => bestKeys.has(planKey(effect)));

  const bestDistrict = (id: string): DistrictResult | undefined => best.districts.find((district) => district.id === id);
  const focus = result.districts.find((district) => district.id === result.summary.weakestDistrictId);
  const focusBest = focus && bestDistrict(focus.id);
  const metricRows = focus ? Object.keys(focus.before)
    .map((code) => ({ code, before: focus.before[code], user: focus.after[code], best: focusBest?.after[code] ?? focus.after[code] }))
    .filter((row) => optimal ? row.user !== row.before : Math.abs(row.best - row.user) >= 0.005 || row.user < CRITICAL)
    .sort((a, b) => Math.abs(b.best - b.user) - Math.abs(a.best - a.user)) : [];

  return (
    <section className="results-card comparison-card" aria-label="Сравнение с лучшим планом">
      <h2>Сравнение с лучшим планом</h2>
      {optimal ? (
        <p className="optimal-note">
          Ваш план уже даёт максимальный Score этой модели — {number(result.finalScore)}. Лучшего допустимого набора нет.
        </p>
      ) : <>
        <div className="plan-cards">
          <PlanCard title="Ваш план" score={result.finalScore} delta={result.scoreDelta}
            spent={result.budget.spent} limit={result.budget.limit} summary={result.summary} />
          <PlanCard title="Лучший план" score={best.finalScore} delta={best.scoreDelta}
            spent={best.budget.spent} limit={best.budget.limit} summary={best.summary} best />
        </div>
        <p className="gap-line">До максимума не хватает <strong>{number(comparison.scoreGap)}</strong> балла.</p>
      </>}

      <div className="comparison-grid">
        {!optimal && <div>
          <h3>Чем отличаются решения</h3>
          <MeasureDiff label="Только в вашем плане" items={onlyUser} names={names} tone="user" />
          <MeasureDiff label="Только в лучшем плане" items={onlyBest} names={names} tone="best" />
          {shared.length > 0 && <p className="results-note">В обоих планах: {shared.map((effect) =>
            `${effect.measureId}${effect.targetDistrictId ? ` (${names[effect.targetDistrictId]})` : ""}`).join(", ")}.</p>}
          {best.synergies.length > 0 && <p className="results-note">Синергии лучшего плана: {best.synergies.map((item) =>
            `${item.measureIds.join(" + ")} → ${item.metric} +${number(item.bonus, 0)} (${names[item.districtId] ?? item.districtId})`).join("; ")}.</p>}
        </div>}
        <div>
          {focus && metricRows.length > 0 && <>
            <h3>{focus.name}: слабейший район, 30% Score</h3>
            <table className="compare-table">
              <thead><tr><th>Показатель</th><th>Было</th><th>Ваш</th>{!optimal && <th>Лучший</th>}</tr></thead>
              <tbody>{metricRows.map((row) => (
                <tr key={row.code}>
                  <th scope="row">{row.code} {metricLabels[row.code] ?? ""}</th>
                  {valueCell(row.before)}{valueCell(row.user)}{!optimal && valueCell(row.best)}
                </tr>
              ))}</tbody>
            </table>
            <p className="results-note">Красным — ниже 40: каждый такой показатель снимает 1 балл Score.</p>
          </>}
          <h3>Баллы районов</h3>
          <table className="compare-table">
            <thead><tr><th>Район</th><th>Было</th><th>Ваш</th>{!optimal && <th>Лучший</th>}</tr></thead>
            <tbody>{result.districts.map((district) => (
              <tr key={district.id} className={district.id === result.summary.weakestDistrictId ? "weakest" : undefined}>
                <th scope="row">{district.name}</th>
                <td>{number(district.scoreBefore)}</td>
                <td>{number(district.scoreAfter)}</td>
                {!optimal && <td>{number(bestDistrict(district.id)?.scoreAfter ?? district.scoreAfter)}</td>}
              </tr>
            ))}</tbody>
          </table>
        </div>
      </div>

      <div className="comparison-footer">
        {!optimal && <button className="results-secondary" type="button" disabled={busy} onClick={onTryBest}>
          {busy ? "Пересчитываем…" : "Загрузить лучший план и пересчитать"}
        </button>}
        <p className="results-note">
          Максимум найден полным перебором {best.evaluatedCandidates.toLocaleString("ru-RU")} допустимых наборов
          учебной модели; это не прогноз для реального города.
        </p>
      </div>
    </section>
  );
}
