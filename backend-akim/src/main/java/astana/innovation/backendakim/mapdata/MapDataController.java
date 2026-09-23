package astana.innovation.backendakim.mapdata;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map")
public class MapDataController {
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
    private static final String ATTRIBUTION = "© OpenStreetMap contributors, Overture Maps Foundation";
    private static final String SOURCE = "Overture Maps";
    private static final String RELEASE = "2026-08-19.0";

    private final MapDataService mapDataService;

    public MapDataController(MapDataService mapDataService) {
        this.mapDataService = mapDataService;
    }

    @GetMapping("/layers")
    public MapLayersResponse getLayers() {
        return new MapLayersResponse(SOURCE, RELEASE, ATTRIBUTION, mapDataService.getManifest());
    }

    @GetMapping("/districts")
    public ResponseEntity<byte[]> getDistricts() {
        return layerResponse(MapLayer.DISTRICTS);
    }

    @GetMapping("/city-boundary")
    public ResponseEntity<byte[]> getCityBoundary() {
        return layerResponse(MapLayer.CITY_BOUNDARY);
    }

    @GetMapping("/district-stats")
    public ResponseEntity<byte[]> getDistrictStats() {
        return layerResponse(MapLayer.DISTRICT_STATS);
    }

    @GetMapping("/pois")
    public ResponseEntity<byte[]> getPois() {
        return layerResponse(MapLayer.POIS);
    }

    @GetMapping("/parks")
    public ResponseEntity<byte[]> getParks() {
        return layerResponse(MapLayer.PARKS);
    }

    @GetMapping("/roads")
    public ResponseEntity<byte[]> getRoads() {
        return layerResponse(MapLayer.ROADS);
    }

    @ApiResponse(responseCode = "200", description = "GeoJSON ЛРТ для M3 с метаданными об источнике и неполноте",
            content = @Content(mediaType = "application/geo+json", schema = @Schema(type = "object")))
    @GetMapping(value = "/lrt", produces = "application/geo+json")
    public ResponseEntity<byte[]> getLrt() {
        return layerResponse(MapLayer.LRT);
    }

    private ResponseEntity<byte[]> layerResponse(MapLayer layer) {
        byte[] body = mapDataService.getLayer(layer);
        return ResponseEntity.ok()
                .contentType(layer.mediaType())
                .contentLength(body.length)
                .cacheControl(CACHE_CONTROL)
                .body(body);
    }

    public record MapLayersResponse(
            String source,
            String release,
            String attribution,
            List<LayerManifest> layers) {
    }

    public record LayerManifest(
            String id,
            String url,
            long count,
            long bytes,
            String contentType,
            String description) {
    }
}
