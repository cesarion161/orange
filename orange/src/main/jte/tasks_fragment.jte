@import java.util.List
@import com.ai.orange.task.Task

@param List<Task> tasks
@param String statusFilter
@param String roleFilter

<table class="rows" hx-get="/dashboard/fragments/tasks?status=${statusFilter == null ? "" : statusFilter}&role=${roleFilter == null ? "" : roleFilter}" hx-trigger="every 5s" hx-swap="outerHTML">
    <thead><tr><th>id</th><th>title</th><th>role</th><th>pipeline</th><th>status</th><th>created</th></tr></thead>
    <tbody>
    @for(Task t : tasks)
        <tr>
            <td><a href="/dashboard/tasks/${t.id().toString()}">${t.id().toString().substring(0, 8)}…</a></td>
            <td>${t.title()}</td>
            <td>${t.role()}</td>
            <td>${t.pipeline()}</td>
            <td class="status status-${t.status().dbValue()}">${t.status().dbValue()}</td>
            <td class="muted">${t.createdAt().toString().substring(0, 19)}</td>
        </tr>
    @endfor
    </tbody>
</table>
