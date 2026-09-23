"use client";

import { useMemo, useState } from "react";
import MeasurePreview from "../components/measure-3d/MeasurePreview";
import { isMeasureId } from "../components/measure-3d/catalog";
import type { Bootstrap, Decision, DistrictMeasure, Measure } from "./api";
import styles from "./initiative-modal.module.css";

export type InitiativeSelection = { initiativeId: string; districtId: string };

type Props = {
  open: boolean;
  districtId: string;
  districtName: string;
  initiatives: DistrictMeasure[];
  decisions: Decision[];
  allMeasures: Measure[];
  rules: Pick<Bootstrap, "budgetLimit" | "requiredDecisionCount" | "maxMeasuresPerCategory" | "horizonQuarters" | "scoreRules">;
  onSelectInitiative: (selection: InitiativeSelection) => void | Promise<void>;
  onClose: () => void;
  /** Extra restrictions from the scenario validator, if any. Return a readable reason. */
  getUnavailableReason?: (initiative: DistrictMeasure, districtId: string) => string | null;
};

const filters = ["Все", "Транспорт", "Экология", "Соцсфера", "Безопасность", "Сервисы"] as const;

function formatEffect(value: number) {
  const amount = value.toLocaleString("ru-RU", { maximumFractionDigits: 2 });
  return `${value > 0 ? "+" : ""}${amount}`;
}

export default function InitiativeModal({ open, districtId, districtName, initiatives, decisions, allMeasures, rules, onSelectInitiative, onClose, getUnavailableReason }: Props) {
  const [filter, setFilter] = useState<string>("Все");
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const measureById = useMemo(() => new Map(allMeasures.map((measure) => [measure.id, measure])), [allMeasures]);
  const spent = decisions.reduce((sum, decision) => sum + (measureById.get(decision.measureId)?.cost ?? 0), 0);
  const visible = filter === "Все" ? initiatives : initiatives.filter((initiative) => initiative.categoryName === filter);

  function unavailableReason(initiative: DistrictMeasure): string | null {
    if (decisions.some((decision) => decision.measureId === initiative.id)) return "Уже выбрано";
    if (decisions.length >= rules.requiredDecisionCount) return "Достигнут лимит мероприятий";
    if (spent + initiative.cost > rules.budgetLimit) return "Недостаточно бюджета";
    const categoryCount = decisions.filter((decision) => measureById.get(decision.measureId)?.categoryId === initiative.categoryId).length;
    if (categoryCount >= rules.maxMeasuresPerCategory) return "Достигнут лимит направления";
    for (const conflict of rules.scoreRules.conflicts) {
      const otherId = conflict.firstMeasureId === initiative.id ? conflict.secondMeasureId : conflict.secondMeasureId === initiative.id ? conflict.firstMeasureId : null;
      if (!otherId) continue;
      const selected = decisions.find((decision) => decision.measureId === otherId);
      if (!selected) continue;
      if (conflict.sameDistrictOnly && (initiative.scope === "city" || selected.districtId !== districtId)) continue;
      return `Конфликт с «${measureById.get(otherId)?.name ?? otherId}»`;
    }
    return getUnavailableReason?.(initiative, districtId) ?? null;
  }

  async function select(initiative: DistrictMeasure) {
    if (pendingId || unavailableReason(initiative)) return;
    setPendingId(initiative.id);
    setError(null);
    try {
      await onSelectInitiative({ initiativeId: initiative.id, districtId });
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Не удалось добавить мероприятие");
    } finally {
      setPendingId(null);
    }
  }

  if (!open) return null;

  return (
    <section className={styles.catalog} aria-label={`Каталог мероприятий: ${districtName}`}>
        <header className={styles.header}>
          <div className={styles.heading}>
            <button className={styles.back} type="button" onClick={onClose}>← К району</button>
            <p>Выберите мероприятие для района {districtName}</p>
          </div>
          <div className={styles.budget}>Доступно <strong>{Math.max(0, rules.budgetLimit - spent)} ед.</strong></div>
        </header>
        <div className={styles.filterBar} role="group" aria-label="Направление мероприятия">
          {filters.map((name) => <button key={name} type="button" className={filter === name ? styles.filterActive : styles.filter} aria-pressed={filter === name} onClick={() => setFilter(name)}>{name}</button>)}
        </div>
        <div className={styles.scrollArea}>
          <div className={styles.grid}>
            {visible.map((initiative) => {
              const reason = unavailableReason(initiative);
              const isPending = pendingId === initiative.id;
              const isSelected = decisions.some((decision) => decision.measureId === initiative.id);
              return <article className={`${styles.card} ${reason && !isSelected ? styles.cardDisabled : ""} ${isSelected ? styles.cardSelected : ""}`} key={initiative.id}>
                <div className={styles.preview}>
                  {isMeasureId(initiative.id) ? <MeasurePreview measureId={initiative.id} variant="card" /> : <div className={styles.previewFallback}>3D-просмотр недоступен</div>}
                  <span className={styles.scope}>{initiative.scope === "city" ? "Весь город" : "Район"}</span>
                </div>
                <div className={styles.cardBody}>
                  <div className={styles.meta}><span className={styles.category}>{initiative.categoryName}</span><span className={styles.lag}>Лаг: {initiative.lagQuarters} кв.</span></div>
                  <h3>{initiative.name}</h3>
                  <div className={styles.cost}><strong>{initiative.cost}</strong><span>ед. бюджета</span></div>
                  <div className={styles.effects} aria-label={`Эффект за ${rules.horizonQuarters} кварталов`}>
                    <span className={styles.effectsTitle}>Эффект за {rules.horizonQuarters} кв.</span>
                    <div>{Object.entries(initiative.realizedEffects).filter(([, value]) => value !== 0).map(([code, value]) => <span className={`${styles.effect} ${value < 0 ? styles.effectNegative : ""}`} key={code}><b>{code}</b> {formatEffect(value)}</span>)}</div>
                  </div>
                  {reason && !isSelected && <p className={styles.reason}>{reason}</p>}
                  <button className={`${styles.select} ${isSelected ? styles.selectSelected : ""}`} type="button" disabled={Boolean(reason) || Boolean(pendingId)} onClick={() => void select(initiative)} aria-label={isSelected ? `${initiative.name}: выбрано` : reason ? `${initiative.name}: ${reason}` : `Выбрать ${initiative.name}`} title={reason ?? undefined}>
                    {isPending ? "Добавляем…" : isSelected ? "✓ Выбрано" : "Выбрать"}<span aria-hidden="true">{reason || isPending ? "" : "↗"}</span>
                  </button>
                </div>
              </article>;
            })}
          </div>
          {visible.length === 0 && <p className={styles.empty}>В этом направлении пока нет мероприятий.</p>}
        </div>
        {error && <div className={styles.error} role="alert">{error}</div>}
    </section>
  );
}
