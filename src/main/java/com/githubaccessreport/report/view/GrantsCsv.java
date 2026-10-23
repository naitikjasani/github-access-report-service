package com.githubaccessreport.report.view;

import com.githubaccessreport.report.domain.AccessGrant;
import com.githubaccessreport.report.domain.AccessReport;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One CSV row per (user, repository) grant, for spreadsheets and audit evidence (RFC 4180 quoting).
 *
 * <p>Cells that a spreadsheet would evaluate as a formula ({@code = + - @}, tab, CR) are prefixed
 * with {@code '}: role and repository names come from GitHub and must not become executable in
 * the reviewer's spreadsheet (CSV injection).
 */
public final class GrantsCsv {

    static final List<String> HEADER = List.of(
            "login",
            "user_id",
            "affiliation",
            "repository",
            "permission",
            "role_name",
            "visibility",
            "archived",
            "fork",
            "pushed_at");

    private GrantsCsv() {}

    public static String write(AccessReport report, AccessFilter filter) {
        Stream<String> rows = report.grants().stream().filter(filter::matches).map(GrantsCsv::row);
        return Stream.concat(Stream.of(line(HEADER)), rows).collect(Collectors.joining("\r\n", "", "\r\n"));
    }

    private static String row(AccessGrant grant) {
        return line(Stream.of(
                        grant.user().login(),
                        grant.user().id(),
                        grant.affiliation(),
                        grant.repository().name(),
                        grant.permission(),
                        grant.roleName(),
                        grant.repository().visibility(),
                        grant.repository().archived(),
                        grant.repository().fork(),
                        grant.repository().pushedAt())
                .map(value -> Objects.toString(value, ""))
                .toList());
    }

    private static String line(List<String> cells) {
        return cells.stream().map(GrantsCsv::cell).collect(Collectors.joining(","));
    }

    static String cell(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
