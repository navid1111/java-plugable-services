package com.example.leetcode.controller;

import com.example.leetcode.model.*;
import com.example.leetcode.repository.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/leetcode/competitions")
public class CompetitionController {
    private final CompetitionRepository competitions; private final CompetitionProblemRepository competitionProblems; private final ProblemRepository problems; private final SubmissionRepository submissions;
    public CompetitionController(CompetitionRepository c,CompetitionProblemRepository cp,ProblemRepository p,SubmissionRepository s){competitions=c;competitionProblems=cp;problems=p;submissions=s;}
    @PostMapping @Transactional public Competition create(@Valid @RequestBody CreateCompetitionRequest request){
        if(!request.endTime().isAfter(request.startTime()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"endTime must be after startTime");
        for(String id:request.problemIds())if(!problems.existsById(id))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown problem: "+id);
        Competition c=new Competition();c.setId(request.id());c.setTitle(request.title());c.setStartTime(request.startTime());c.setEndTime(request.endTime());competitions.save(c);
        int order=1;for(String id:request.problemIds()){CompetitionProblem cp=new CompetitionProblem();cp.setCompetitionId(c.getId());cp.setProblemId(id);cp.setProblemOrder(order++);competitionProblems.save(cp);}return c;
    }
    @GetMapping("/{id}/leaderboard") public Map<String,Object> leaderboard(@PathVariable String id,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="100") @Min(1) @Max(100) int limit){
        if(!competitions.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Competition not found");var all=submissions.getLeaderboard(id);int from=Math.min((page-1)*limit,all.size()),to=Math.min(from+limit,all.size());var items=new ArrayList<Map<String,Object>>();int rank=from+1;for(var row:all.subList(from,to)){Map<String,Object> m=new LinkedHashMap<>();m.put("rank",rank++);m.put("username",row.getUsername());m.put("solvedCount",row.getSolvedCount());m.put("lastSolveTime",row.getLastSolveTime());items.add(m);}return Map.of("items",items,"total",all.size(),"page",page);
    }
    public record CreateCompetitionRequest(@NotBlank String id,@NotBlank String title,@NotNull Instant startTime,@NotNull Instant endTime,@NotEmpty List<@NotBlank String> problemIds){}
}
