package com.ai.orange.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "I sketched two designs.\n\nShould I proceed with option 1?",
            "Two options:\n- A\n- B\n\n**Which one do you prefer?**",
            "The migration is ready. Let me know if you want me to run it.",
            "Want me to open a PR for this",
            "Do you want the tests in the same commit?\n",
            "Is this the right table? (`runs`)"})
    void asks(String message) {
        assertThat(QuestionDetector.asks(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Done. The retry is in GatewayClient and its tests pass.",
            "Why did it fail? The token had expired.\n\nI fixed it.\n\nAll 212 tests pass.\n\nCommitted as abc123.\n\nNothing else is open.",
            "",
            "   "})
    void doesNotAsk(String message) {
        assertThat(QuestionDetector.asks(message)).isFalse();
    }

    @Test
    void nullIsNotAQuestion() {
        assertThat(QuestionDetector.asks(null)).isFalse();
    }

    @Test
    void questionLineIsTheLineThatAsks() {
        assertThat(QuestionDetector.questionLine("Context here.\n\nShould I proceed with option 1?\n\n(Option 2 is slower.)"))
                .isEqualTo("Should I proceed with option 1?");
        assertThat(QuestionDetector.questionLine("First.\nLet me know how to go on.")).isEqualTo("Let me know how to go on.");
    }
}
