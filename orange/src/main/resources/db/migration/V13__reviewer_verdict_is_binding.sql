-- Correction + follow-up to V10's reviewer seed.
--
-- V10's header comment says the reviewer is "advisory, not gating" and that
-- verdict parsing for the reviewer role is "deliberately out of scope". That is
-- no longer true: PR #20 made the reviewer a real gate — PipelineActivitiesImpl
-- now parses the `VERDICT: PASS|FAIL` line from the reviewer's final message,
-- and a FAIL fails the review stage so retryPrevious bounces the work back to
-- the dev agent (with the review attached as feedback).
--
-- V10's file cannot be edited (Flyway checksums applied migrations), so this
-- fresh migration records the correction AND brings the reviewer's own prompt
-- in line with the new behavior: it tells the reviewer its verdict is binding,
-- so it reserves FAIL for genuinely blocking problems instead of nitpicks.
--
-- Idempotent: the NOT LIKE guard makes the append a no-op if the note is
-- already present.
UPDATE agents
   SET system_prompt = system_prompt || E'\n\nYour VERDICT is binding. VERDICT: FAIL fails the review stage and sends the change back to the dev agent for another attempt, with your review attached as feedback — so reserve FAIL for genuinely blocking problems (correctness, security, missing/meaningless tests), not style nits. VERDICT: PASS lets the change proceed to the tester.',
       updated_at = NOW()
 WHERE name = 'reviewer'
   AND system_prompt NOT LIKE '%Your VERDICT is binding.%';
