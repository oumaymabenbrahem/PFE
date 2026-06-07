package com.example.backend.service;

import com.example.backend.dto.DashboardStatisticsDTO;
import com.example.backend.entity.TestExecutionResult;
import com.example.backend.entity.TestScript;
import com.example.backend.repository.ProjectRepository;
import com.example.backend.repository.TestExecutionResultRepository;
import com.example.backend.repository.TestScriptRepository;
import com.example.backend.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final ProjectRepository projectRepository;
    private final TestScriptRepository testScriptRepository;
    private final TestExecutionResultRepository testExecutionResultRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public DashboardStatisticsDTO getGlobalStatistics() {
        return DashboardStatisticsDTO.builder()
                .totalProjects(projectRepository.count())
                .totalTests(calculateTotalTests())
                .totalUsers(userRepository.count())
                .successRate(calculateAverageSuccessRate())
                .monthlyActivity(getMonthlyActivityStats())
                .testTypeDistribution(getDistributionByField("type", "Fonctionnel"))
                .priorityDistribution(getDistributionByField("priorite", "Moyenne"))
                .statusDistribution(getStatusDistributionStats())
                .totalTestsGrowth(calculateTestsGrowth())
                .totalProjectsGrowth(calculateProjectsGrowth())
                .averageExecutionTime(calculateAverageExecutionTime())
                .build();
    }

    private Long calculateTotalTests() {
        return testScriptRepository.findAll().stream()
                .mapToLong(ts -> ts.getElementsCount() != null ? ts.getElementsCount() : 0L)
                .sum();
    }

    private Double calculateAverageSuccessRate() {
        List<TestExecutionResult> results = testExecutionResultRepository.findAll();
        if (results.isEmpty()) return 0.0;

        long passedCount = results.stream()
                .filter(r -> r.getStatus() != null && 
                            (r.getStatus().equalsIgnoreCase("PASSED") || 
                             r.getStatus().equalsIgnoreCase("SUCCESS") ||
                             r.getStatus().equalsIgnoreCase("OK") ||
                             r.getStatus().equalsIgnoreCase("COMPLETED")))
                .count();

        return (double) passedCount / results.size() * 100.0;
    }

    private Map<String, Long> getMonthlyActivityStats() {
        List<TestScript> scripts = testScriptRepository.findAll();
        Map<String, Long> activity = new LinkedHashMap<>();

        // Get last 6 months names
        for (int i = 5; i >= 0; i--) {
            java.time.LocalDate date = java.time.LocalDate.now().minusMonths(i);
            String monthName = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            activity.put(monthName, 0L);
        }

        for (TestScript script : scripts) {
            if (script.getGeneratedAt() != null) {
                String month = script.getGeneratedAt().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
                if (activity.containsKey(month)) {
                    activity.put(month, activity.get(month) + (script.getElementsCount() != null ? script.getElementsCount() : 0));
                }
            }
        }

        return activity;
    }

    private Map<String, Long> getDistributionByField(String fieldName, String defaultValue) {
        List<TestScript> scripts = testScriptRepository.findAll();
        Map<String, Long> distribution = new HashMap<>();

        for (TestScript script : scripts) {
            try {
                String scenariosJson = script.getScenarios();
                if (scenariosJson != null && !scenariosJson.isEmpty()) {
                    List<Map<String, Object>> scenarios = objectMapper.readValue(scenariosJson, new TypeReference<List<Map<String, Object>>>() {});
                    for (Map<String, Object> sc : scenarios) {
                        String value = (String) sc.getOrDefault(fieldName, defaultValue);
                        distribution.put(value, distribution.getOrDefault(value, 0L) + 1);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse scenarios for script ID: {}", script.getId());
            }
        }
        return distribution;
    }

    private Map<String, Long> getStatusDistributionStats() {
        List<TestExecutionResult> results = testExecutionResultRepository.findAll();
        Map<String, Long> distribution = new HashMap<>();
        
        for (TestExecutionResult result : results) {
            String status = result.getStatus();
            if (status != null) {
                status = status.toUpperCase();
                distribution.put(status, distribution.getOrDefault(status, 0L) + 1);
            }
        }
        
        if (distribution.isEmpty()) {
            distribution.put("PASSED", 0L);
            distribution.put("FAILED", 0L);
        }
        return distribution;
    }

    private double calculateTestsGrowth() {
        java.time.LocalDateTime thirtyDaysAgo = java.time.LocalDateTime.now().minusDays(30);
        java.time.LocalDateTime sixtyDaysAgo = thirtyDaysAgo.minusDays(30);

        long currentMonthCount = testScriptRepository.findAll().stream()
                .filter(s -> s.getGeneratedAt() != null && s.getGeneratedAt().isAfter(thirtyDaysAgo))
                .count();

        long previousMonthCount = testScriptRepository.findAll().stream()
                .filter(s -> s.getGeneratedAt() != null && s.getGeneratedAt().isAfter(sixtyDaysAgo) && s.getGeneratedAt().isBefore(thirtyDaysAgo))
                .count();

        if (previousMonthCount == 0) return currentMonthCount > 0 ? 100.0 : 0.0;
        return ((double) (currentMonthCount - previousMonthCount) / previousMonthCount) * 100.0;
    }

    private double calculateProjectsGrowth() {
        java.time.LocalDateTime thirtyDaysAgo = java.time.LocalDateTime.now().minusDays(30);
        java.time.LocalDateTime sixtyDaysAgo = thirtyDaysAgo.minusDays(30);

        long currentMonthCount = projectRepository.findAll().stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(thirtyDaysAgo))
                .count();

        long previousMonthCount = projectRepository.findAll().stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(sixtyDaysAgo) && p.getCreatedAt().isBefore(thirtyDaysAgo))
                .count();

        if (previousMonthCount == 0) return currentMonthCount > 0 ? 100.0 : 0.0;
        return ((double) (currentMonthCount - previousMonthCount) / previousMonthCount) * 100.0;
    }

    private double calculateAverageExecutionTime() {
        List<TestExecutionResult> results = testExecutionResultRepository.findAll();
        if (results.isEmpty()) return 0.0;

        double totalMs = results.stream()
                .mapToLong(r -> r.getExecutionDurationMs() != null ? r.getExecutionDurationMs() : 0L)
                .sum();

        // Return in seconds
        return (totalMs / results.size()) / 1000.0;
    }
}
