package com.githubaccessreport.github;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads the {@code rel="next"} page number from GitHub's {@code Link} header.
 *
 * <p>We follow GitHub's own "next" link rather than guessing from page size, which saves a request
 * when a collection is an exact multiple of the page size. Only the page number is taken from the
 * link: requests keep using our URI template, so metrics stay tagged by template (bounded
 * cardinality) rather than by GitHub's per-repository-id URLs.
 */
public final class LinkHeader {

    private static final Pattern LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"([^\"]+)\"");

    private LinkHeader() {}

    public static OptionalInt nextPage(String header) {
        if (header == null || header.isBlank()) {
            return OptionalInt.empty();
        }
        Matcher matcher = LINK.matcher(header);
        while (matcher.find()) {
            if ("next".equals(matcher.group(2))) {
                String page = UriComponentsBuilder.fromUriString(matcher.group(1))
                        .build()
                        .getQueryParams()
                        .getFirst("page");
                if (page == null) {
                    throw new IllegalStateException("Unsupported pagination: next link has no page parameter");
                }
                return OptionalInt.of(Integer.parseInt(page));
            }
        }
        return OptionalInt.empty();
    }
}
