package com.example.leetcode.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "competition_problems")
@IdClass(CompetitionProblemId.class)
public class CompetitionProblem {

    @Id
    private String competitionId;

    @Id
    private String problemId;

    private Integer problemOrder;

    // Getters and Setters

    public String getCompetitionId() { return competitionId; }
    public void setCompetitionId(String competitionId) { this.competitionId = competitionId; }

    public String getProblemId() { return problemId; }
    public void setProblemId(String problemId) { this.problemId = problemId; }

    public Integer getProblemOrder() { return problemOrder; }
    public void setProblemOrder(Integer problemOrder) { this.problemOrder = problemOrder; }
}
