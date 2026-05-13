@import java.util.List
@import java.util.Map
@import com.ai.orange.api.WorkerService.Activity
@import com.ai.orange.api.WorkerService.WorkerInstance

@param List<WorkerInstance> workers
@param Map<String, Map<Activity, Integer>> aggregate

@template.layout(
    title = "workers",
    activeNav = "workers",
    body = @`
        <h1>workers</h1>
        <p class="muted">each row is a live agent instance — one per in_progress task. activity is derived from the latest event.</p>

        <div hx-get="/dashboard/fragments/workers" hx-trigger="every 5s" hx-swap="outerHTML">
            <h2 class="section-h">by role × activity</h2>
            <table class="rows">
                <thead>
                    <tr>
                        <th>role</th>
                        @for(Activity a : Activity.values())
                            <th>${a.label()}</th>
                        @endfor
                        <th>total</th>
                    </tr>
                </thead>
                <tbody>
                @for(Map.Entry<String, Map<Activity, Integer>> e : aggregate.entrySet())
                    !{int total = 0; for(Integer v : e.getValue().values()) total += v;}
                    <tr class="${total == 0 ? "muted" : ""}">
                        <td>${e.getKey()}</td>
                        @for(Activity a : Activity.values())
                            !{Integer v = e.getValue().get(a);}
                            <td>${v == null || v == 0 ? "·" : String.valueOf(v)}</td>
                        @endfor
                        <td><strong>${total}</strong></td>
                    </tr>
                @endfor
                </tbody>
            </table>

            <h2 class="section-h">live instances (${workers.size()})</h2>
            <table class="rows">
                <thead><tr><th>task</th><th>role</th><th>activity</th><th>latest event</th><th>claimed by</th><th>since</th></tr></thead>
                <tbody>
                @for(WorkerInstance w : workers)
                    <tr>
                        <td><a href="/dashboard/tasks/${w.taskId().toString()}">${w.title()}</a></td>
                        <td>${w.role()}</td>
                        <td><span class="activity activity-${w.activity().name().toLowerCase()}">${w.activity().label()}</span></td>
                        <td class="muted">${w.latestEventType() == null ? "-" : w.latestEventType()}</td>
                        <td class="muted">${w.claimedBy() == null ? "-" : w.claimedBy()}</td>
                        <td class="muted">${w.claimedAt() == null ? "-" : w.claimedAt().toString().substring(0, 19)}</td>
                    </tr>
                @endfor
                </tbody>
            </table>
            @if(workers.isEmpty())
                <p class="muted">no workers active. start a task and it'll show up here.</p>
            @endif
        </div>
    `
)
