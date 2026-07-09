package com.example.leetcode.controller;

import com.example.leetcode.model.Competition;
import com.example.leetcode.repository.CompetitionRepository;
import com.example.leetcode.repository.SubmissionRepository;
import com.example.leetcode.repository.SubmissionRepository.LeaderboardRow;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

@RestController
@RequestMapping("/leetcode/competitions")
public class CompetitionController {

    private final CompetitionRepository competitionRepository;
    private final SubmissionRepository submissionRepository;

    public CompetitionController(CompetitionRepository competitionRepository, SubmissionRepository submissionRepository) {
        this.competitionRepository = competitionRepository;
        this.submissionRepository = submissionRepository;
    }

    @PostMapping
    public Competition createCompetition(@RequestBody Competition competition) {
        // Admin or authenticated check could be done via JwtHelper.
        // Assuming simple creation for testability.
        return competitionRepository.save(competition);
    }

    @GetMapping("/{id}/leaderboard")
    public Map<String, Object> getLeaderboard(@PathVariable String id, 
                                              @RequestParam(defaultValue = "1") int page, 
                                              @RequestParam(defaultValue = "100") int limit) {
        
        List<LeaderboardRow> fullLeaderboard = submissionRepository.getLeaderboard(id);

        int fromIndex = Math.min((page - 1) * limit, fullLeaderboard.size());
        int toIndex = Math.min(fromIndex + limit, fullLeaderboard.size());
        
        List<LeaderboardRow> paginated = fullLeaderboard.subList(fromIndex, toIndex);

        List<Map<String, Object>> items = new ArrayList<>();
        int rank = fromIndex + 1;
        for (LeaderboardRow row : paginated) {
            Map<String, Object> map = new HashMap<>();
            map.put("rank", rank++);
            map.put("username", row.getUsername());
            map.put("solvedCount", row.getSolvedCount());
            map.put("lastSolveTime", row.getLastSolveTime());
            items.add(map);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("total", fullLeaderboard.size());
        response.put("page", page);

        return response;
    }
}
