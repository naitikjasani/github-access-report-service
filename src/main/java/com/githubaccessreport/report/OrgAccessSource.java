package com.githubaccessreport.report;

import com.githubaccessreport.report.domain.OrgAccessSnapshot;
import java.time.Instant;

/**
 * Port for reading who can access what in an organization. Defined at the level of the whole use
 * case, not single endpoints, so an implementation is free to batch (e.g. GraphQL) instead of
 * being forced into one call per repository.
 */
public interface OrgAccessSource {

    /**
     * @param organization normalized (lower-case) organization login
     * @param deadline     give up, cancel outstanding calls and throw {@link ReportTimeoutException}
     * @throws OrganizationNotFoundException if the organization does not exist or is not visible
     */
    OrgAccessSnapshot fetch(String organization, Instant deadline);
}
