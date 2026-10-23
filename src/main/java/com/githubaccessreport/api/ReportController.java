package com.githubaccessreport.api;

import com.githubaccessreport.config.ReportProperties;
import com.githubaccessreport.report.AccessReportService;
import com.githubaccessreport.report.domain.AccessReport;
import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.domain.Permission;
import com.githubaccessreport.report.view.AccessFilter;
import com.githubaccessreport.report.view.AccessFindings;
import com.githubaccessreport.report.view.Finding;
import com.githubaccessreport.report.view.GrantsCsv;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{organization}/access")
@Tag(name = "Access report", description = "Who can access which repositories in a GitHub organization")
@ApiResponse(
        responseCode = "400",
        description = "Invalid organization name or query parameter",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "404",
        description = "Organization not found or not visible to the service token",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "429",
        description = "GitHub rate limit exhausted; see Retry-After",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "502",
        description = "GitHub failed or rejected the service token",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "503",
        description = "GitHub is throttling requests; see Retry-After",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
        responseCode = "504",
        description = "The report did not finish within the deadline",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
public class ReportController {

    static final int MAX_PAGE_SIZE = 200;

    static final String ORGANIZATION_LOGIN = Organizations.LOGIN;
    static final String ORGANIZATION_LOGIN_MESSAGE = Organizations.LOGIN_MESSAGE;

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
            @Parameter(description = "GitHub organization login", example = "my-org")
                    @PathVariable
                    @Pattern(regexp = ORGANIZATION_LOGIN, message = ORGANIZATION_LOGIN_MESSAGE)
                    String organization,
            @Parameter(description = "Only grants at or above this level") @RequestParam(required = false)
                    Permission minPermission,
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
            @Parameter(description = "GitHub organization login", example = "my-org")
                    @PathVariable
                    @Pattern(regexp = ORGANIZATION_LOGIN, message = ORGANIZATION_LOGIN_MESSAGE)
                    String organization,
            @Parameter(description = "Only grants at or above this level") @RequestParam(required = false)
                    Permission minPermission,
            @RequestParam(required = false) Affiliation affiliation,
            @RequestParam(defaultValue = "true") boolean includeArchived,
            @RequestParam(defaultValue = "true") boolean includeForks,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        AccessReport report = reports.getReport(resolve(organization));
        var filter = new AccessFilter(minPermission, affiliation, includeArchived, includeForks);
        return toPage(report, ReportViews.byRepository(report, filter), page, size);
    }

    @Operation(
            summary = "Export grants as CSV",
            description = "One row per (user, repository) grant matching the filters, for spreadsheets. Not paged.")
    @ApiResponse(responseCode = "200", description = "CSV (RFC 4180), formula-like cells neutralized")
    @GetMapping(value = "/grants.csv", produces = "text/csv")
    public ResponseEntity<String> grantsCsv(
            @Parameter(description = "GitHub organization login", example = "my-org")
                    @PathVariable
                    @Pattern(regexp = ORGANIZATION_LOGIN, message = ORGANIZATION_LOGIN_MESSAGE)
                    String organization,
            @RequestParam(required = false) Permission minPermission,
            @RequestParam(required = false) Affiliation affiliation,
            @RequestParam(defaultValue = "true") boolean includeArchived,
            @RequestParam(defaultValue = "true") boolean includeForks) {

        AccessReport report = reports.getReport(resolve(organization));
        var filter = new AccessFilter(minPermission, affiliation, includeArchived, includeForks);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(report.organization() + "-access.csv")
                                .build()
                                .toString())
                .body(GrantsCsv.write(report, filter));
    }

    @Operation(
            summary = "Access worth reviewing",
            description = "Outside collaborators who can write; non-owner admins on archived or dormant "
                    + "repositories (no push for dormantDays). Most severe first.")
    @ApiResponse(responseCode = "200", description = "Findings page (possibly served from cache)")
    @GetMapping("/findings")
    public ReportPage<Finding> findings(
            @Parameter(description = "GitHub organization login", example = "my-org")
                    @PathVariable
                    @Pattern(regexp = ORGANIZATION_LOGIN, message = ORGANIZATION_LOGIN_MESSAGE)
                    String organization,
            @Parameter(description = "A repository is dormant after this many days without a push")
                    @RequestParam(defaultValue = "180")
                    @Min(1)
                    @Max(3650)
                    int dormantDays,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        AccessReport report = reports.getReport(resolve(organization));
        return toPage(
                report, AccessFindings.evaluate(report, clock.instant(), Duration.ofDays(dormantDays)), page, size);
    }

    private String resolve(String organization) {
        return Organizations.resolve(organization, properties);
    }

    private <T> ReportPage<T> toPage(AccessReport report, List<T> items, int page, int size) {
        PageResult<T> slice = PageResult.of(items, page, size);
        long ageSeconds = Math.max(
                0, Duration.between(report.generatedAt(), clock.instant()).toSeconds());
        return new ReportPage<>(
                report.organization(),
                report.generatedAt(),
                ageSeconds,
                report.coverage(),
                ReportViews.summarize(report),
                new ReportPage.PageInfo(slice.number(), slice.size(), slice.totalElements(), slice.totalPages()),
                slice.items());
    }
}
