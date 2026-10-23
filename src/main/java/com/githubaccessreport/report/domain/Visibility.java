package com.githubaccessreport.report.domain;

import java.util.Locale;

public enum Visibility {
    PUBLIC,
    PRIVATE,
    INTERNAL;

    public static Visibility fromGitHub(String visibility, boolean isPrivate) {
        if (visibility != null) {
            switch (visibility.toLowerCase(Locale.ROOT)) {
                case "public" -> { return PUBLIC; }
                case "private" -> { return PRIVATE; }
                case "internal" -> { return INTERNAL; }
                default -> { /* fall back to the private flag */ }
            }
        }
        return isPrivate ? PRIVATE : PUBLIC;
    }
}
