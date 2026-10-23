package com.githubaccessreport.api;

import com.githubaccessreport.config.ReportProperties;
import java.util.Locale;

/** Organization-name rules shared by every endpoint that takes an organization. */
final class Organizations {

    /** GitHub's rule: 1-39 alphanumerics or single hyphens, not starting or ending with a hyphen. */
    static final String LOGIN = "^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$";

    static final String LOGIN_MESSAGE =
            "must be a GitHub organization login: 1-39 letters, digits or single hyphens, not starting or ending with '-'";

    private Organizations() {}

    /**
     * GitHub logins are case-insensitive, so normalize to one cache entry per organization, then
     * apply the allowlist (the control that makes a public demo safe to expose).
     */
    static String resolve(String organization, ReportProperties properties) {
        String normalized = organization.toLowerCase(Locale.ROOT);
        if (!properties.isAllowed(normalized)) {
            throw new OrganizationNotAllowedException(normalized);
        }
        return normalized;
    }
}
