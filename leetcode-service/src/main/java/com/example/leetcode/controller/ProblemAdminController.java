package com.example.leetcode.controller;

import com.example.leetcode.model.Problem;
import com.example.leetcode.repository.ProblemRepository;
import com.example.leetcode.security.JwtHelper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/leetcode/admin/problems")
public class ProblemAdminController {
    private static final int MAX_TEST_CASE_JSON_BYTES = 262_144;
    private static final java.util.Set<String> DIFFICULTIES = java.util.Set.of("EASY", "MEDIUM", "HARD");
    private static final java.util.Set<String> LANGUAGES = java.util.Set.of("python", "javascript", "java");

    private final ProblemRepository problems;
    private final JwtHelper jwt;
    private final ObjectMapper mapper;

    public ProblemAdminController(ProblemRepository problems, JwtHelper jwt, ObjectMapper mapper) {
        this.problems = problems;
        this.jwt = jwt;
        this.mapper = mapper;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<AdminProblemView> create(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ProblemWriteRequest request) {
        jwt.requireAdmin(authorization);
        String id = request.id().trim();
        if (problems.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Problem id already exists");
        }
        Problem saved = problems.save(toProblem(new Problem(), id, request));
        return ResponseEntity.created(URI.create("/leetcode/admin/problems/" + id)).body(view(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public AdminProblemView update(
            @PathVariable @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String id,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ProblemWriteRequest request) {
        jwt.requireAdmin(authorization);
        if (!id.equals(request.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body id must match path id");
        }
        Problem problem = problems.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        return view(problems.save(toProblem(problem, id, request)));
    }

    @GetMapping("/{id}")
    public AdminProblemView detail(
            @PathVariable String id,
            @RequestHeader("Authorization") String authorization) {
        jwt.requireAdmin(authorization);
        return problems.findById(id).map(this::view)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
    }

    private Problem toProblem(Problem problem, String id, ProblemWriteRequest request) {
        String difficulty = request.difficulty().toUpperCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(difficulty)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Difficulty must be EASY, MEDIUM, or HARD");
        }
        if (!LANGUAGES.containsAll(request.codeStubs().keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported code-stub language");
        }
        String testCases = mapper.writeValueAsString(request.testCases());
        if (testCases.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEST_CASE_JSON_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Test cases are too large");
        }
        problem.setId(id);
        problem.setTitle(request.title().trim());
        problem.setDescription(request.description().trim());
        problem.setDifficulty(difficulty);
        problem.setTags(mapper.writeValueAsString(request.tags()));
        problem.setCodeStubs(mapper.writeValueAsString(request.codeStubs()));
        problem.setTestCases(testCases);
        return problem;
    }

    private AdminProblemView view(Problem problem) {
        return new AdminProblemView(problem.getId(), problem.getTitle(), problem.getDescription(),
                problem.getDifficulty(), mapper.readTree(problem.getTags()), mapper.readTree(problem.getCodeStubs()),
                mapper.readTree(problem.getTestCases()));
    }

    public record ProblemWriteRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 80) String id,
            @NotBlank @Size(max = 180) String title,
            @NotBlank @Size(max = 20_000) String description,
            @NotBlank String difficulty,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 40) String> tags,
            @NotEmpty Map<@Pattern(regexp = "python|javascript|java") String,
                    @NotBlank @Size(max = 65_536) String> codeStubs,
            @NotEmpty @Size(max = 100) List<@Valid TestCaseInput> testCases) {}

    public record TestCaseInput(@NotNull JsonNode input, @NotNull JsonNode output, boolean hidden) {}

    public record AdminProblemView(String id, String title, String description, String difficulty,
            JsonNode tags, JsonNode codeStubs, JsonNode testCases) {}
}
