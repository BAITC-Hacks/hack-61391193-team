package astana.innovation.backendakim.mapdata;

import org.springframework.http.MediaType;

enum MapLayer {
    DISTRICTS(
            "districts",
            "districts.geojson",
            6,
            "Administrative boundaries of Astana's six simulation districts",
            MediaType.parseMediaType("application/geo+json")),
    CITY_BOUNDARY(
            "city-boundary",
            "city_boundary.geojson",
            1,
            "Astana city boundary",
            MediaType.parseMediaType("application/geo+json")),
    DISTRICT_STATS(
            "district-stats",
            "district_stats.json",
            6,
            "Spatial context statistics grouped by simulation district",
            MediaType.APPLICATION_JSON),
    POIS(
            "pois",
            "pois.geojson",
            5_888,
            "Schools, kindergartens, medical facilities, transit stops and street infrastructure",
            MediaType.parseMediaType("application/geo+json")),
    PARKS(
            "parks",
            "parks.geojson",
            181,
            "Park and public green-space polygons",
            MediaType.parseMediaType("application/geo+json")),
    ROADS(
            "roads",
            "roads_main.geojson",
            4_591,
            "Main road network and light-rail segments",
            MediaType.parseMediaType("application/geo+json")),
    LRT(
            "lrt",
            "lrt.geojson",
            31,
            "Partial light-rail segments for M3; not the complete route or a station inventory",
            MediaType.parseMediaType("application/geo+json"));

    private static final String RESOURCE_DIRECTORY = "map-data/astana/";
    private static final String API_DIRECTORY = "/api/v1/map/";

    private final String id;
    private final String resourceName;
    private final long count;
    private final String description;
    private final MediaType mediaType;

    MapLayer(String id, String resourceName, long count, String description, MediaType mediaType) {
        this.id = id;
        this.resourceName = resourceName;
        this.count = count;
        this.description = description;
        this.mediaType = mediaType;
    }

    String id() {
        return id;
    }

    String classpathLocation() {
        return RESOURCE_DIRECTORY + resourceName;
    }

    String url() {
        return API_DIRECTORY + id;
    }

    long count() {
        return count;
    }

    String description() {
        return description;
    }

    MediaType mediaType() {
        return mediaType;
    }
}
