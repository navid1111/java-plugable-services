package com.example.leetcode.controller;

import com.example.leetcode.model.Problem;
import com.example.leetcode.model.Submission;
import com.example.leetcode.repository.ProblemRepository;
import com.example.leetcode.repository.SubmissionRepository;
import com.example.leetcode.security.JwtHelper;
import com.example.leetcode.service.runner.CodeRunner;
import com.example.leetcode.service.runner.ExecutionResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/leetcode")
public class LeetcodeController {

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final List<CodeRunner> runners;
    private final JwtHelper jwtHelper;

    public LeetcodeController(ProblemRepository problemRepository,
                              SubmissionRepository submissionRepository,
                              List<CodeRunner> runners,
                              JwtHelper jwtHelper) {
        this.problemRepository = problemRepository;
        this.submissionRepository = submissionRepository;
        this.runners = runners;
        this.jwtHelper = jwtHelper;
    }

    @GetMapping("/problems")
    public Map<String, Object> getProblems(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "100") int limit) {
        Page<Problem> problemsPage = problemRepository.findAll(PageRequest.of(Math.max(0, page - 1), limit));
        
        List<Map<String, Object>> items = problemsPage.getContent().stream().map(p -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId());
            map.put("title", p.getTitle());
            map.put("difficulty", p.getDifficulty());
            // Tags is JSON, so we just return the raw string or leave it out.
            // Ideally we parse it but for the partial list it's fine.
            return map;
        }).toList();

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("total", problemsPage.getTotalElements());
        response.put("page", page);
        return response;
    }

    @GetMapping("/problems/{id}")
    public Problem getProblem(@PathVariable String id) {
        return problemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
    }

    @PostMapping("/problems/{id}/submit")
    public Submission submitCode(@PathVariable String id,
                                 @RequestHeader("Authorization") String authHeader,
                                 @RequestParam(required = false) String competitionId,
                                 @RequestBody SubmitRequest request) {
        
        String username = jwtHelper.extractUsername(authHeader);
        
        Problem problem = problemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));

        CodeRunner runner = runners.stream()
                .filter(r -> r.supports(request.language))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Language not supported"));

        ExecutionResult execResult = runner.runCode(request.code, problem.getTestCases());

        Submission submission = new Submission();
        submission.setProblemId(problem.getId());
        submission.setUsername(username);
        submission.setCode(request.code);
        submission.setLanguage(request.language);
        submission.setStatus(execResult.getStatus());
        submission.setPassedCount(execResult.getPassedCount());
        submission.setTotalCount(execResult.getTotalCount());
        submission.setExecutionTimeMs(execResult.getExecutionTimeMs());
        submission.setErrorMessage(execResult.getErrorMessage());
        submission.setCompetitionId(competitionId);
        submission.setSubmittedAt(Instant.now());

        return submissionRepository.save(submission);
    }

    public static class SubmitRequest {
        public String code;
        public String language;
    }
}
