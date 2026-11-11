package com.wly.job.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHtmlContractTest {

    @Test
    void adminPageContainsLocalLoginAndForcedPasswordChangeFlow() throws Exception {
        String html = new String(new ClassPathResource("static/admin.html").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(html.contains("/auth/login"));
        assertTrue(html.contains("/auth/password/change"));
        assertTrue(html.contains("Authorization"), "request APIs must carry bearer auth");
        assertTrue(html.contains("mustChangePassword"));
        assertTrue(html.contains("localStorage"));
        assertTrue(html.contains("id=\"logout\""));
        assertFalse(html.contains("loadGroups();\n    loadJobs();\n    loadRecs();"),
                "data loading must wait until authentication succeeds");
    }
}
