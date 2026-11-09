package com.ai.orange.harness.claude;

import java.util.Locale;

/**
 * Recognises Claude Code's "credentials rejected" failure: an expired or revoked subscription
 * token, or a gateway key that is blocked or over budget. Such a run never reached the model, so
 * it is reported as {@code auth_failed} and not counted towards escalation.
 */
public final class AuthFailure {

    private AuthFailure() {}

    public static boolean matches(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("failed to authenticate")
                || t.contains("api error: 401")
                || t.contains("authentication_error")
                || t.contains("please run /login");
    }
}
