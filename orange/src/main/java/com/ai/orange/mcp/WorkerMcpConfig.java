package com.ai.orange.mcp;

import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunSecrets;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The worker MCP endpoint, {@code /mcp/worker} (v2 spec §15). It is stateless: every request
 * carries the run's bearer token, {@link WorkerTokenFilter} resolves it to a running run, and
 * the run rides into the tool handlers through the transport context.
 */
@Configuration
public class WorkerMcpConfig {

    public static final String PATH = "/mcp/worker";

    @Bean
    McpJsonMapper workerMcpJsonMapper() {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new JacksonMcpJsonMapper(mapper);
    }

    @Bean
    HttpServletStatelessServerTransport workerMcpTransport(McpJsonMapper workerMcpJsonMapper) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(workerMcpJsonMapper)
                .messageEndpoint(PATH)
                .contextExtractor(request -> {
                    Object run = request.getAttribute(WorkerTokenFilter.RUN_ATTRIBUTE);
                    return run == null ? McpTransportContext.EMPTY
                            : McpTransportContext.create(Map.of(WorkerTools.RUN_KEY, run));
                })
                .build();
    }

    @Bean
    WorkerTools workerTools(RunRepository runs, RunSecrets runSecrets, McpJsonMapper workerMcpJsonMapper) {
        return new WorkerTools(runs, runSecrets, workerMcpJsonMapper);
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer workerMcpServer(HttpServletStatelessServerTransport workerMcpTransport,
                                           WorkerTools workerTools, McpJsonMapper workerMcpJsonMapper) {
        return McpServer.sync(workerMcpTransport)
                .serverInfo("orange-worker", "2")
                .jsonMapper(workerMcpJsonMapper)
                .tools(workerTools.specifications())
                .addToolFilter((context, tool) -> workerTools.visible(context, tool.name()))
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> workerMcpServlet(
            HttpServletStatelessServerTransport workerMcpTransport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> bean =
                new ServletRegistrationBean<>(workerMcpTransport, PATH);
        bean.setName("orangeWorkerMcp");
        return bean;
    }

    @Bean
    FilterRegistrationBean<WorkerTokenFilter> workerTokenFilter(RunRepository runs) {
        FilterRegistrationBean<WorkerTokenFilter> bean = new FilterRegistrationBean<>(new WorkerTokenFilter(runs));
        bean.addUrlPatterns(PATH);
        bean.setName("orangeWorkerTokenFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return bean;
    }
}
