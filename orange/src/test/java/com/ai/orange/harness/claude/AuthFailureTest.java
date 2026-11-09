package com.ai.orange.harness.claude;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthFailureTest {

    @Test
    void rejectedCredentialsMatch() {
        // Claude Code 2.1.319 with an invalid subscription token (seen in ClaudeCliContractLiveIT).
        assertThat(AuthFailure.matches("Failed to authenticate. API Error: 401 OAuth access token is invalid.")).isTrue();
        assertThat(AuthFailure.matches("API Error: 401 {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                + "\"message\":\"OAuth token has expired.\"}}")).isTrue();
        assertThat(AuthFailure.matches("Invalid API key · Please run /login")).isTrue();
        assertThat(AuthFailure.matches("Not logged in · Please run /login")).isTrue();
    }

    @Test
    void otherErrorsDoNotMatch() {
        assertThat(AuthFailure.matches("API Error: 529 overloaded")).isFalse();
        assertThat(AuthFailure.matches("API Error: 400 prompt is too long")).isFalse();
        assertThat(AuthFailure.matches("Claude usage limit reached. Your limit will reset at 5pm.")).isFalse();
        assertThat(AuthFailure.matches("boom")).isFalse();
        assertThat(AuthFailure.matches(null)).isFalse();
        assertThat(AuthFailure.matches("")).isFalse();
    }
}
