package com.githubaccessreport.report.domain;

import java.time.Instant;

public record Repository(
        long id, String name, Visibility visibility, boolean archived, boolean fork, Instant pushedAt) {}
