@import com.ai.orange.task.Task

@param Task task

@template.layout(
    title = "task " + task.id().toString().substring(0, 8),
    activeNav = "tasks",
    body = @`
        <a href="/dashboard/tasks" class="back">← all tasks</a>
        <h1>${task.title()}</h1>
        <table class="kv">
            <tr><td>id</td><td><code>${task.id().toString()}</code></td></tr>
            <tr><td>status</td><td class="status status-${task.status().dbValue()}">${task.status().dbValue()}</td></tr>
            <tr><td>role</td><td>${task.role()}</td></tr>
            <tr><td>pipeline</td><td>${task.pipeline()}</td></tr>
            <tr><td>priority</td><td>${task.priority()}</td></tr>
            <tr><td>claimed by</td><td>${task.claimedBy() == null ? "-" : task.claimedBy()}</td></tr>
            <tr><td>created</td><td class="muted">${task.createdAt().toString()}</td></tr>
            <tr><td>heartbeat</td><td class="muted">${task.heartbeatAt() == null ? "-" : task.heartbeatAt().toString()}</td></tr>
        </table>

        @if(task.description() != null && !task.description().isBlank())
            <h2>description</h2>
            <pre class="desc">${task.description()}</pre>
        @endif

        <h2>events <span class="muted">(live stream)</span></h2>
        <div id="event-stream" class="event-stream"
             hx-ext="sse"
             sse-connect="/dashboard/tasks/${task.id().toString()}/events/stream"
             sse-swap="event">
            <p class="muted">connecting…</p>
        </div>
    `
)
