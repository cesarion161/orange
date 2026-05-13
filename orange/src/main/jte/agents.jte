@import java.util.List
@import com.ai.orange.agent.Agent

@param List<Agent> agents

@template.layout(
    title = "agents",
    activeNav = "agents",
    body = @`
        <h1>agent roles</h1>
        <p class="muted">role <em>definitions</em> the orchestrator picks from when claiming a task. For live instances actually working right now, see <a href="/dashboard/workers">workers</a>.</p>
        @for(Agent a : agents)
            <section class="card">
                <h2>${a.name()} <span class="muted">· ${a.role()} · ${a.model()}</span></h2>
                <table class="kv">
                    <tr><td>permission</td><td>${a.permissionMode()}</td></tr>
                    <tr><td>max turns</td><td>${a.maxTurns() == null ? "∞" : String.valueOf(a.maxTurns())}</td></tr>
                    <tr><td>enabled</td><td>${a.enabled() ? "yes" : "no"}</td></tr>
                    <tr><td>tools</td><td><code class="muted">${a.allowedTools() == null ? "[]" : a.allowedTools().data()}</code></td></tr>
                </table>
                <details>
                    <summary>system prompt</summary>
                    <pre class="prompt">${a.systemPrompt()}</pre>
                </details>
            </section>
        @endfor
    `
)
