package com.wly.job.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

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
        assertTrue(html.contains("sessionStorage"));
        assertTrue(html.contains("id=\"change-password\""));
        assertTrue(html.contains("id=\"logout\""));
        assertTrue(html.contains("if(authToken()) {"));
        assertTrue(html.contains("if(mustChangePassword()) showAuthView('password');"));
    }
}
