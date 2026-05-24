# Beta — Safety & Sandboxing

Building on the three-layer overview in [base.md](base.md), this file focuses on how Orange enforces isolation at runtime.

The `worktree_guard` hook (always installed) blocks any `Write`, `Edit`, or `Bash` whose path escapes the agent's designated worktree directory, making file-system containment the first line of defence.

Webhook ingress is separately protected: `GithubWebhookHmacFilter` verifies the `X-Hub-Signature-256` header before any payload reaches application logic, ensuring only GitHub-signed events are processed.

Together, these two boundaries — subprocess filesystem scope and authenticated external input — mean neither a runaway agent nor a spoofed webhook can corrupt the orchestrator's state unilaterally.
