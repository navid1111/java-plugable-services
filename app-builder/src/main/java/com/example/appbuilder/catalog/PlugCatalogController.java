package com.example.appbuilder.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PlugCatalogController {

    private final PlugCatalogService catalogService;
    private final EndpointScanner endpointScanner;

    public PlugCatalogController(PlugCatalogService catalogService, EndpointScanner endpointScanner) {
        this.catalogService = catalogService;
        this.endpointScanner = endpointScanner;
    }

    @GetMapping("/plugs")
    public List<PlugDescriptor> listPlugs() {
        return catalogService.listPlugs();
    }

    /** Real HTTP endpoints each plug service exposes, so the agent can wire calls that exist. */
    @GetMapping("/plugs/endpoints")
    public Map<String, List<String>> endpoints() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (PlugDescriptor plug : catalogService.listPlugs()) {
            result.put(plug.id(), endpointScanner.endpointsFor(plug.id()));
        }
        return result;
    }

    @PostMapping("/assess")
    public CapabilityAssessment assess(@Valid @RequestBody CapabilityAssessmentRequest request) {
        return catalogService.assessRequest(request.prompt());
    }
}
