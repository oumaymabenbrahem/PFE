package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatisticsDTO {
    private Long totalTests;
    private Long totalProjects;
    private Double successRate;
    private Map<String, Long> monthlyActivity;
    private Map<String, Long> testTypeDistribution;
    private Map<String, Long> priorityDistribution;
    private Map<String, Long> statusDistribution;
    private double totalTestsGrowth;
    private double totalProjectsGrowth;
    private double averageExecutionTime;
    private Long totalUsers;
}
