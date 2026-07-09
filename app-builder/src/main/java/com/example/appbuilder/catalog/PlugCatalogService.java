package com.example.appbuilder.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PlugCatalogService {

    private static final Pattern KONG_PATH_PATTERN = Pattern.compile("paths\\[\\]=([^\\s\"']+)");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "app", "build", "for", "i", "make", "me", "my", "the", "to", "with");
    private static final java.util.Map<String, Set<String>> SERVICE_ALIASES = java.util.Map.of(
            "auth-service", Set.of("auth", "login", "register", "signup", "signin"),
            "tweeter-service", Set.of("post", "posts", "feed", "follow", "tweet"),
            "whatsapp-service", Set.of("chat", "message", "messages", "whatsapp"),
            "booking-service", Set.of("booking", "bookings", "reservation", "slot", "resource", "turf", "venue"),
            "comment-service", Set.of("comment", "comments"),
            "post-search-service", Set.of("search", "post-search"),
            "media-service", Set.of("media", "upload", "uploads", "image", "images", "video", "videos"),
            "leetcode-service", Set.of("leetcode", "problem", "submission", "competition"));

    private final Path repoRoot;

    @Autowired
    public PlugCatalogService(@Value("${appbuilder.repo-root:..}") String repoRoot) {
        this(Path.of(repoRoot).toAbsolutePath().normalize());
    }

    public PlugCatalogService(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public List<PlugDescriptor> listPlugs() {
        if (!Files.isDirectory(repoRoot)) {
            return List.of();
        }

        try (var stream = Files.list(repoRoot)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> Files.isDirectory(path.resolve("plug")))
                    .map(this::describePlug)
                    .sorted(Comparator.comparing(PlugDescriptor::id))
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to scan plug kits under " + repoRoot, ex);
        }
    }

    public CapabilityAssessment assessRequest(String request) {
        String normalized = normalize(request);
        List<String> tokens = meaningfulTokens(normalized);
        List<PlugDescriptor> plugs = listPlugs();
        List<String> available = plugs.stream()
                .filter(plug -> plug.status() == PlugStatus.AVAILABLE)
                .filter(plug -> matchesRequest(plug, normalized, tokens))
                .map(PlugDescriptor::id)
                .toList();

        Set<String> developing = new LinkedHashSet<>();
        for (String token : tokens) {
            boolean covered = plugs.stream().anyMatch(plug -> plugMatchesToken(plug, token));
            if (!covered) {
                developing.add(token);
            }
        }

        return new CapabilityAssessment(available, new ArrayList<>(developing));
    }

    private List<String> meaningfulTokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank() && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private PlugDescriptor describePlug(Path serviceDir) {
        Path plugDir = serviceDir.resolve("plug");
        Path compose = plugDir.resolve("compose.plug.yml");
        Path kongSetup = plugDir.resolve("kong-setup.sh");
        Path smoke = plugDir.resolve("smoke.sh");
        PlugStatus status = Files.isRegularFile(compose)
                        && Files.isRegularFile(kongSetup)
                        && Files.isRegularFile(smoke)
                ? PlugStatus.AVAILABLE
                : PlugStatus.DEVELOPING;

        return new PlugDescriptor(
                serviceDir.getFileName().toString(),
                displayName(serviceDir.getFileName().toString()),
                serviceDir.getFileName().toString(),
                status,
                relative(compose),
                relative(kongSetup),
                relative(smoke),
                extractGatewayPaths(kongSetup));
    }

    private List<String> extractGatewayPaths(Path kongSetup) {
        if (!Files.isRegularFile(kongSetup)) {
            return List.of();
        }
        try {
            Matcher matcher = KONG_PATH_PATTERN.matcher(Files.readString(kongSetup));
            List<String> paths = new ArrayList<>();
            while (matcher.find()) {
                paths.add(matcher.group(1));
            }
            return paths;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean matchesRequest(PlugDescriptor plug, String normalizedRequest, List<String> tokens) {
        String normalizedId = normalize(plug.id());
        String normalizedDisplayName = normalize(plug.displayName());
        return normalizedRequest.contains(normalizedId)
                || normalizedRequest.contains(normalizedDisplayName)
                || plug.gatewayPaths().stream().map(this::normalize).anyMatch(normalizedRequest::contains)
                || tokens.stream().anyMatch(token -> plugMatchesToken(plug, token));
    }

    private boolean plugMatchesToken(PlugDescriptor plug, String token) {
        return hasWord(normalize(plug.id()), token)
                || hasWord(normalize(plug.displayName()), token)
                || plug.gatewayPaths().stream().map(this::normalize).anyMatch(path -> hasWord(path, token))
                || SERVICE_ALIASES.getOrDefault(plug.id(), Set.of()).contains(token);
    }

    private boolean hasWord(String phrase, String token) {
        if (token.isBlank()) {
            return false;
        }
        for (String word : phrase.split(" ")) {
            if (word.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private String displayName(String id) {
        String[] parts = id.split("-");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return String.join(" ", words);
    }

    private String relative(Path path) {
        return repoRoot.relativize(path).toString().replace('\\', '/');
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
