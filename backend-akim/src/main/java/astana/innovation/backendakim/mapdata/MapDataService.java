package astana.innovation.backendakim.mapdata;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class MapDataService {
    private final Map<MapLayer, byte[]> layerData = new EnumMap<>(MapLayer.class);

    byte[] getLayer(MapLayer layer) {
        synchronized (layerData) {
            return layerData.computeIfAbsent(layer, this::readLayer);
        }
    }

    List<MapDataController.LayerManifest> getManifest() {
        return Arrays.stream(MapLayer.values())
                .map(layer -> new MapDataController.LayerManifest(
                        layer.id(),
                        layer.url(),
                        layer.count(),
                        getLayer(layer).length,
                        layer.mediaType().toString(),
                        layer.description()))
                .toList();
    }

    private byte[] readLayer(MapLayer layer) {
        ClassPathResource resource = new ClassPathResource(layer.classpathLocation());
        if (!resource.exists()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Map resource is missing: " + layer.classpathLocation());
        }

        try {
            return resource.getContentAsByteArray();
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read map resource: " + layer.classpathLocation(),
                    exception);
        }
    }
}
