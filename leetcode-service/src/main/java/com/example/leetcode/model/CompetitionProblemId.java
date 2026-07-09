package com.example.leetcode.model;

import java.io.Serializable;
import java.util.Objects;

public class CompetitionProblemId implements Serializable {
    private String competitionId;
    private String problemId;

    public CompetitionProblemId() {}

    public CompetitionProblemId(String competitionId, String problemId) {
        this.competitionId = competitionId;
        this.problemId = problemId;
    }

    public String getCompetitionId() { return competitionId; }
    public void setCompetitionId(String competitionId) { this.competitionId = competitionId; }

    public String getProblemId() { return problemId; }
    public void setProblemId(String problemId) { this.problemId = problemId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompetitionProblemId that = (CompetitionProblemId) o;
        return Objects.equals(competitionId, that.competitionId) &&
               Objects.equals(problemId, that.problemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(competitionId, problemId);
    }
}
