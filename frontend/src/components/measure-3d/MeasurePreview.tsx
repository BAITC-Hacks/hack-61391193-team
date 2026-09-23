"use client";

import { useEffect, useRef, useState } from "react";
import type { MeasureId } from "./catalog";
import styles from "./measure-preview.module.css";

type Props = { measureId: MeasureId; variant?: "default" | "card" };

/** Static diorama. Only visible previews own a WebGL context. */
export default function MeasurePreview({ measureId, variant = "default" }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === "undefined");
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const element = host.current;
    if (!element) return;
    if (typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting), { rootMargin: "80px 0px" });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const element = host.current;
    if (!element || !visible || failed) return;
    let cancelled = false;
    let cleanup = () => {};

    void Promise.all([import("three"), import("./factory")]).then(([THREE, factory]) => {
      if (cancelled) return;
      let renderer: import("three").WebGLRenderer;
      try {
        renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "low-power" });
      } catch {
        setFailed(true);
        return;
      }

      let model: import("three").Group | undefined;
      let observer: ResizeObserver | undefined;
      try {
        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
        renderer.outputColorSpace = THREE.SRGBColorSpace;
        element.appendChild(renderer.domElement);

        const scene = new THREE.Scene();
        model = factory.createMeasureScene(measureId);
        scene.add(model);
        scene.add(new THREE.HemisphereLight(0xffffff, 0x5c7490, 2.2));
        const sun = new THREE.DirectionalLight(0xffffff, 2.3);
        sun.position.set(4, 8, 5);
        scene.add(sun);
        const camera = new THREE.PerspectiveCamera(35, 1, 0.1, 30);
        camera.position.set(6.4, 5.5, 7.3);
        camera.lookAt(0, 0.65, 0);

        const render = () => {
          const width = Math.max(1, element.clientWidth);
          const height = Math.max(1, element.clientHeight);
          renderer.setSize(width, height, false);
          camera.aspect = width / height;
          camera.updateProjectionMatrix();
          renderer.render(scene, camera);
        };
        observer = new ResizeObserver(render);
        observer.observe(element);
        render();
      } catch {
        setFailed(true);
      }
      cleanup = () => {
        observer?.disconnect();
        if (model) factory.disposeMeasureScene(model);
        renderer.dispose();
        renderer.forceContextLoss();
        renderer.domElement.remove();
      };
      if (cancelled || !model || !observer) cleanup();
    }).catch(() => {
      if (!cancelled) setFailed(true);
    });

    return () => { cancelled = true; cleanup(); };
  }, [measureId, visible, failed]);

  return <div ref={host} className={`${styles.canvas} ${variant === "card" ? styles.card : ""}`} role="img" aria-label={`Условная 3D-визуализация мероприятия ${measureId}`}>
    {failed && <span className={styles.fallback}>3D-просмотр недоступен</span>}
  </div>;
}
