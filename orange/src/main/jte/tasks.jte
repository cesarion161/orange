@import java.util.List
@import com.ai.orange.task.Task

@param List<Task> tasks
@param String statusFilter
@param String roleFilter

@template.layout(
    title = "tasks",
    activeNav = "tasks",
    body = @`
        <h1>tasks</h1>
        <form method="get" action="/dashboard/tasks" class="filters">
            <label>status
                <select name="status">
                    <option value="">(any)</option>
                    @for(String s : List.of("pending","ready","in_progress","pr_open","dev_ready","in_test","test_done","failed","cancelled"))
                        <option value="${s}" selected="${s.equals(statusFilter)}">${s}</option>
                    @endfor
                </select>
            </label>
            <label>role
                <select name="role">
                    <option value="">(any)</option>
                    @for(String r : List.of("dev","tester","reviewer","planner","triage"))
                        <option value="${r}" selected="${r.equals(roleFilter)}">${r}</option>
                    @endfor
                </select>
            </label>
            <button type="submit">filter</button>
        </form>

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
        @if(tasks.isEmpty())
            <p class="muted">no tasks match these filters.</p>
        @endif
    `
)
