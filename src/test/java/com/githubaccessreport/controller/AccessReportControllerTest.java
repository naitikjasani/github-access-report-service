package com.githubaccessreport.controller;

import com.githubaccessreport.exception.GlobalExceptionHandler;
import com.githubaccessreport.exception.OrganizationNotFoundException;
import com.githubaccessreport.exception.RateLimitExceededException;
import com.githubaccessreport.model.dto.AccessReportResponse;
import com.githubaccessreport.model.dto.RepositoryAccess;
import com.githubaccessreport.model.dto.UserAccess;
import com.githubaccessreport.service.AccessReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccessReportController.class)
@Import(GlobalExceptionHandler.class)
class AccessReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessReportService accessReportService;

    @Test
    @DisplayName("GET /api/access-report/{org} returns 200 with correct response")
    void shouldReturnAccessReport() throws Exception {
        // Arrange
        AccessReportResponse response = AccessReportResponse.builder()
                .organization("test-org")
                .generatedAt(Instant.parse("2024-01-15T10:30:00Z"))
                .users(List.of(
                        UserAccess.builder()
                                .username("alice")
                                .repositories(List.of(
                                        RepositoryAccess.builder()
                                                .name("backend-service")
                                                .permission("admin")
                                                .build()
                                ))
                                .build()
                ))
                .build();

        when(accessReportService.generateReport("test-org")).thenReturn(response);

        // Act & Assert
        mockMvc.perform(get("/api/access-report/test-org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("test-org"))
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users[0].username").value("alice"))
                .andExpect(jsonPath("$.users[0].repositories[0].name").value("backend-service"))
                .andExpect(jsonPath("$.users[0].repositories[0].permission").value("admin"));
    }

    @Test
    @DisplayName("GET /api/access-report/{org} returns 404 when organization not found")
    void shouldReturn404WhenOrganizationNotFound() throws Exception {
        // Arrange
        when(accessReportService.generateReport("nonexistent-org"))
                .thenThrow(new OrganizationNotFoundException("nonexistent-org"));

        // Act & Assert
        mockMvc.perform(get("/api/access-report/nonexistent-org"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("GET /api/access-report/{org} returns 429 when rate limit exceeded")
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        // Arrange
        when(accessReportService.generateReport("big-org"))
                .thenThrow(new RateLimitExceededException("GitHub API rate limit exceeded"));

        // Act & Assert
        mockMvc.perform(get("/api/access-report/big-org"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }

    @Test
    @DisplayName("GET /api/access-report/{org} returns 200 with empty users for org with no repos")
    void shouldReturnEmptyUsersForOrgWithNoRepos() throws Exception {
        // Arrange
        AccessReportResponse response = AccessReportResponse.builder()
                .organization("empty-org")
                .generatedAt(Instant.now())
                .users(List.of())
                .build();

        when(accessReportService.generateReport("empty-org")).thenReturn(response);

        // Act & Assert
        mockMvc.perform(get("/api/access-report/empty-org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("empty-org"))
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users").isEmpty());
    }
}
