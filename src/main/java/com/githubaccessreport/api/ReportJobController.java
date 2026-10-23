package com.githubaccessreport.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.githubaccessreport.config.ReportProperties;
import com.githubaccessreport.report.ReportJobs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/reports} starts a report job and answers {@code 202 Accepted} with a {@code
 * Location} to poll. When the job has succeeded, the regular endpoints serve the report from cache.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Report jobs", description = "Asynchronous reports for organizations too large for one request")
public class ReportJobController {

    /** Suggested polling interval for clients. */
    static final String POLL_AFTER_SECONDS = "2";

    private final ReportJobs jobs;
    private final ReportProperties properties;
    private final Problems problems;

    public ReportJobController(ReportJobs jobs, ReportProperties properties, Problems problems) {
        this.jobs = jobs;
        this.properties = properties;
        this.problems = problems;
    }

    public record CreateReportRequest(
            @NotNull @Pattern(regexp = Organizations.LOGIN, message = Organizations.LOGIN_MESSAGE)
            String organization) {}

    /** A job as returned to clients; {@code links} appear once it succeeded, {@code problem} if it failed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobView(
            UUID id,
            String organization,
            ReportJobs.Status status,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant reportGeneratedAt,
            Links links,
            ProblemDetail problem) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Links(String self, String users, String repositories, String findings, String csv) {}

    @Operation(
            summary = "Start a report job",
            description = "Returns the running job if one already exists for the organization.")
    @ApiResponse(responseCode = "202", description = "Job accepted; poll the Location header")
    @PostMapping
    public ResponseEntity<JobView> create(@Valid @RequestBody CreateReportRequest request) {
        ReportJobs.Job job = jobs.submit(Organizations.resolve(request.organization(), properties));
        JobView view = toView(job);
        return ResponseEntity.accepted()
                .location(URI.create(view.links().self()))
                .header(HttpHeaders.RETRY_AFTER, POLL_AFTER_SECONDS)
                .body(view);
    }

    @Operation(summary = "Poll a report job")
    @GetMapping("/{id}")
    public ResponseEntity<JobView> get(@PathVariable UUID id) {
        ReportJobs.Job job = jobs.find(id).orElseThrow(() -> new ReportJobNotFoundException(id));
        var response = ResponseEntity.ok();
        if (job.status() == ReportJobs.Status.QUEUED || job.status() == ReportJobs.Status.RUNNING) {
            response.header(HttpHeaders.RETRY_AFTER, POLL_AFTER_SECONDS);
        }
        return response.body(toView(job));
    }

    private JobView toView(ReportJobs.Job job) {
        String self = "/api/v1/reports/" + job.id();
        String base = "/api/v1/orgs/" + job.organization() + "/access";
        Links links = job.status() == ReportJobs.Status.SUCCEEDED
                ? new Links(self, base + "/users", base + "/repositories", base + "/findings", base + "/grants.csv")
                : new Links(self, null, null, null, null);
        ProblemDetail problem =
                job.failure() == null ? null : problems.from(job.failure()).getBody();
        return new JobView(
                job.id(),
                job.organization(),
                job.status(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.reportGeneratedAt(),
                links,
                problem);
    }
}
