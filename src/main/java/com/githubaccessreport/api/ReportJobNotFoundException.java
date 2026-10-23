package com.githubaccessreport.api;

import java.util.UUID;

public class ReportJobNotFoundException extends RuntimeException {

    public ReportJobNotFoundException(UUID id) {
        super("No report job " + id + " (jobs are kept for one hour)");
    }
}
