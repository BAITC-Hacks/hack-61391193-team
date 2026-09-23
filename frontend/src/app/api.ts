import type { FeatureCollection, LineString, MultiPolygon, Point, Polygon } from "geojson";

export type MetricValues = Record<string, number>;
export type District = {
  id: string; name: string; populationShare: number;
  metrics: MetricValues; baselineScore: number;
};
export type Measure = {
  id: string; categoryId: string; categoryName: string; name: string;
  scope: "district" | "city"; cost: number; lagQuarters: number;
  realizationFactor: number; fullEffects: MetricValues; realizedEffects: MetricValues;
};
export type DistrictMeasure = Measure & { targetDistrictId: string | null; affectedDistrictIds: string[] };
export type Decision = { measureId: string; districtId?: string };
export type Bootstrap = {
  apiVersion: string; budgetLimit: number; requiredDecisionCount: number;
  horizonQuarters: number; maxMeasuresPerCategory: number; baselineScore: number;
  api: {
    districts: string; districtTemplate: string; measures: string;
    districtMeasuresTemplate: string; mapLayers: string;
    calculate: string; baseline: string; swaggerUi: string; openApi: string;
  };
  map: { cityBoundary: string; districts: string; districtStats: string; pois: string; parks: string; roads: string };
  dataNotes: string[];
  scoreRules: {
    formula: string; metricWeights: MetricValues; criticalThresholdExclusive: number;
    synergies: { districtMeasureId: string; cityMeasureId: string; metric: string; bonus: number }[];
    conflicts: { firstMeasureId: string; secondMeasureId: string; sameDistrictOnly: boolean; reason: string }[];
  };
};
export type SimulationResult = {
  metricName: string; modelVersion: string; finalScore: number; displayScore: number;
  baselineScore: number; scoreDelta: number;
  budget: { limit: number; spent: number; remaining: number };
  horizonQuarters: number;
  baseline: ScoreSummary; summary: ScoreSummary;
  districts: DistrictResult[];
  measureEffects: MeasureEffect[];
  synergies: Synergy[];
  explanation: { source: "llm" | "template"; summary: string; strengths: string[]; risks: string[]; recommendations: string[] };
  /** Global maximum of the fixed model; null for the baseline. Shown only after a full 5-decision calculation. */
  bestSolution: OptimalSolution | null;
  comparison: { scoreGap: number; isOptimal: boolean } | null;
};
export type MeasureEffect = {
  measureId: string; name: string; categoryId: string; scope: string;
  targetDistrictId: string | null; cost: number; lagQuarters: number;
  realizationFactor: number; affectedDistrictIds: string[]; realizedEffects: MetricValues;
  districtScoreContributionBeforeClip: number;
};
export type Synergy = { measureIds: string[]; districtId: string; metric: string; bonus: number };
export type OptimalSolution = {
  algorithm: string; provenOptimal: boolean; evaluatedCandidates: number;
  decisions: { measureId: string; districtId: string | null }[];
  finalScore: number; displayScore: number; scoreDelta: number;
  budget: { limit: number; spent: number; remaining: number };
  summary: ScoreSummary; districts: DistrictResult[];
  measureEffects: MeasureEffect[]; synergies: Synergy[];
};
export type ScoreSummary = {
  dAvg: number; dMin: number; weakestDistrictId: string; weakestDistrictName: string;
  nCrit: number; criticalMetrics: { districtId: string; districtName: string; metric: string; value: number }[];
  cityContribution: number; weakestDistrictContribution: number;
  criticalPenalty: number; finalScore: number;
};
export type DistrictResult = {
  id: string; name: string; populationShare: number;
  before: MetricValues; after: MetricValues; metricDeltas: MetricValues;
  scoreBefore: number; scoreAfter: number; scoreDelta: number;
};
export type LayerManifest = {
  source: string; release: string; attribution: string;
  layers: { id: string; url: string; count: number; bytes: number; contentType: string; description: string }[];
};
export type DistrictStats = { district_id: string; name_ru: string; area_km2: number; [key: string]: string | number };
export type ModelDistricts = FeatureCollection<Polygon | MultiPolygon, { district_id: string; name_ru: string; name_kk: string }>;
export type CityBoundary = FeatureCollection<MultiPolygon, { name: string }>;
export type Pois = FeatureCollection<Point, { id: string; name: string | null; kind: string; category: string; source: string; district_id: string }>;
export type Parks = FeatureCollection<Polygon | MultiPolygon, { id: string; name: string | null; class: string; district_id: string }>;
export type Roads = FeatureCollection<LineString, { id: string; name: string | null; class: string; district_id: string }>;

export class ApiError extends Error {
  constructor(public status: number, public fields: { field: string; message: string }[], message: string) {
    super(message);
  }
}

export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { signal });
  return parseResponse<T>(response);
}

export async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  return parseResponse<T>(response);
}

async function parseResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get("content-type") ?? "";
  const isJson = /\bjson\b|\+json\b/i.test(contentType);
  const raw = await response.text();
  let data: unknown = null;
  if (isJson && raw) {
    try { data = JSON.parse(raw); }
    catch { console.error("Invalid API JSON:", response.status, raw); }
  } else if (raw && !response.ok) {
    console.error("API error:", response.status, raw);
  }
  if (!response.ok) {
    if (response.status === 401) {
      await fetch("/api/session/logout", { method: "POST", credentials: "same-origin" }).catch(() => null);
      window.location.replace("/login");
    }
    const payload = data && typeof data === "object" ? data as Record<string, unknown> : {};
    const fields = Array.isArray(payload.errors) ? payload.errors.filter((item): item is { field: string; message: string } =>
      !!item && typeof item === "object" && typeof item.field === "string" && typeof item.message === "string") : [];
    const message = response.status === 422 && typeof payload.detail === "string" ? payload.detail : "Сервис временно недоступен";
    throw new ApiError(response.status, fields, message);
  }
  if (!isJson || data === null) throw new ApiError(response.status, [], "Сервис временно недоступен");
  return data as T;
}

export function districtPath(template: string, id: string): string {
  return template.replace("{districtId}", encodeURIComponent(id));
}
