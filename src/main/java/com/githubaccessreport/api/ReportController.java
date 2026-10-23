package com.githubaccessreport.api;

import com.githubaccessreport.report.AccessReportService;
import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.view.AccessFilter;
import com.githubaccessreport.report.view.PageResult;
import com.githubaccessreport.report.view.ReportViews;
import com.githubaccessreport.report.view.RepositoryAccess;
import com.githubaccessreport.report.view.UserAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import com.githubaccessreport.config.ReportProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{organization}/access")
@Tag(name = "Access report", description = "Who can access which repositories in a GitHub organization")
@ApiResponse(responseCode = "400", description = "Invalid organization name or query parameter",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Organization not found or not visible to the service token",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "GitHub rate limit exhausted; see Retry-After",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "502", description = "GitHub failed or rejected the service token",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "GitHub is throttling requests; see Retry-After",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "504", description = "The report did not finish within the deadline",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ReportController {

    static final int MAX_PAGE_SIZE = 200;

    /** GitHub's rule: 1-39 alphanumerics or single hyphens, not starting or ending with a hyphen. */
    static final String ORGANIZATION_LOGIN = "^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$";
    static final String ORGANIZATION_LOGIN_MESSAGE =
            "must be a GitHub organization login: 1-39 letters, digits or single hyphens, not starting or ending with '-'";

    private final AccessReportService reports;
    private final ReportProperties properties;
    private final Clock clock;

    public ReportController(AccessReportService reports, ReportProperties properties, Clock clock) {
        this.reports = reports;
        this.properties = properties;
        this.clock = clock;
    }

    @Operation(summary = "Access by user", description = "Each user with the repositories they can access.")
    @ApiResponse(responseCode = "200", description = "Report page (possibly served from cache)")
    @GetMapping("/users")
    public ReportPage<UserAccess> users(
            @Parameter(description = "GitHub organization login", example = "my-org") @PathVariable @Pattern(regexp = ORGANIZATION_LOGIN, message = ORGANIZATION_LOGIN_MESSAGE) String organization,
            @Parameter(description = "Only grants at or above this level") @RequestParam(required = false) Permission minPermission,
            @RequestParam(required = false) Affiliation affiliation,
            @RequestParam(defaultValue = "true") boolean includeArchived,
            @RequestParam(defaultValue = "true") boolean includeForks,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        AccessReport report = reports.getReport(resolve(organization));
        var filter = new AccessFilter(minPermission, affiliation, includeArchived, includeForks);
        return toPage(report, ReportViews.byUser(report, filter), page, size);
    }

    @Operation(summary = "Access by repository", description = "Each repository with the users who can access it.")
    @ApiResponse(responseCode = "200", description = "Report page (possibly served from cache)")
    @GetMapping("/repositories")
    public ReportPage<RepositoryAccess> repositories(
            @Parameter(description = "GitHub organization login", example = "my-org") @PathVariable @Pattern(regexp = ORGANIZATION_LOGIN, message = ORGANIZATION_LOGIN_MESSAGE) String organization,
            @Parameter(description = "Only grants at or above this level") @RequestParam(required = false) Permission minPermission,
            @RequestParam(required = false) Affiliation affiliation,
            @RequestParam(defaultValue = "true") boolean includeArchived,
            @RequestParam(defaultValue = "true") boolean includeForks,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        AccessReport report = reports.getReport(resolve(organization));
        var filter = new AccessFilter(minPermission, affiliation, includeArchived, includeForks);
        return toPage(report, ReportViews.byRepository(report, filter), page, size);
    }

    /**
     * GitHub logins are case-insensitive, so normalize to one cache entry per organization, then
     * apply the allowlist (the control that makes a public demo safe to expose).
     */
    private String resolve(String organization) {
        String normalized = organization.toLowerCase(Locale.ROOT);
        if (!properties.isAllowed(normalized)) {
            throw new OrganizationNotAllowedException(normalized);
        }
        return normalized;
    }

    private <T> ReportPage<T> toPage(AccessReport report, List<T> items, int page, int size) {
        PageResult<T> slice = PageResult.of(items, page, size);
        long ageSeconds = Math.max(0, Duration.between(report.generatedAt(), clock.instant()).toSeconds());
        return new ReportPage<>(report.organization(), report.generatedAt(), ageSeconds, report.coverage(),
                ReportViews.summarize(report),
                new ReportPage.PageInfo(slice.number(), slice.size(), slice.totalElements(), slice.totalPages()),
                slice.items());
    }
}
