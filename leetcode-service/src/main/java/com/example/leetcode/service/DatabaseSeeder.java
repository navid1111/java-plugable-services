package com.example.leetcode.service;

import com.example.leetcode.model.Problem;
import com.example.leetcode.repository.ProblemRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSeeder {

    private final ProblemRepository problemRepository;

    public DatabaseSeeder(ProblemRepository problemRepository) {
        this.problemRepository = problemRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedDatabase() {
        if (problemRepository.count() == 0) {
            seedTwoSum();
            seedReverseString();
        }
    }

    private void seedTwoSum() {
        Problem problem = new Problem();
        problem.setId("two-sum");
        problem.setTitle("Two Sum");
        problem.setDescription("Given an array of integers nums and an integer target, return indices of the two numbers such that they add up to target.");
        problem.setDifficulty("EASY");
        problem.setTags("[\"array\", \"hash-table\"]");

        String pythonStub = "class Solution:\\n    def twoSum(self, nums, target):\\n        pass";
        String javaStub = "class Solution {\\n    public int[] twoSum(int[] nums, int target) {\\n        return new int[]{};\\n    }\\n}";
        String jsStub = "var twoSum = function(nums, target) {\\n\\n};";
        
        problem.setCodeStubs("{\"python\": \"" + pythonStub + "\", \"java\": \"" + javaStub + "\", \"javascript\": \"" + jsStub + "\"}");

        // The test harness will pass args based on keys or as a list.
        String testCases = "[" +
                "{\"input\": {\"nums\": [2, 7, 11, 15], \"target\": 9}, \"output\": [0, 1]}," +
                "{\"input\": {\"nums\": [3, 2, 4], \"target\": 6}, \"output\": [1, 2]}," +
                "{\"input\": {\"nums\": [3, 3], \"target\": 6}, \"output\": [0, 1]}" +
                "]";
        problem.setTestCases(testCases);

        problemRepository.save(problem);
    }

    private void seedReverseString() {
        Problem problem = new Problem();
        problem.setId("reverse-string");
        problem.setTitle("Reverse String");
        problem.setDescription("Write a function that reverses a string. The input string is given as an array of characters s. You must do this by modifying the input array in-place with O(1) extra memory.");
        problem.setDifficulty("EASY");
        problem.setTags("[\"string\", \"two-pointers\"]");

        String pythonStub = "class Solution:\\n    def reverseString(self, s):\\n        pass";
        String javaStub = "class Solution {\\n    public void reverseString(char[] s) {\\n\\n    }\\n}";
        String jsStub = "var reverseString = function(s) {\\n\\n};";
        
        problem.setCodeStubs("{\"python\": \"" + pythonStub + "\", \"java\": \"" + javaStub + "\", \"javascript\": \"" + jsStub + "\"}");

        String testCases = "[" +
                "{\"input\": {\"s\": [\"h\",\"e\",\"l\",\"l\",\"o\"]}, \"output\": [\"o\",\"l\",\"l\",\"e\",\"h\"]}," +
                "{\"input\": {\"s\": [\"H\",\"a\",\"n\",\"n\",\"a\",\"h\"]}, \"output\": [\"h\",\"a\",\"n\",\"n\",\"a\",\"H\"]}" +
                "]";
        problem.setTestCases(testCases);

        problemRepository.save(problem);
    }
}
