package com.githubaccessreport.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubaccessreport.report.domain.Affiliation;
import com.githubaccessreport.report.view.AccessFilter;
import com.githubaccessreport.report.view.GrantsCsv;
import com.githubaccessreport.report.view.GrantsCsvTestAccess;
import org.junit.jupiter.api.Test;

class GrantsCsvTest {

    @Test
    void writesHeaderAndOneRowPerMatchingGrant() {
        String csv = GrantsCsv.write(
                ReportFixtures.report(), new AccessFilter(null, Affiliation.OUTSIDE_COLLABORATOR, true, true));

        assertThat(csv.split("\r\n"))
                .containsExactly(
                        "login,user_id,affiliation,repository,permission,role_name,visibility,archived,fork,pushed_at",
                        "olga,301,OUTSIDE_COLLABORATOR,api,WRITE,write,PRIVATE,false,false,2025-06-01T00:00:00Z");
    }

    @Test
    void everyGrantWithoutFilters() {
        assertThat(GrantsCsv.write(ReportFixtures.report(), AccessFilter.NONE).split("\r\n"))
                .hasSize(1 + 7);
    }

    @Test
    void quotesSpecialCharactersPerRfc4180() {
        assertThat(GrantsCsvTestAccess.cell("plain")).isEqualTo("plain");
        assertThat(GrantsCsvTestAccess.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(GrantsCsvTestAccess.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    @Test
    void neutralizesCellsSpreadsheetsWouldEvaluate() {
        assertThat(GrantsCsvTestAccess.cell("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(GrantsCsvTestAccess.cell("+1")).isEqualTo("'+1");
        assertThat(GrantsCsvTestAccess.cell("-cmd")).isEqualTo("'-cmd");
        assertThat(GrantsCsvTestAccess.cell("@sum")).isEqualTo("'@sum");
        assertThat(GrantsCsvTestAccess.cell("=a,b")).isEqualTo("\"'=a,b\"");
    }
}
