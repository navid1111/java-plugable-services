package com.example.appbuilder.catalog;

import java.util.List;

public record PlugDescriptor(
        String id,
        String displayName,
        String serviceDirectory,
        PlugStatus status,
        String composePath,
        String kongSetupPath,
        String smokePath,
        List<String> gatewayPaths) {}
