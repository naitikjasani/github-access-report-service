package com.githubaccessreport.report.view;

/** Exposes the package-private cell encoder to tests in other packages. */
public final class GrantsCsvTestAccess {

    private GrantsCsvTestAccess() {}

    public static String cell(String value) {
        return GrantsCsv.cell(value);
    }
}
