import * as THREE from "three";
import type { MeasureId } from "./catalog";

// All dimensions are illustration units. No geographic placement is implied.
type Palette = "grass" | "road" | "blue" | "red" | "cream" | "dark" | "yellow" | "white" | "brown" | "water";
const colors: Record<Palette, number> = {
  grass: 0x74b987, road: 0x455665, blue: 0x449dc9, red: 0xea735b,
  cream: 0xf1d8aa, dark: 0x233e52, yellow: 0xffc75b,
  white: 0xf7f5ec, brown: 0x98745b, water: 0x77c8e5,
};

function box(parent: THREE.Group, x: number, y: number, z: number, w: number, h: number, d: number, color: Palette) {
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), new THREE.MeshStandardMaterial({ color: colors[color], roughness: 0.8 }));
  mesh.position.set(x, y, z);
  parent.add(mesh);
  return mesh;
}
function cylinder(parent: THREE.Group, x: number, y: number, z: number, radius: number, height: number, color: Palette, sides = 10) {
  const mesh = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, height, sides), new THREE.MeshStandardMaterial({ color: colors[color], roughness: 0.8 }));
  mesh.position.set(x, y, z);
  parent.add(mesh);
  return mesh;
}
function sphere(parent: THREE.Group, x: number, y: number, z: number, radius: number, color: Palette) {
  const mesh = new THREE.Mesh(new THREE.IcosahedronGeometry(radius, 1), new THREE.MeshStandardMaterial({ color: colors[color], roughness: 0.8 }));
  mesh.position.set(x, y, z);
  parent.add(mesh);
}
function tree(parent: THREE.Group, x: number, z: number, size = 1) {
  cylinder(parent, x, 0.35 * size, z, 0.07 * size, 0.7 * size, "brown", 6);
  sphere(parent, x, 0.9 * size, z, 0.38 * size, "grass");
}
function building(parent: THREE.Group, x: number, z: number, color: Palette, height = 1.25) {
  box(parent, x, height / 2, z, 1.8, height, 1.25, color);
  box(parent, x, height + 0.08, z, 2, 0.16, 1.42, "dark");
  for (const dx of [-0.55, 0, 0.55]) box(parent, x + dx, height * 0.56, z + 0.635, 0.28, 0.34, 0.035, "blue");
  box(parent, x, 0.3, z + 0.65, 0.35, 0.6, 0.04, "dark");
}
function road(parent: THREE.Group) {
  box(parent, 0, 0.025, 0, 3.7, 0.05, 1.85, "road");
  for (const x of [-1.2, -0.4, 0.4, 1.2]) box(parent, x, 0.058, 0, 0.4, 0.008, 0.045, "white");
}
function bus(parent: THREE.Group, x = 0, z = 0) {
  box(parent, x, 0.42, z, 1.5, 0.7, 0.55, "yellow");
  box(parent, x, 0.58, z + 0.285, 1.18, 0.22, 0.025, "blue");
  for (const dx of [-0.48, 0.48]) {
    cylinder(parent, x + dx, 0.11, z - 0.25, 0.13, 0.12, "dark").rotation.x = Math.PI / 2;
    cylinder(parent, x + dx, 0.11, z + 0.25, 0.13, 0.12, "dark").rotation.x = Math.PI / 2;
  }
}
function signal(parent: THREE.Group, x: number, z: number) {
  cylinder(parent, x, 0.8, z, 0.055, 1.6, "dark", 6);
  box(parent, x, 1.85, z, 0.32, 0.68, 0.28, "dark");
  for (const [i, color] of (["red", "yellow", "grass"] as const).entries()) sphere(parent, x, 2.07 - i * 0.22, z + 0.15, 0.075, color);
}
function lamp(parent: THREE.Group, x: number, z: number) {
  cylinder(parent, x, 0.95, z, 0.055, 1.9, "dark", 6);
  box(parent, x + 0.23, 1.9, z, 0.52, 0.06, 0.06, "dark");
  box(parent, x + 0.45, 1.85, z, 0.24, 0.06, 0.16, "yellow");
}
function pipe(parent: THREE.Group, x: number, z: number, color: Palette) {
  const mesh = cylinder(parent, x, 0.17, z, 0.13, 3.3, color, 10);
  mesh.rotation.z = Math.PI / 2;
}
function plus(parent: THREE.Group, x: number, y: number, z: number, color: Palette) {
  box(parent, x, y, z, 0.18, 0.62, 0.08, color);
  box(parent, x, y, z + 0.002, 0.62, 0.18, 0.08, color);
}

/** Owns its geometries and materials. Call disposeMeasureScene when replacing it. */
export function createMeasureScene(id: MeasureId): THREE.Group {
  const scene = new THREE.Group();
  scene.name = id;
  const city = ["M2", "M6", "M12", "M14"].includes(id);
  box(scene, 0, -0.11, 0, 4.2, 0.2, 3.3, city ? "blue" : "grass");
  switch (id) {
    case "M1":
      road(scene); box(scene, 0, 0.065, -0.68, 3.7, 0.012, 0.08, "yellow"); bus(scene, 0, -0.45); break;
    case "M2":
      road(scene); signal(scene, -1.15, -0.35); signal(scene, 1.15, 0.35); break;
    case "M3":
      for (const z of [-0.5, 0.5]) box(scene, 0, 0.08, z, 3.8, 0.08, 0.08, "dark");
      for (const x of [-1.35, -0.45, 0.45, 1.35]) box(scene, x, 0.055, 0, 0.1, 0.06, 1.2, "brown");
      box(scene, 0, 0.53, 0, 2.1, 0.72, 0.8, "white");
      box(scene, 0, 0.65, 0.42, 1.75, 0.3, 0.04, "blue"); break;
    case "M4":
      box(scene, 0, 0.01, 0, 0.48, 0.03, 3.1, "cream");
      for (const [x,z] of [[-1,-0.8],[1,-0.8],[-1,0.85],[1,0.85]]) tree(scene,x,z);
      box(scene, 0.75, 0.19, 0.25, 0.72, 0.12, 0.2, "brown"); break;
    case "M5":
      building(scene, -0.8, -0.15, "cream", 0.95);
      cylinder(scene, 1.05, 0.5, 0.2, 0.33, 1.0, "blue");
      pipe(scene, 0.3, 0.75, "yellow"); sphere(scene, 1.05, 1.2, 0.2, 0.23, "grass"); break;
    case "M6":
      for (const [x,z] of [[-1.4,-0.65],[-0.7,0.6],[0,-0.65],[0.7,0.6],[1.4,-0.65]]) tree(scene,x,z,0.85);
      break;
    case "M7":
      building(scene, -0.75, -0.2, "cream", 1.15);
      building(scene, 1, 0.25, "yellow", 0.75);
      box(scene, -0.65, 1.35, -0.2, 0.48, 0.12, 0.1, "red"); break;
    case "M8":
      building(scene, 0, 0, "white", 1.35); plus(scene, 0, 1.0, 0.73, "red"); break;
    case "M9":
      box(scene, 0, 0.025, 0, 3, 0.05, 2.25, "blue");
      for (const x of [-1.35,1.35]) {
        cylinder(scene,x,0.5,0,0.035,1,"white",6);
        box(scene,x,1,0,0.45,0.05,0.05,"white");
      }
      box(scene, 0, 0.06, 0, 0.06, 0.01, 2.1, "white"); break;
    case "M10":
      lamp(scene, -1.2, 0); lamp(scene, 1.2, 0);
      box(scene, 0, 1.55, 0, 0.58, 0.32, 0.45, "dark");
      cylinder(scene, 0, 0.7, 0, 0.05, 1.4, "dark");
      sphere(scene, 0, 1.52, 0.3, 0.12, "blue"); break;
    case "M11":
      road(scene);
      for (const x of [-0.9,-0.45,0,0.45,0.9]) box(scene,x,0.06,0.15,0.23,0.012,1.35,"white");
      signal(scene, -1.55, -0.7); break;
    case "M12":
      box(scene, 0, 0.85, 0, 1.25, 1.5, 0.22, "dark");
      box(scene, 0, 0.9, 0.13, 0.95, 1.08, 0.02, "white");
      for (const y of [0.55,0.85,1.15]) box(scene,0,y,0.15,0.6,0.075,0.02,"blue");
      for (const x of [-1.4,1.4]) { sphere(scene,x,0.75,0,0.34,"yellow"); box(scene,x,0.25,0,0.05,0.4,0.05,"dark"); }
      break;
    case "M13":
      box(scene,0,0.03,0,3.6,0.06,1.7,"brown");
      pipe(scene,0,-0.42,"blue"); pipe(scene,0,0.42,"red");
      for (const x of [-1.3,1.3]) cylinder(scene,x,0.28,0,0.21,0.45,"dark"); break;
    case "M14":
      box(scene,0,0.34,0,2.1,0.58,0.95,"white");
      box(scene,-0.55,0.86,0,0.72,0.58,0.92,"white");
      box(scene,-0.52,0.85,0.48,0.47,0.25,0.03,"blue");
      for (const x of [-0.65,0.7]) cylinder(scene,x,0.12,0.5,0.2,0.16,"dark").rotation.x=Math.PI/2;
      box(scene,0.35,0.66,0.49,0.52,0.12,0.03,"red");
      sphere(scene,0,1.2,0,0.2,"yellow"); break;
  }
  return scene;
}

export function disposeMeasureScene(group: THREE.Group) {
  group.traverse((object) => {
    if (!(object instanceof THREE.Mesh)) return;
    object.geometry.dispose();
    const materials = Array.isArray(object.material) ? object.material : [object.material];
    materials.forEach((material) => material.dispose());
  });
}
