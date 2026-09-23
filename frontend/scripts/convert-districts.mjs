import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { arcgisToGeoJSON } from "@terraformer/arcgis";

const input = process.argv[2];

if (!input) {
  throw new Error("Usage: node scripts/convert-districts.mjs <arcgis-query.json>");
}

const source = JSON.parse(await readFile(resolve(input), "utf8"));
if (source.error || source.spatialReference?.wkid !== 4326 || source.exceededTransferLimit) {
  throw new Error("ArcGIS response is incomplete or is not in EPSG:4326");
}

const geojson = arcgisToGeoJSON({ features: source.features }, "objectid");
const expected = ["Алматы", "Байконур", "Есиль", "Нура", "Сарыарка", "Сарайшык"];
const names = geojson.features?.map((feature) => feature.properties?.name_object).sort();

if (
  geojson.type !== "FeatureCollection" ||
  JSON.stringify(names) !== JSON.stringify(expected.sort()) ||
  geojson.features.some((feature) => !["Polygon", "MultiPolygon"].includes(feature.geometry?.type))
) {
  throw new Error("Unexpected districts or geometry in ArcGIS response");
}

const output = resolve("public/data/astana-districts.geojson");
await mkdir(dirname(output), { recursive: true });
await writeFile(output, `${JSON.stringify(geojson)}\n`);
console.log(`Saved ${geojson.features.length} districts to ${output}`);
