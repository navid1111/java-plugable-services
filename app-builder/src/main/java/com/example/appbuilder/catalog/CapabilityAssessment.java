package com.example.appbuilder.catalog;

import java.util.List;

public record CapabilityAssessment(
        List<String> availableServiceIds,
        List<String> developingCapabilities) {}
