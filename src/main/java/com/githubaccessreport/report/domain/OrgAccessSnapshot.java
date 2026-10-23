package com.githubaccessreport.report.domain;

import java.util.List;

/** Raw result of reading an organization's access, before ordering and de-duplication. */
public record OrgAccessSnapshot(List<Repository> repositories, List<AccessGrant> grants, Coverage coverage) {}
