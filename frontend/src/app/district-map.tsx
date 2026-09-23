"use client";

import { useEffect, useRef, useState } from "react";
import type { FeatureCollection, MultiPolygon, Polygon } from "geojson";

type Districts = FeatureCollection<Polygon | MultiPolygon, {
  objectid: number;
  name_object: string;
  name_object_kaz: string;
}>;

function getBounds(data: Districts): [[number, number], [number, number]] {
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity;
  for (const feature of data.features) {
    const polygons = feature.geometry.type === "Polygon"
      ? [feature.geometry.coordinates]
      : feature.geometry.coordinates;
    for (const polygon of polygons) {
      for (const ring of polygon) {
        for (const [longitude, latitude] of ring) {
          west = Math.min(west, longitude);
          south = Math.min(south, latitude);
          east = Math.max(east, longitude);
          north = Math.max(north, latitude);
        }
      }
    }
  }
  return [[west, south], [east, north]];
}

export default function DistrictMap() {
  const containerRef = useRef<HTMLDivElement>(null);
  const [selectedDistrict, setSelectedDistrict] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    let disposed = false;
    let map: import("maplibre-gl").Map | undefined;
    const controller = new AbortController();

    async function initialize() {
      const maplibregl = await import("maplibre-gl");
      if (disposed || !containerRef.current) return;
      maplibregl.setWorkerUrl("/vendor/maplibre/maplibre-gl-worker.mjs");
      const instance = new maplibregl.Map({
        container: containerRef.current,
        style: "https://tiles.openfreemap.org/styles/liberty",
        center: [71.43, 51.17],
        zoom: 9,
        attributionControl: {},
      });
      map = instance;
      instance.addControl(new maplibregl.NavigationControl(), "top-right");

      instance.on("load", async () => {
        try {
          const response = await fetch("/data/astana-districts.geojson", { signal: controller.signal });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          const data = (await response.json()) as Districts;
          if (disposed || !map) return;

          map.addSource("districts", { type: "geojson", data, promoteId: "objectid" });
          map.addLayer({
            id: "district-fill", type: "fill", source: "districts",
            paint: {
              "fill-color": ["case", ["boolean", ["feature-state", "selected"], false], "#ea7b32", "#3479a5"],
              "fill-opacity": ["case", ["boolean", ["feature-state", "selected"], false], 0.58, 0.31],
            },
          });
          map.addLayer({
            id: "district-outline", type: "line", source: "districts",
            paint: { "line-color": "#163b55", "line-width": 2.3, "line-opacity": 0.95 },
          });
          map.addLayer({
            id: "district-selected-outline", type: "line", source: "districts",
            filter: ["==", ["get", "objectid"], -1],
            paint: { "line-color": "#d85b22", "line-width": 4 },
          });
          map.fitBounds(getBounds(data), { padding: 44, maxZoom: 11, duration: 0 });

          let selectedId: number | null = null;
          map.on("click", "district-fill", (event) => {
            const feature = event.features?.[0];
            const id = feature?.properties?.objectid;
            const name = feature?.properties?.name_object;
            if (typeof id !== "number" || typeof name !== "string" || !map) return;
            if (selectedId !== null) {
              map.setFeatureState({ source: "districts", id: selectedId }, { selected: false });
            }
            selectedId = id;
            map.setFeatureState({ source: "districts", id }, { selected: true });
            map.setFilter("district-selected-outline", ["==", ["get", "objectid"], id]);
            setSelectedDistrict(name);
          });
          map.on("mouseenter", "district-fill", () => {
            if (map) map.getCanvas().style.cursor = "pointer";
          });
          map.on("mouseleave", "district-fill", () => {
            if (map) map.getCanvas().style.cursor = "";
          });
        } catch {
          if (!disposed) setError("Не удалось загрузить границы районов.");
        }
      });
    }

    initialize().catch(() => {
      if (!disposed) setError("Не удалось открыть карту.");
    });
    return () => {
      disposed = true;
      controller.abort();
      map?.remove();
    };
  }, []);

  return (
    <section className="map-layout" aria-label="Карта районов Астаны">
      <div className="map-panel">
        <div ref={containerRef} className="map-container" aria-label="Интерактивная карта" />
      </div>
      <aside className="selection-panel" aria-live="polite">
        <span className="selection-kicker">ВЫБРАННЫЙ РАЙОН</span>
        <h2>{selectedDistrict ?? "Выберите район"}</h2>
        <p>{error ?? (selectedDistrict
          ? "Район выделен на карте. Нажмите на другой район, чтобы изменить выбор."
          : "Нажмите на любой район внутри его границ.")}</p>
      </aside>
    </section>
  );
}
