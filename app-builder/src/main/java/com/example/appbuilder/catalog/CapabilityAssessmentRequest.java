package com.example.appbuilder.catalog;

import jakarta.validation.constraints.NotBlank;

public record CapabilityAssessmentRequest(@NotBlank String prompt) {}
