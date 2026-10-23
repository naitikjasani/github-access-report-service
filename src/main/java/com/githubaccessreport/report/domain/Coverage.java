package com.githubaccessreport.report.domain;

import java.util.List;

/**
 * What the report could and could not see. A report is {@code complete} only when every listed
 * repository was readable and organization membership was known.
 */
public record Coverage(
        boolean complete,
        int repositoriesListed,
        int repositoriesReadable,
        List<SkippedRepository> skippedRepositories,
        boolean affiliationsKnown) {

    public static Coverage of(int listed, List<SkippedRepository> skipped, boolean affiliationsKnown) {
        return new Coverage(skipped.isEmpty() && affiliationsKnown, listed, listed - skipped.size(),
                List.copyOf(skipped), affiliationsKnown);
    }
}
