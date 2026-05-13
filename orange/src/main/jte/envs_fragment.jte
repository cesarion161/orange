@import java.util.List
@import com.ai.orange.devenv.DevEnv

@param List<DevEnv> envs

<table class="rows" hx-get="/dashboard/fragments/envs" hx-trigger="every 5s" hx-swap="outerHTML">
    <thead><tr><th>name</th><th>status</th><th>ports</th><th>held by</th><th>since</th></tr></thead>
    <tbody>
    @for(DevEnv e : envs)
        <tr>
            <td>${e.name()}</td>
            <td class="status status-${e.status().dbValue()}">${e.status().dbValue()}</td>
            <td><code class="muted">${e.ports() == null ? "-" : e.ports().toString()}</code></td>
            <td>${e.leasedBy() == null ? "-" : e.leasedBy()}</td>
            <td class="muted">${e.leasedAt() == null ? "-" : e.leasedAt().toString()}</td>
        </tr>
    @endfor
    </tbody>
</table>
