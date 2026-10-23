package com.githubaccessreport.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.githubaccessreport.report.AccessReportService;
import com.githubaccessreport.report.ReportFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReportController.class, properties = "report.api-key=s3cret-key")
@Import(ReportControllerTest.FixedClock.class)
class ApiKeyFilterTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccessReportService reports;

    @Test
    void missingKeyIs401Problem() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void wrongKeyIs401() throws Exception {
        mvc.perform(get("/api/v1/orgs/acme/access/users").header("X-API-Key", "s3cret-kez"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctKeyPasses() throws Exception {
        when(reports.getReport("acme")).thenReturn(ReportFixtures.report());

        mvc.perform(get("/api/v1/orgs/acme/access/users").header("X-API-Key", "s3cret-key"))
                .andExpect(status().isOk());
    }
}
