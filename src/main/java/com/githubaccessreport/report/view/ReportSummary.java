package com.githubaccessreport.report.view;

/** Totals for the whole report, independent of filters and paging. */
public record ReportSummary(int repositories, int users, int outsideCollaborators, int admins) {}
