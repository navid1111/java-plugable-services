package com.example.appbuilder.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracts the real HTTP endpoints a plug service exposes by scanning its Spring
 * controllers, so the app generator can wire calls to routes that actually exist
 * instead of hallucinating them.
 */
@Component
public class EndpointScanner {

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern METHOD_WITH_ARGS =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\s*\\(([^)]*)\\)");
    private static final Pattern METHOD_NO_ARGS =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\b(?!\\s*\\()");
    private static final Pattern FIRST_QUOTE = Pattern.compile("\"([^\"]*)\"");

    private final Path repoRoot;

    public EndpointScanner(@Value("${appbuilder.repo-root:..}") String repoRoot) {
        this.repoRoot = Path.of(repoRoot).toAbsolutePath().normalize();
    }

    public List<String> endpointsFor(String serviceId) {
        Path base = repoRoot.resolve(serviceId).resolve("src/main/java");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<String> endpoints = new ArrayList<>();
        try (var stream = Files.walk(base)) {
            stream.filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .forEach(path -> parseController(path, endpoints));
        } catch (Exception ex) {
            return endpoints;
        }
        return endpoints.stream().distinct().sorted().toList();
    }

    private void parseController(Path file, List<String> endpoints) {
        String src;
        try {
            src = Files.readString(file);
        } catch (Exception ex) {
            return;
        }
        Matcher classMatcher = CLASS_MAPPING.matcher(src);
        String basePath = classMatcher.find() ? classMatcher.group(1) : "";

        Matcher withArgs = METHOD_WITH_ARGS.matcher(src);
        while (withArgs.find()) {
            Matcher quote = FIRST_QUOTE.matcher(withArgs.group(2));
            String sub = quote.find() ? quote.group(1) : "";
            addEndpoint(endpoints, withArgs.group(1), basePath, sub);
        }
        Matcher noArgs = METHOD_NO_ARGS.matcher(src);
        while (noArgs.find()) {
            addEndpoint(endpoints, noArgs.group(1), basePath, "");
        }
    }

    private void addEndpoint(List<String> endpoints, String verb, String basePath, String sub) {
        String full = (basePath + sub).replaceAll("//+", "/");
        if (!full.startsWith("/")) {
            full = "/" + full;
        }
        // The generated browser app only has access to public Kong routes. Internal
        // controllers use workload JWTs and are intentionally not routed through Kong;
        // advertising them caused generated apps to call service-to-service APIs directly.
        if (full.equals("/health") || full.equals("/internal") || full.startsWith("/internal/")) {
            return;
        }
        endpoints.add(verb.toUpperCase() + " " + full);
    }
}
