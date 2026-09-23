"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { ExpressionSpecification } from "maplibre-gl";
import type { FeatureCollection, MultiPolygon, Polygon } from "geojson";
import {
  ApiError, apiGet, apiPost, districtPath,
  type Bootstrap, type CityBoundary, type Decision, type District,
  type DistrictMeasure, type DistrictStats, type LayerManifest, type Measure,
  type ModelDistricts, type Parks, type Pois, type Roads, type SimulationResult,
} from "./api";

type Map = import("maplibre-gl").Map;
type AdministrativeDistricts = FeatureCollection<Polygon | MultiPolygon, {
  objectid: number; name_object: string; name_object_kaz: string;
}>;
type Selected = { id: string | null; name: string };
type LayerKey = "city" | "parks" | "roads" | "pois";

const districtIds: Record<string, string> = {
  "Есиль": "esil", "Алматы": "almaty", "Сарыарка": "saryarka",
  "Байконур": "baikonur", "Нура": "nura",
};
const metricLabels: Record<string, string> = {
  T1: "Дороги", T2: "Общественный транспорт", E1: "Озеленение", E2: "Воздух",
  S1: "Школы и детсады", S2: "Медицина", B1: "Безопасность улиц",
  B2: "Безопасность движения", C1: "ЖКХ", C2: "Обращения жителей",
};
const poiKinds: Record<string, string> = {
  school: "Школы", kindergarten: "Детсады", hospital: "Больницы",
  clinic: "Поликлиники", bus_stop: "Остановки", traffic_signals: "Светофоры",
  crossing: "Переходы", street_lamp: "Фонари",
};

function getBounds(data: FeatureCollection<Polygon | MultiPolygon>): [[number, number], [number, number]] {
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity;
  for (const feature of data.features) {
    const polygons = feature.geometry.type === "Polygon"
      ? [feature.geometry.coordinates] : feature.geometry.coordinates;
    for (const polygon of polygons) for (const ring of polygon) {
      for (const [longitude, latitude] of ring) {
        west = Math.min(west, longitude); south = Math.min(south, latitude);
        east = Math.max(east, longitude); north = Math.max(north, latitude);
      }
    }
  }
  return [[west, south], [east, north]];
}

function number(value: number, digits = 1): string {
  return value.toLocaleString("ru-RU", { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

function scoreColor(score: number): string {
  if (score < 50) return "#c96455";
  if (score < 57) return "#d69a43";
  return "#458e81";
}

export default function DistrictMap() {
  const mapContainer = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  const mapRef = useRef<Map | null>(null);
  const selectedFeature = useRef<{ source: string; id: string | number } | null>(null);
  const boundsRef = useRef<[[number, number], [number, number]] | null>(null);
  const adminBounds = useRef<[[number, number], [number, number]] | null>(null);
  const modelBounds = useRef<[[number, number], [number, number]] | null>(null);
  const loadedLayers = useRef(new Set<LayerKey>());
  const [mapReady, setMapReady] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);
  const [apiError, setApiError] = useState<string | null>(null);
  const [bootstrap, setBootstrap] = useState<Bootstrap | null>(null);
  const [districts, setDistricts] = useState<District[]>([]);
  const [measures, setMeasures] = useState<Measure[]>([]);
  const [baseline, setBaseline] = useState<SimulationResult | null>(null);
  const [manifest, setManifest] = useState<LayerManifest | null>(null);
  const [stats, setStats] = useState<DistrictStats[]>([]);
  const [modelGeojson, setModelGeojson] = useState<ModelDistricts | null>(null);
  const [cityGeojson, setCityGeojson] = useState<CityBoundary | null>(null);
  const [selected, setSelected] = useState<Selected | null>(null);
  const [districtDetail, setDistrictDetail] = useState<District | null>(null);
  const [districtMeasures, setDistrictMeasures] = useState<DistrictMeasure[]>([]);
  const [boundaryMode, setBoundaryMode] = useState<"administrative" | "model">("administrative");
  const [visibleLayers, setVisibleLayers] = useState<Record<LayerKey, boolean>>({ city: false, parks: false, roads: false, pois: false });
  const [layerLoading, setLayerLoading] = useState<LayerKey | null>(null);
  const [poiKind, setPoiKind] = useState("school");
  const [tab, setTab] = useState<"district" | "scenario">("district");
  const [decisions, setDecisions] = useState<Decision[]>([]);
  const [result, setResult] = useState<SimulationResult | null>(null);
  const [calculating, setCalculating] = useState(false);
  const [calculationError, setCalculationError] = useState<string[]>([]);

  function clearSelection() {
    const map = mapRef.current;
    if (map && selectedFeature.current) {
      map.setFeatureState(selectedFeature.current, { selected: false });
    }
    selectedFeature.current = null;
    setSelected(null);
    setDistrictDetail(null);
    setDistrictMeasures([]);
  }

  function chooseFeature(source: string, id: string | number, name: string, districtId: string | null) {
    const map = mapRef.current;
    if (!map) return;
    if (selectedFeature.current) map.setFeatureState(selectedFeature.current, { selected: false });
    selectedFeature.current = { source, id };
    map.setFeatureState({ source, id }, { selected: true });
    setSelected({ id: districtId, name });
    setDistrictDetail(null);
    setDistrictMeasures([]);
    setTab("district");
  }

  useEffect(() => {
    const controller = new AbortController();
    async function loadApi() {
      try {
        const config = await apiGet<Bootstrap>("/api/v1/simulation/bootstrap", controller.signal);
        if (controller.signal.aborted) return;
        setBootstrap(config);
        const [catalog, allMeasures, base, layerList, spatialStats, model, city] = await Promise.all([
          apiGet<District[]>(config.api.districts, controller.signal),
          apiGet<Measure[]>(config.api.measures, controller.signal),
          apiGet<SimulationResult>(config.api.baseline, controller.signal),
          apiGet<LayerManifest>(config.api.mapLayers, controller.signal),
          apiGet<DistrictStats[]>(config.map.districtStats, controller.signal),
          apiGet<ModelDistricts>(config.map.districts, controller.signal),
          apiGet<CityBoundary>(config.map.cityBoundary, controller.signal),
        ]);
        if (controller.signal.aborted) return;
        setDistricts(catalog); setMeasures(allMeasures); setBaseline(base);
        setManifest(layerList); setStats(spatialStats);
        setModelGeojson(model); setCityGeojson(city);
        setApiError(null);
      } catch (error) {
        if (!controller.signal.aborted) setApiError(`Бэкенд недоступен: ${error instanceof Error ? error.message : "ошибка запроса"}`);
      }
    }
    void loadApi();
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!selected?.id || !bootstrap) return;
    const controller = new AbortController();
    Promise.all([
      apiGet<District>(districtPath(bootstrap.api.districtTemplate, selected.id), controller.signal),
      apiGet<DistrictMeasure[]>(districtPath(bootstrap.api.districtMeasuresTemplate, selected.id), controller.signal),
    ]).then(([detail, available]) => {
      if (!controller.signal.aborted) { setDistrictDetail(detail); setDistrictMeasures(available); }
    }).catch((error) => {
      if (!controller.signal.aborted) setApiError(error instanceof Error ? error.message : "Ошибка загрузки района");
    });
    return () => controller.abort();
  }, [selected?.id, bootstrap]);

  useEffect(() => {
    if (!mapContainer.current) return;
    let disposed = false;
    let map: Map | null = null;
    const controller = new AbortController();
    function fitDistricts() {
      if (!map || !boundsRef.current || !mapContainer.current) return;
      const width = mapContainer.current.clientWidth;
      const height = mapContainer.current.clientHeight;
      const mobile = width <= 760;
      const panelSize = panelRef.current?.getBoundingClientRect();
      map.fitBounds(boundsRef.current, {
        padding: mobile
          ? { top: 115, right: 22, bottom: Math.min(height * 0.58, (panelSize?.height ?? 230) + 48), left: 22 }
          : { top: 104, right: (panelSize?.width ?? 370) + 68, bottom: 45, left: 45 },
        maxZoom: 11, duration: 0,
      });
    }
    function resizeMap() { map?.resize(); fitDistricts(); }
    window.addEventListener("resize", resizeMap);
    const observer = new ResizeObserver(resizeMap);
    observer.observe(mapContainer.current);
    if (panelRef.current) observer.observe(panelRef.current);

    async function initialize() {
      const maplibregl = await import("maplibre-gl");
      if (disposed || !mapContainer.current) return;
      maplibregl.setWorkerUrl("/vendor/maplibre/maplibre-gl-worker.mjs");
      map = new maplibregl.Map({
        container: mapContainer.current,
        style: "https://tiles.openfreemap.org/styles/liberty",
        center: [71.43, 51.17], zoom: 9,
        maxBounds: [[71.12, 50.78], [71.88, 51.43]],
        attributionControl: {},
      });
      mapRef.current = map;
      map.addControl(new maplibregl.NavigationControl(), "top-right");
      map.on("load", async () => {
        try {
          const data = await apiGet<AdministrativeDistricts>("/data/astana-districts.geojson", controller.signal);
          if (disposed || !map) return;
          map.addSource("administrative", { type: "geojson", data, promoteId: "objectid" });
          map.addLayer({ id: "administrative-fill", type: "fill", source: "administrative",
            paint: { "fill-color": ["case", ["boolean", ["feature-state", "selected"], false], "#ea7b32", "#3479a5"], "fill-opacity": 0.34 } });
          map.addLayer({ id: "administrative-outline", type: "line", source: "administrative",
            paint: { "line-color": ["case", ["boolean", ["feature-state", "selected"], false], "#d85b22", "#173f5a"],
              "line-width": ["case", ["boolean", ["feature-state", "selected"], false], 4, 2.2] } });
          adminBounds.current = getBounds(data);
          boundsRef.current = adminBounds.current;
          fitDistricts();
          map.on("click", "administrative-fill", (event) => {
            const properties = event.features?.[0]?.properties;
            if (typeof properties?.objectid === "number" && typeof properties.name_object === "string") {
              chooseFeature("administrative", properties.objectid, properties.name_object, districtIds[properties.name_object] ?? null);
            }
          });
          map.on("mouseenter", "administrative-fill", () => { if (map) map.getCanvas().style.cursor = "pointer"; });
          map.on("mouseleave", "administrative-fill", () => { if (map) map.getCanvas().style.cursor = ""; });
          setMapReady(true);
        } catch (error) {
          if (!disposed) setMapError(error instanceof Error ? error.message : "Не удалось загрузить границы");
        }
      });
    }
    void initialize().catch(() => { if (!disposed) setMapError("Не удалось открыть карту"); });
    const layersLoaded = loadedLayers.current;
    return () => {
      disposed = true; controller.abort(); observer.disconnect(); window.removeEventListener("resize", resizeMap);
      map?.remove(); mapRef.current = null; layersLoaded.clear();
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!mapReady || !map || !modelGeojson || map.getSource("model")) return;
    map.addSource("model", { type: "geojson", data: modelGeojson, promoteId: "district_id", attribution: manifest?.attribution });
    map.addLayer({ id: "model-fill", type: "fill", source: "model", layout: { visibility: "none" },
      paint: { "fill-color": ["case", ["boolean", ["feature-state", "selected"], false], "#ea7b32", "#3479a5"], "fill-opacity": 0.34 } });
    map.addLayer({ id: "model-outline", type: "line", source: "model", layout: { visibility: "none" },
      paint: { "line-color": ["case", ["boolean", ["feature-state", "selected"], false], "#d85b22", "#173f5a"],
        "line-width": ["case", ["boolean", ["feature-state", "selected"], false], 4, 2.2] } });
    modelBounds.current = getBounds(modelGeojson);
    map.on("click", "model-fill", (event) => {
      const properties = event.features?.[0]?.properties;
      if (typeof properties?.district_id === "string" && typeof properties.name_ru === "string") {
        chooseFeature("model", properties.district_id, properties.name_ru, properties.district_id);
      }
    });
    map.on("mouseenter", "model-fill", () => { map.getCanvas().style.cursor = "pointer"; });
    map.on("mouseleave", "model-fill", () => { map.getCanvas().style.cursor = ""; });
  }, [mapReady, modelGeojson, manifest]);

  useEffect(() => {
    const map = mapRef.current;
    if (!mapReady || !map || !cityGeojson || map.getSource("city")) return;
    map.addSource("city", { type: "geojson", data: cityGeojson, attribution: manifest?.attribution });
    map.addLayer({ id: "city-outline", type: "line", source: "city", layout: { visibility: "none" },
      paint: { "line-color": "#9166a2", "line-width": 2, "line-dasharray": [3, 2] } });
    loadedLayers.current.add("city");
  }, [mapReady, cityGeojson, manifest]);

  useEffect(() => {
    const map = mapRef.current;
    if (!mapReady || !map) return;
    const byName = result?.districts.flatMap((district) => [district.name, scoreColor(district.scoreAfter)]) ?? [];
    const byId = result?.districts.flatMap((district) => [district.id, scoreColor(district.scoreAfter)]) ?? [];
    map.setPaintProperty("administrative-fill", "fill-color", [
      "case", ["boolean", ["feature-state", "selected"], false], "#ea7b32",
      result ? (["match", ["get", "name_object"], ...byName, "#3479a5"] as unknown as ExpressionSpecification) : "#3479a5",
    ]);
    if (map.getLayer("model-fill")) {
      map.setPaintProperty("model-fill", "fill-color", [
        "case", ["boolean", ["feature-state", "selected"], false], "#ea7b32",
        result ? (["match", ["get", "district_id"], ...byId, "#3479a5"] as unknown as ExpressionSpecification) : "#3479a5",
      ]);
    }
  }, [mapReady, result, modelGeojson]);

  function changeBoundaryMode(next: "administrative" | "model") {
    const map = mapRef.current;
    if (!map || (next === "model" && !modelBounds.current)) return;
    clearSelection();
    setBoundaryMode(next);
    for (const layer of ["administrative-fill", "administrative-outline"]) map.setLayoutProperty(layer, "visibility", next === "administrative" ? "visible" : "none");
    for (const layer of ["model-fill", "model-outline"]) map.setLayoutProperty(layer, "visibility", next === "model" ? "visible" : "none");
    boundsRef.current = next === "model" ? modelBounds.current : adminBounds.current;
    const size = mapContainer.current;
    if (size && boundsRef.current) map.fitBounds(boundsRef.current, {
      padding: size.clientWidth <= 760
        ? { top: 115, right: 22, bottom: Math.min(size.clientHeight * 0.58, (panelRef.current?.clientHeight ?? 230) + 48), left: 22 }
        : { top: 104, right: (panelRef.current?.clientWidth ?? 370) + 68, bottom: 45, left: 45 },
      maxZoom: 11, duration: 0,
    });
  }

  async function toggleLayer(layer: LayerKey) {
    const map = mapRef.current;
    if (!map || !bootstrap) return;
    const next = !visibleLayers[layer];
    if (next && !loadedLayers.current.has(layer)) {
      setLayerLoading(layer);
      try {
        const attribution = manifest?.attribution;
        if (layer === "parks") {
          const data = await apiGet<Parks>(bootstrap.map.parks);
          map.addSource("parks", { type: "geojson", data, attribution });
          map.addLayer({ id: "parks-fill", type: "fill", source: "parks", paint: { "fill-color": "#4f9b58", "fill-opacity": 0.55 } });
        } else if (layer === "roads") {
          const data = await apiGet<Roads>(bootstrap.map.roads);
          map.addSource("roads", { type: "geojson", data, attribution });
          map.addLayer({ id: "roads-line", type: "line", source: "roads",
            paint: { "line-color": ["case", ["==", ["get", "class"], "light_rail"], "#8659b2", "#d87453"], "line-width": 2 } });
        } else if (layer === "pois") {
          const data = await apiGet<Pois>(bootstrap.map.pois);
          map.addSource("pois", { type: "geojson", data, attribution });
          map.addLayer({ id: "pois-circle", type: "circle", source: "pois", filter: ["==", ["get", "kind"], poiKind],
            paint: { "circle-radius": 4, "circle-color": "#7a49aa", "circle-stroke-color": "#fff", "circle-stroke-width": 1 } });
        }
        loadedLayers.current.add(layer);
      } catch (error) {
        setApiError(error instanceof Error ? error.message : "Ошибка загрузки слоя");
        setLayerLoading(null);
        return;
      }
      setLayerLoading(null);
    }
    const layerId = { city: "city-outline", parks: "parks-fill", roads: "roads-line", pois: "pois-circle" }[layer];
    map.setLayoutProperty(layerId, "visibility", next ? "visible" : "none");
    setVisibleLayers((current) => ({ ...current, [layer]: next }));
  }

  function changePoiKind(kind: string) {
    setPoiKind(kind);
    const map = mapRef.current;
    if (map?.getLayer("pois-circle")) map.setFilter("pois-circle", ["==", ["get", "kind"], kind]);
  }

  function addDecision(measure: DistrictMeasure) {
    if (decisions.length >= (bootstrap?.requiredDecisionCount ?? 5) || decisions.some((item) => item.measureId === measure.id)) return;
    const decision = measure.scope === "city" ? { measureId: measure.id } : { measureId: measure.id, districtId: selected?.id ?? undefined };
    if (measure.scope === "district" && !decision.districtId) return;
    setDecisions((current) => [...current, decision]);
    setResult(null); setCalculationError([]);
  }

  async function calculate() {
    if (!bootstrap || decisions.length !== bootstrap.requiredDecisionCount) return;
    setCalculating(true); setCalculationError([]);
    try {
      const response = await apiPost<SimulationResult>(bootstrap.api.calculate, { decisions });
      setResult(response);
    } catch (error) {
      if (error instanceof ApiError) setCalculationError(error.fields.length ? error.fields.map((item) => `${item.field}: ${item.message}`) : [error.message]);
      else setCalculationError([error instanceof Error ? error.message : "Расчёт не выполнен"]);
    } finally { setCalculating(false); }
  }

  const selectedStats = stats.find((item) => item.district_id === selected?.id);
  const selectedResult = (result ?? baseline)?.districts.find((item) => item.id === selected?.id);
  const estimatedCost = decisions.reduce((sum, decision) => sum + (measures.find((item) => item.id === decision.measureId)?.cost ?? 0), 0);
  const currentScore = result ?? baseline;

  return (
    <main className="map-screen" aria-label="Карта районов Астаны">
      <div ref={mapContainer} className="map-container" aria-label="Интерактивная карта" />
      <header className="map-brand">
        <h1>Астана · Районы</h1>
        <p>Выберите район на карте</p>
        <nav className="map-auth-links" aria-label="Аккаунт">
          <Link href="/login">Войти</Link>
          <Link href="/register">Регистрация</Link>
        </nav>
      </header>
      <div className="map-tools">
        <details>
          <summary>Слои карты</summary>
          <div className="tool-content">
            <p className="tool-label">Границы</p>
            <label><input type="radio" checked={boundaryMode === "administrative"} onChange={() => changeBoundaryMode("administrative")} /> 6 административных</label>
            <label><input type="radio" checked={boundaryMode === "model"} disabled={!modelGeojson} onChange={() => changeBoundaryMode("model")} /> 5 районов модели</label>
            <p className="tool-label">Данные Overture</p>
            {(["city", "parks", "roads", "pois"] as LayerKey[]).map((layer) => (
              <label key={layer}><input type="checkbox" checked={visibleLayers[layer]} disabled={!bootstrap || layerLoading === layer || (layer === "city" && !cityGeojson)} onChange={() => void toggleLayer(layer)} /> {{ city: "Граница города", parks: "Парки", roads: "Дороги и ЛРТ", pois: "Инфраструктура" }[layer]}{layerLoading === layer ? " · загрузка" : ""}{manifest ? ` · ${manifest.layers.find((item) => item.id === (layer === "city" ? "city-boundary" : layer))?.count ?? ""}` : ""}</label>
            ))}
            {visibleLayers.pois && <select aria-label="Тип инфраструктуры" value={poiKind} onChange={(event) => changePoiKind(event.target.value)}>{Object.entries(poiKinds).map(([kind, label]) => <option key={kind} value={kind}>{label}</option>)}</select>}
            <small>Геоданные Overture служат контекстом и не участвуют в Score.</small>
          </div>
        </details>
      </div>
      <aside ref={panelRef} className="selection-panel" aria-live="polite">
        <div className="panel-heading">
          <div><span className="selection-kicker">АКИМ НА 5 ЧАСОВ</span><h2>{selected?.name ?? "Выберите район"}</h2></div>
          {selected && <button className="text-button" type="button" onClick={clearSelection}>Сбросить</button>}
        </div>
        <div className="score-strip">
          <div><span>Score города</span><strong>{currentScore ? number(currentScore.displayScore, 2) : "—"}</strong></div>
          <div><span>Изменение</span><strong>{result ? `${result.scoreDelta >= 0 ? "+" : ""}${number(result.scoreDelta, 2)}` : "—"}</strong></div>
          <div><span>Бюджет</span><strong>{estimatedCost}/{bootstrap?.budgetLimit ?? 100}</strong></div>
        </div>
        <div className="panel-tabs" role="tablist" aria-label="Раздел панели">
          <button role="tab" aria-selected={tab === "district"} onClick={() => setTab("district")}>Район</button>
          <button role="tab" aria-selected={tab === "scenario"} onClick={() => setTab("scenario")}>Сценарий · {decisions.length}/{bootstrap?.requiredDecisionCount ?? 5}</button>
        </div>
        <div className="panel-body">
          {mapError && <p className="error-message">Карта: {mapError}</p>}
          {apiError && <p className="error-message">{apiError}. Запустите backend на порту 8080.</p>}
          {tab === "district" && (
            selected ? selected.id ? (
              <>
                {districtDetail ? <>
                  <p className="panel-note">Показатели ниже — синтетическая модель хакатона для пяти районов.</p>
                  <div className="district-facts">
                    <div><span>Балл района</span><strong>{number(selectedResult?.scoreAfter ?? districtDetail.baselineScore, 2)}</strong></div>
                    <div><span>Площадь</span><strong>{selectedStats ? `${number(selectedStats.area_km2)} км²` : "—"}</strong></div>
                    <div><span>Школы в геоданных</span><strong>{typeof selectedStats?.school_count === "number" ? selectedStats.school_count : "нет данных"}</strong></div>
                    <div><span>Доля зелени</span><strong>{typeof selectedStats?.green_share_pct === "number" ? `${number(selectedStats.green_share_pct)}%` : "нет данных"}</strong></div>
                  </div>
                  <h3>Показатели района</h3>
                  <div className="metric-list">{Object.entries(districtDetail.metrics).map(([code, value]) => (
                    <div key={code}><span>{metricLabels[code] ?? code}</span><strong>{number(value)}{result && selectedResult ? ` → ${number(selectedResult.after[code])}` : ""}</strong></div>
                  ))}</div>
                </> : <p>Загружаются показатели района…</p>}
                <h3>Мероприятия</h3>
                <p className="panel-note">Для районных мер цель — {selected.name}; городские действуют во всех пяти районах.</p>
                <div className="measure-list">{districtMeasures.map((measure) => (
                  <div className="measure-row" key={measure.id}>
                    <div><small>{measure.categoryName} · {measure.scope === "city" ? "весь город" : selected.name} · лаг {measure.lagQuarters} кв.</small><strong>{measure.name}</strong><span>{measure.cost} ед. · {Object.entries(measure.realizedEffects).map(([code, amount]) => `${code} ${amount > 0 ? "+" : ""}${number(amount, 2)}`).join(", ")}</span></div>
                    <button type="button" disabled={decisions.length >= (bootstrap?.requiredDecisionCount ?? 5) || decisions.some((item) => item.measureId === measure.id)} onClick={() => addDecision(measure)} aria-label={`Добавить ${measure.name}`}>+</button>
                  </div>
                ))}</div>
              </>
            ) : <p>Сарайшык есть на административной карте, но не входит в набор из пяти районов симулятора. Выберите другой район для показателей и мероприятий.</p>
            : <p>Нажмите на район на карте. Здесь появятся его показатели и доступные мероприятия.</p>
          )}
          {tab === "scenario" && <>
            <p className="panel-note">Выберите ровно {bootstrap?.requiredDecisionCount ?? 5} разных мер. Окончательную проверку бюджета и ограничений выполнит backend.</p>
            {decisions.length === 0 ? <p>Пока нет решений. Выберите район и добавьте меры из его карточки.</p> : <ol className="decision-list">{decisions.map((decision) => {
              const measure = measures.find((item) => item.id === decision.measureId);
              return <li key={decision.measureId}><div><strong>{measure?.name ?? decision.measureId}</strong><span>{decision.districtId ? districts.find((item) => item.id === decision.districtId)?.name ?? decision.districtId : "Весь город"} · {measure?.cost ?? 0} ед.</span></div><button className="text-button" type="button" onClick={() => { setDecisions((current) => current.filter((item) => item.measureId !== decision.measureId)); setResult(null); setCalculationError([]); }} aria-label={`Удалить ${measure?.name ?? decision.measureId}`}>Убрать</button></li>;
            })}</ol>}
            <div className="scenario-actions"><button className="primary-button" type="button" disabled={!bootstrap || calculating || decisions.length !== bootstrap.requiredDecisionCount} onClick={() => void calculate()}>{calculating ? "Расчёт…" : "Рассчитать Score"}</button>{decisions.length > 0 && <button className="text-button" type="button" onClick={() => { setDecisions([]); setResult(null); setCalculationError([]); }}>Очистить</button>}</div>
            {calculationError.length > 0 && <div className="error-message" role="alert">{calculationError.map((message) => <p key={message}>{message}</p>)}</div>}
            {result && <div className="simulation-result">
              <h3>Результат: {number(result.displayScore, 2)}</h3>
              <p>Изменение к базе: {result.scoreDelta >= 0 ? "+" : ""}{number(result.scoreDelta, 2)} · бюджет {result.budget.spent}/{result.budget.limit}</p>
              <p>Средний балл районов: {number(result.summary.dAvg, 2)} · слабейший район: {result.summary.weakestDistrictName} ({number(result.summary.dMin, 2)}) · критических показателей: {result.summary.nCrit}</p>
              <h3>Районы после расчёта</h3><div className="metric-list">{result.districts.map((item) => <div key={item.id}><span>{item.name}</span><strong>{number(item.scoreAfter, 2)} ({item.scoreDelta >= 0 ? "+" : ""}{number(item.scoreDelta, 2)})</strong></div>)}</div>
              <h3>Объяснение</h3><p>{result.explanation.summary}</p>
              {result.explanation.strengths.length > 0 && <><h4>Сильные стороны</h4><ul>{result.explanation.strengths.map((item) => <li key={item}>{item}</li>)}</ul></>}
              {result.explanation.risks.length > 0 && <><h4>Риски</h4><ul>{result.explanation.risks.map((item) => <li key={item}>{item}</li>)}</ul></>}
              {result.synergies.length > 0 && <p>Синергии: {result.synergies.map((item) => `${item.measureIds.join(" + ")} → ${item.metric} +${item.bonus} (${districts.find((district) => district.id === item.districtId)?.name ?? item.districtId})`).join("; ")}</p>}
            </div>}
          </>}
        </div>
      </aside>
    </main>
  );
}
