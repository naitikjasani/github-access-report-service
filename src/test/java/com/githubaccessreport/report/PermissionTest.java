package com.githubaccessreport.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.githubaccessreport.report.domain.Permission;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PermissionTest {

    @ParameterizedTest(name = "role_name {0} -> {1}")
    @CsvSource({"admin, ADMIN", "maintain, MAINTAIN", "write, WRITE", "triage, TRIAGE", "read, READ", "ADMIN, ADMIN"})
    void builtInRoleNamesMapDirectly(String roleName, Permission expected) {
        assertThat(Permission.fromGitHub(roleName, Map.of())).isEqualTo(expected);
    }

    @Test
    void customRoleFallsBackToPermissionFlags() {
        var flags = Map.of("admin", false, "maintain", false, "push", false, "triage", true, "pull", true);

        assertThat(Permission.fromGitHub("security-reviewer", flags)).isEqualTo(Permission.TRIAGE);
    }

    @Test
    void flagsAreUsedWhenRoleNameIsMissing() {
        assertThat(Permission.fromGitHub(null, Map.of("push", true, "pull", true)))
                .isEqualTo(Permission.WRITE);
    }

    @Test
    void missingDataIsUnknownNotRead() {
        assertThat(Permission.fromGitHub(null, null)).isEqualTo(Permission.UNKNOWN);
        assertThat(Permission.fromGitHub(null, Map.of("pull", false))).isEqualTo(Permission.UNKNOWN);
    }

    @Test
    void levelsAreOrdered() {
        assertThat(Permission.ADMIN.isAtLeast(Permission.WRITE)).isTrue();
        assertThat(Permission.TRIAGE.isAtLeast(Permission.WRITE)).isFalse();
        assertThat(Permission.UNKNOWN.isAtLeast(Permission.READ)).isFalse();
    }
}
