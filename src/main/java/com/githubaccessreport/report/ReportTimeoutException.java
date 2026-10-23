package com.githubaccessreport.report;

import java.time.Duration;

public class ReportTimeoutException extends RuntimeException {

    public ReportTimeoutException(String organization, Duration deadline) {
        super("Report for '%s' did not finish within %ss".formatted(organization, deadline.toSeconds()));
    }
}
