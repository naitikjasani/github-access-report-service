package com.githubaccessreport.api;

import com.githubaccessreport.report.domain.Coverage;
import com.githubaccessreport.report.view.ReportSummary;
import java.time.Instant;
import java.util.List;

/**
 * Response envelope shared by both views.
 *
 * @param generatedAt when the underlying report was built (it may come from cache)
 * @param ageSeconds  how stale the data is right now
 * @param coverage    what the report could not see; check {@code coverage.complete} before trusting totals
 * @param summary     totals for the whole report, ignoring filters and paging
 */
public record ReportPage<T>(String organization, Instant generatedAt, long ageSeconds, Coverage coverage,
        ReportSummary summary, PageInfo page, List<T> items) {

    public record PageInfo(int number, int size, long totalElements, int totalPages) {}
}
