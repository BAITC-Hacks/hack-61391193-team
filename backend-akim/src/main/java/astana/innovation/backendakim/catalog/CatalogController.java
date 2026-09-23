package astana.innovation.backendakim.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/districts")
    public List<DistrictResponse> getDistricts() {
        return catalogService.getDistricts();
    }

    @GetMapping("/districts/{id}")
    public DistrictResponse getDistrict(@PathVariable String id) {
        return catalogService.getDistrict(id);
    }

    @GetMapping("/measures")
    public List<MeasureResponse> getMeasures() {
        return catalogService.getMeasures();
    }

    @GetMapping("/districts/{id}/measures")
    public List<DistrictMeasureResponse> getMeasuresForDistrict(@PathVariable String id) {
        return catalogService.getMeasuresForDistrict(id);
    }
}
