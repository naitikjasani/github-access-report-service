package com.githubaccessreport.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LinkHeaderTest {

    @Test
    void readsNextPageFromGitHubLinkHeader() {
        String header = "<https://api.github.com/organizations/123/repos?per_page=100&page=2>; rel=\"next\", "
                + "<https://api.github.com/organizations/123/repos?per_page=100&page=7>; rel=\"last\"";

        assertThat(LinkHeader.nextPage(header)).hasValue(2);
    }

    @Test
    void lastPageHasNoNextLink() {
        String header = "<https://api.github.com/organizations/123/repos?per_page=100&page=1>; rel=\"prev\", "
                + "<https://api.github.com/organizations/123/repos?per_page=100&page=1>; rel=\"first\"";

        assertThat(LinkHeader.nextPage(header)).isEmpty();
    }

    @Test
    void missingHeaderMeansSinglePage() {
        assertThat(LinkHeader.nextPage(null)).isEmpty();
        assertThat(LinkHeader.nextPage("")).isEmpty();
    }

    @Test
    void cursorPaginationIsRejectedRatherThanSilentlyTruncated() {
        assertThatThrownBy(() -> LinkHeader.nextPage("<https://api.github.com/x?after=abc>; rel=\"next\""))
                .isInstanceOf(IllegalStateException.class);
    }
}
