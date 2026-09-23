"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import InitiativeModal, { type InitiativeSelection } from "./initiative-modal";
import styles from "./district-map.module.css";
import { ResultsOverlay, ScenarioHud, decisionFromInitiative, selectedDecisions } from "./scenario-ui";
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
  const popupRef = useRef<HTMLElement>(null);
  const mapRef = useRef<Map | null>(null);
  const selectedFeature = useRef<{ source: string; id: string | number } | null>(null);
  const hoveredFeature = useRef<{ source: string; id: string | number } | null>(null);
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
  const [selectionPoint, setSelectionPoint] = useState<{ x: number; y: number } | null>(null);
  const [popupPosition, setPopupPosition] = useState<{ x: number; y: number } | null>(null);
  const [districtDetail, setDistrictDetail] = useState<District | null>(null);
  const [districtMeasures, setDistrictMeasures] = useState<DistrictMeasure[]>([]);
  const [initiativeDialog, setInitiativeDialog] = useState<{ districtId: string; districtName: string } | null>(null);
  const [boundaryMode, setBoundaryMode] = useState<"administrative" | "model">("administrative");
  const [visibleLayers, setVisibleLayers] = useState<Record<LayerKey, boolean>>({ city: false, parks: false, roads: false, pois: false });
  const [layerLoading, setLayerLoading] = useState<LayerKey | null>(null);
  const [poiKind, setPoiKind] = useState("school");
  const [decisions, setDecisions] = useState<Decision[]>([]);
  const [result, setResult] = useState<SimulationResult | null>(null);
  const [showResults, setShowResults] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [calculationError, setCalculationError] = useState<string[]>([]);
  const calculationId = useRef(0);

  function clearSelection() {
    setInitiativeDialog(null);
    const map = mapRef.current;
    if (map && selectedFeature.current) {
      map.setFeatureState(selectedFeature.current, { selected: false });
    }
    selectedFeature.current = null;
    setSelectionPoint(null);
    setPopupPosition(null);
    setSelected(null);
    setDistrictDetail(null);
    setDistrictMeasures([]);
  }

  function chooseFeature(source: string, id: string | number, name: string, districtId: string | null, point: { x: number; y: number }) {
    setInitiativeDialog(null);
    const map = mapRef.current;
    if (!map) return;
    const sameFeature = selectedFeature.current?.source === source && selectedFeature.current.id === id;
    if (selectedFeature.current) map.setFeatureState(selectedFeature.current, { selected: false });
    selectedFeature.current = { source, id };
    map.setFeatureState({ source, id }, { selected: true });
    setSelected({ id: districtId, name });
    setPopupPosition(null);
    setSelectionPoint(point);
    if (!sameFeature) {
      setDistrictDetail(null);
      setDistrictMeasures([]);
    }
  }

  function hoverFeature(source: string, id: string | number | null) {
    const map = mapRef.current;
    if (!map) return;
    if (hoveredFeature.current && (hoveredFeature.current.source !== source || hoveredFeature.current.id !== id)) {
      map.setFeatureState(hoveredFeature.current, { hover: false });
      hoveredFeature.current = null;
    }
    if (id !== null && !hoveredFeature.current) {
      hoveredFeature.current = { source, id };
      map.setFeatureState(hoveredFeature.current, { hover: true });
    }
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
        console.error("Failed to load simulator data", error);
        if (!controller.signal.aborted) setApiError("Сервис временно недоступен");
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
      if (!controller.signal.aborted) { setDistrictDetail(detail); setDistrictMeasures(available); setApiError(null); }
    }).catch((error) => {
      console.error("Failed to load district", error);
      if (!controller.signal.aborted) setApiError("Не удалось загрузить данные района");
    });
    return () => controller.abort();
  }, [selected?.id, bootstrap]);

  useLayoutEffect(() => {
    if (!selected || !selectionPoint || !popupRef.current || !mapContainer.current) return;
    const popup = popupRef.current;
    const container = mapContainer.current;
    const click = selectionPoint;
    const occupiedElements = [...document.querySelectorAll<HTMLElement>(".map-brand, .score-hud, .budget-hud, .turns-hud, .turn-counter, .map-tools, .maplibregl-ctrl-top-right")];
    function positionPopup() {
      if (window.innerWidth <= 760) return;
      const width = container.clientWidth;
      const height = container.clientHeight;
      const cardWidth = popup.offsetWidth;
      const cardHeight = popup.offsetHeight;
      const top = 112;
      const bottom = 126;
      const left = 24;
      const right = 24;
      const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(value, Math.max(min, max)));
      const obstacles = occupiedElements
        .filter((element) => element.getClientRects().length > 0)
        .map((element) => element.getBoundingClientRect());
      const base = container.getBoundingClientRect();
      const horizontal = [click.x + 18, click.x - cardWidth - 18, left, width - right - cardWidth,
        ...obstacles.flatMap((obstacle) => [obstacle.right - base.left + 12, obstacle.left - base.left - cardWidth - 12])];
      const vertical = [click.y + 18, click.y - cardHeight - 18, top, height - bottom - cardHeight,
        ...obstacles.flatMap((obstacle) => [obstacle.bottom - base.top + 12, obstacle.top - base.top - cardHeight - 12])];
      const choices = horizontal.flatMap((x) => vertical.map((y) => ({
          x: clamp(x, left, width - right - cardWidth),
          y: clamp(y, top, height - bottom - cardHeight),
        })));
      const scored = choices.map((choice) => {
        const rect = { left: base.left + choice.x, right: base.left + choice.x + cardWidth, top: base.top + choice.y, bottom: base.top + choice.y + cardHeight };
        const overlap = obstacles.reduce((sum, obstacle) => sum + Math.max(0, Math.min(rect.right, obstacle.right) - Math.max(rect.left, obstacle.left)) * Math.max(0, Math.min(rect.bottom, obstacle.bottom) - Math.max(rect.top, obstacle.top)), 0);
        return { ...choice, score: overlap * 10 + Math.abs(choice.x - click.x) + Math.abs(choice.y - click.y) };
      });
      scored.sort((a, b) => a.score - b.score);
      setPopupPosition({ x: scored[0].x, y: scored[0].y });
    }
    positionPopup();
    const observer = new ResizeObserver(positionPopup);
    observer.observe(popup);
    occupiedElements.forEach((element) => observer.observe(element));
    window.addEventListener("resize", positionPopup);
    return () => { observer.disconnect(); window.removeEventListener("resize", positionPopup); };
  }, [selected, selectionPoint, initiativeDialog]);

  useEffect(() => {
    if (!mapContainer.current) return;
    let disposed = false;
    let map: Map | null = null;
    const controller = new AbortController();
    function fitDistricts() {
      if (!map || !boundsRef.current || !mapContainer.current) return;
      const width = mapContainer.current.clientWidth;
      const height = mapContainer.current.clientHeight;
      map.fitBounds(boundsRef.current, {
        padding: width <= 760
          ? { top: 110, right: 22, bottom: Math.min(height * 0.28, 180), left: 22 }
          : { top: 95, right: 56, bottom: 140, left: 56 },
        maxZoom: 11, duration: 0,
      });
    }
    function resizeMap() { map?.resize(); fitDistricts(); }
    window.addEventListener("resize", resizeMap);
    const observer = new ResizeObserver(resizeMap);
    observer.observe(mapContainer.current);

    async function initialize() {
      const maplibregl = await import("maplibre-gl");
      if (disposed || !mapContainer.current) return;
      maplibregl.setWorkerUrl("/vendor/maplibre/maplibre-gl-worker.mjs");
      map = new maplibregl.Map({
        container: mapContainer.current,
        style: "https://tiles.openfreemap.org/styles/liberty",
        center: [71.43, 51.17], zoom: 9,
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
            paint: { "fill-color": ["case", ["boolean", ["feature-state", "selected"], false], "#ea7b32", "#3479a5"], "fill-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 0.68, ["boolean", ["feature-state", "hover"], false], 0.54, 0.34] } });
          map.addLayer({ id: "administrative-outline", type: "line", source: "administrative",
            paint: { "line-color": ["case", ["boolean", ["feature-state", "selected"], false], "#d85b22", ["boolean", ["feature-state", "hover"], false], "#155c78", "#173f5a"],
              "line-width": ["case", ["boolean", ["feature-state", "selected"], false], 4, ["boolean", ["feature-state", "hover"], false], 3, 2.2] } });
          adminBounds.current = getBounds(data);
          boundsRef.current = adminBounds.current;
          fitDistricts();
          map.on("click", "administrative-fill", (event) => {
            const properties = event.features?.[0]?.properties;
            if (typeof properties?.objectid === "number" && typeof properties.name_object === "string") {
              chooseFeature("administrative", properties.objectid, properties.name_object, districtIds[properties.name_object] ?? null, event.point);
            }
          });
          map.on("mouseenter", "administrative-fill", () => { if (map) map.getCanvas().style.cursor = "pointer"; });
          map.on("mousemove", "administrative-fill", (event) => hoverFeature("administrative", (event.features?.[0]?.id as string | number | undefined) ?? null));
          map.on("mouseleave", "administrative-fill", () => { if (map) map.getCanvas().style.cursor = ""; hoverFeature("administrative", null); });
          map.on("click", (event) => {
            if (!map?.queryRenderedFeatures(event.point, { layers: ["administrative-fill", "model-fill"].filter((layer) => map?.getLayer(layer)) }).length) clearSelection();
          });
          setMapReady(true);
        } catch (error) {
          console.error("Failed to load map boundaries", error);
          if (!disposed) setMapError("Не удалось загрузить карту");
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
      paint: { "fill-color": ["case", ["boolean", ["feature-state", "selected"], false], "#ea7b32", "#3479a5"], "fill-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 0.68, ["boolean", ["feature-state", "hover"], false], 0.54, 0.34] } });
    map.addLayer({ id: "model-outline", type: "line", source: "model", layout: { visibility: "none" },
      paint: { "line-color": ["case", ["boolean", ["feature-state", "selected"], false], "#d85b22", ["boolean", ["feature-state", "hover"], false], "#155c78", "#173f5a"],
        "line-width": ["case", ["boolean", ["feature-state", "selected"], false], 4, ["boolean", ["feature-state", "hover"], false], 3, 2.2] } });
    modelBounds.current = getBounds(modelGeojson);
    map.on("click", "model-fill", (event) => {
      const properties = event.features?.[0]?.properties;
      if (typeof properties?.district_id === "string" && typeof properties.name_ru === "string") {
        chooseFeature("model", properties.district_id, properties.name_ru, properties.district_id, event.point);
      }
    });
    map.on("mouseenter", "model-fill", () => { map.getCanvas().style.cursor = "pointer"; });
    map.on("mousemove", "model-fill", (event) => hoverFeature("model", (event.features?.[0]?.id as string | number | undefined) ?? null));
    map.on("mouseleave", "model-fill", () => { map.getCanvas().style.cursor = ""; hoverFeature("model", null); });
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
    hoverFeature(boundaryMode === "model" ? "model" : "administrative", null);
    setBoundaryMode(next);
    for (const layer of ["administrative-fill", "administrative-outline"]) map.setLayoutProperty(layer, "visibility", next === "administrative" ? "visible" : "none");
    for (const layer of ["model-fill", "model-outline"]) map.setLayoutProperty(layer, "visibility", next === "model" ? "visible" : "none");
    boundsRef.current = next === "model" ? modelBounds.current : adminBounds.current;
    const size = mapContainer.current;
    if (size && boundsRef.current) map.fitBounds(boundsRef.current, {
      padding: size.clientWidth <= 760
        ? { top: 110, right: 22, bottom: Math.min(size.clientHeight * 0.28, 180), left: 22 }
        : { top: 95, right: 56, bottom: 140, left: 56 },
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
        console.error("Failed to load map layer", layer, error);
        setApiError("Сервис временно недоступен");
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

  const closeInitiativeDialog = useCallback(() => setInitiativeDialog(null), []);

  function addDecision(selection: InitiativeSelection) {
    const measure = districtMeasures.find((item) => item.id === selection.initiativeId);
    if (!measure || selection.districtId !== selected?.id) return;
    if (decisions.length >= (bootstrap?.requiredDecisionCount ?? 5) || decisions.some((item) => item.measureId === measure.id)) return;
    const decision = decisionFromInitiative(selection, measures);
    if (!decision) return;
    calculationId.current += 1;
    setDecisions((current) => [...current, decision]);
    setResult(null); setCalculationError([]); setCalculating(false);
  }

  async function calculate() {
    if (!bootstrap || decisions.length !== bootstrap.requiredDecisionCount) return;
    const requestId = ++calculationId.current;
    setCalculating(true); setCalculationError([]);
    try {
      const response = await apiPost<SimulationResult>(bootstrap.api.calculate, { decisions });
      if (requestId !== calculationId.current) return;
      setResult(response);
      setShowResults(true);
    } catch (error) {
      if (requestId !== calculationId.current) return;
      console.error("Failed to calculate scenario", error);
      if (error instanceof ApiError) setCalculationError(error.fields.length ? error.fields.map((item) => item.message) : [error.status < 500 ? error.message : "Сервис временно недоступен"]);
      else setCalculationError(["Сервис временно недоступен"]);
    } finally { if (requestId === calculationId.current) setCalculating(false); }
  }

  const selectedStats = stats.find((item) => item.district_id === selected?.id);
  const selectedResult = (result ?? baseline)?.districts.find((item) => item.id === selected?.id);
  const selections = selectedDecisions(decisions, measures, districts);

  function removeDecision(measureId: string) {
    calculationId.current += 1;
    setDecisions((current) => current.filter((item) => item.measureId !== measureId));
    setResult(null); setCalculationError([]); setShowResults(false); setCalculating(false);
  }

  function newScenario() {
    calculationId.current += 1;
    setDecisions([]); setResult(null); setCalculationError([]); setShowResults(false); setCalculating(false);
    clearSelection();
  }

  return (
    <main className="map-screen" aria-label="Карта районов Астаны">
      <div ref={mapContainer} className="map-container" aria-label="Интерактивная карта" />
      <header className="map-brand">
        <h1>Аким на 5 часов</h1>
        <p>Астана</p>
      </header>
      <section className="score-hud floating-hud" aria-label="Score города"><span className="hud-label">Score города</span><strong>{baseline || bootstrap ? number((result ?? baseline)?.displayScore ?? bootstrap?.baselineScore ?? 0, 2) : "—"}</strong></section>
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
      {(mapError || apiError) && <div className={styles.mapNotice} role="alert">{mapError || apiError}</div>}
      {selected && selectionPoint && <aside ref={popupRef} className={`${styles.selectionCard} ${initiativeDialog ? styles.catalogCard : ""}`} aria-live="polite" style={{
        left: popupPosition?.x ?? 0, top: popupPosition?.y ?? 0, visibility: popupPosition ? "visible" : "hidden",
      }}>
        <div className={styles.cardHeading}>
          <div><span className={styles.eyebrow}>{initiativeDialog ? "КАТАЛОГ МЕРОПРИЯТИЙ" : "РАЙОН АСТАНЫ"}</span><h2>{selected.name}</h2></div>
          <button className={styles.closeButton} type="button" onClick={clearSelection} aria-label="Закрыть карточку района">×</button>
        </div>
        {initiativeDialog && bootstrap ? <InitiativeModal
          key={initiativeDialog.districtId}
          open
          districtId={initiativeDialog.districtId}
          districtName={initiativeDialog.districtName}
          initiatives={districtMeasures}
          decisions={decisions}
          allMeasures={measures}
          rules={bootstrap}
          onSelectInitiative={addDecision}
          onClose={closeInitiativeDialog}
        /> : selected.id ? <>
          <div className={styles.scoreBlock}><span>Score района</span><strong>{districtDetail ? number(selectedResult?.scoreAfter ?? districtDetail.baselineScore, 2) : "—"}</strong></div>
          <div className={styles.facts}>
            <div><span>Площадь</span><strong>{selectedStats ? `${number(selectedStats.area_km2)} км²` : "—"}</strong></div>
            <div><span>Школы</span><strong>{typeof selectedStats?.school_count === "number" ? selectedStats.school_count : "—"}</strong></div>
            <div><span>Доля зелени</span><strong>{typeof selectedStats?.green_share_pct === "number" ? `${number(selectedStats.green_share_pct)}%` : "—"}</strong></div>
          </div>
          <button className={styles.actionButton} type="button" disabled={!bootstrap || !districtDetail} onClick={() => {
            if (selected.id) setInitiativeDialog({ districtId: selected.id, districtName: selected.name });
          }}>{districtDetail ? "Провести мероприятие" : apiError ? "Мероприятия недоступны" : "Загрузка мероприятий…"} <span aria-hidden="true">↗</span></button>
        </> : <p className={styles.unavailable}>Этот район показан на административной карте, но не входит в симулятор.</p>}
      </aside>}
      <ScenarioHud selections={selections} requiredCount={bootstrap?.requiredDecisionCount ?? 5} budgetLimit={bootstrap?.budgetLimit ?? 100} calculating={calculating} ready={!!bootstrap} errors={calculationError} onRemove={removeDecision} onCalculate={() => void calculate()} />
      {result && showResults && <ResultsOverlay result={result} selections={selections} bootstrap={bootstrap} onViewDistricts={() => setShowResults(false)} onNewScenario={newScenario} />}
    </main>
  );
}
