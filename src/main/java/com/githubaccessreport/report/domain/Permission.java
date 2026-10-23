package com.githubaccessreport.report.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Base repository permission levels, lowest to highest, so filters can ask for "at least WRITE".
 * {@link #UNKNOWN} sorts below everything and is only shown when no minimum is requested.
 */
public enum Permission {
    UNKNOWN,
    READ,
    TRIAGE,
    WRITE,
    MAINTAIN,
    ADMIN;

    public boolean isAtLeast(Permission minimum) {
        return compareTo(minimum) >= 0;
    }

    /**
     * Prefers GitHub's {@code role_name} for built-in roles. A custom role name (Enterprise) does not
     * map to a level, so the base level comes from the {@code permissions} flags instead.
     */
    public static Permission fromGitHub(String roleName, Map<String, Boolean> permissions) {
        if (roleName != null) {
            switch (roleName.toLowerCase(Locale.ROOT)) {
                case "admin" -> {
                    return ADMIN;
                }
                case "maintain" -> {
                    return MAINTAIN;
                }
                case "write", "push" -> {
                    return WRITE;
                }
                case "triage" -> {
                    return TRIAGE;
                }
                case "read", "pull" -> {
                    return READ;
                }
                default -> {
                    /* custom role: derive from flags */
                }
            }
        }
        if (permissions == null || permissions.isEmpty()) {
            return UNKNOWN;
        }
        if (flag(permissions, "admin")) return ADMIN;
        if (flag(permissions, "maintain")) return MAINTAIN;
        if (flag(permissions, "push")) return WRITE;
        if (flag(permissions, "triage")) return TRIAGE;
        if (flag(permissions, "pull")) return READ;
        return UNKNOWN;
    }

    private static boolean flag(Map<String, Boolean> permissions, String name) {
        return Boolean.TRUE.equals(permissions.get(name));
    }
}
