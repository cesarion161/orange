@import gg.jte.Content

@param String title
@param String activeNav
@param Content body

<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <title>${title} · orange</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <script src="/static/htmx.min.js"></script>
    <script src="/static/htmx-sse.js"></script>
    <link rel="stylesheet" href="/static/orange.css">
</head>
<body>
<header class="topbar">
    <a href="/" class="brand">🟠 orange</a>
    <nav>
        <a href="/" class="${activeNav.equals("overview") ? "active" : ""}">overview</a>
        <a href="/dashboard/tasks" class="${activeNav.equals("tasks") ? "active" : ""}">tasks</a>
        <a href="/dashboard/workers" class="${activeNav.equals("workers") ? "active" : ""}">workers</a>
        <a href="/dashboard/envs" class="${activeNav.equals("envs") ? "active" : ""}">envs</a>
        <a href="/dashboard/agents" class="${activeNav.equals("agents") ? "active" : ""}">agents</a>
    </nav>
</header>
<main>
    ${body}
</main>
<footer>
    <span class="muted">read-only dashboard · auto-refreshes every 5s</span>
</footer>
</body>
</html>
