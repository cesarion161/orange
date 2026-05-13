@import java.util.List
@import java.util.Map

@param Map<String, Integer> queues
@param Map<String, Object> envs
@param List<Map<String, Object>> concurrency
@param Map<String, Object> cost

<div class="grid" hx-get="/dashboard/fragments/overview" hx-trigger="every 5s" hx-swap="outerHTML">
    <section class="card">
        <h2>queues</h2>
        <table class="kv">
            <tr><td>ready</td><td>${queues.getOrDefault("ready", 0)}</td></tr>
            <tr><td>in_progress</td><td>${queues.getOrDefault("in_progress", 0)}</td></tr>
            <tr><td>pr_open</td><td>${queues.getOrDefault("pr_open", 0)}</td></tr>
            <tr><td>dev_ready</td><td>${queues.getOrDefault("dev_ready", 0)}</td></tr>
        </table>
    </section>
    <section class="card">
        <h2>envs</h2>
        <table class="kv">
            <tr><td>total</td><td>${String.valueOf(envs.getOrDefault("total", 0))}</td></tr>
            <tr><td>leased</td><td>${String.valueOf(envs.getOrDefault("leased", 0))}</td></tr>
            <tr><td>free</td><td>${String.valueOf(envs.getOrDefault("free", 0))}</td></tr>
        </table>
    </section>
    <section class="card">
        <h2>cost</h2>
        <table class="kv">
            <tr><td>last 24h</td><td>$${String.valueOf(cost.getOrDefault("last_24h_usd", 0))}</td></tr>
            <tr><td>total</td><td>$${String.valueOf(cost.getOrDefault("total_usd", 0))}</td></tr>
        </table>
    </section>
    <section class="card wide">
        <h2>concurrency by role</h2>
        <table class="rows">
            <thead><tr><th>role</th><th>in flight</th><th>cap</th></tr></thead>
            <tbody>
            @for(Map<String, Object> row : concurrency)
                <tr class="${Boolean.TRUE.equals(row.get("atCap")) ? "at-cap" : ""}">
                    <td>${String.valueOf(row.get("role"))}</td>
                    <td>${String.valueOf(row.get("inProgress"))}</td>
                    <td>${row.get("cap") == null ? "∞" : String.valueOf(row.get("cap"))}</td>
                </tr>
            @endfor
            </tbody>
        </table>
    </section>
</div>
