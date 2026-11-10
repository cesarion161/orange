package com.ai.orange.mcp;

import com.ai.orange.attention.AttentionService;
import com.ai.orange.brief.BriefLinks;
import com.ai.orange.brief.BriefService;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceService;
import com.ai.orange.session.SessionRepository;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The steering MCP endpoint, {@code /mcp/steering} (project-space spec §10): the user's own
 * Claude Code sessions talk to orange here. Stateless like the worker endpoint; authenticated by
 * the API token ({@link com.ai.orange.config.ApiToken}) when one is configured.
 */
@Configuration
public class SteeringMcpConfig {

    public static final String PATH = "/mcp/steering";

    @Bean
    HttpServletStatelessServerTransport steeringMcpTransport(McpJsonMapper workerMcpJsonMapper) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(workerMcpJsonMapper)
                .messageEndpoint(PATH)
                .build();
    }

    @Bean
    SteeringTools steeringTools(ProjectService projectService, ProjectRepository projects, SpaceService space,
                                Conventions conventions, AttentionService attention, SessionRepository sessions,
                                BriefService briefs, BriefLinks links, McpJsonMapper workerMcpJsonMapper) {
        return new SteeringTools(projectService, projects, space, conventions, attention, sessions, briefs, links,
                workerMcpJsonMapper);
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer steeringMcpServer(
            @Qualifier("steeringMcpTransport") HttpServletStatelessServerTransport steeringMcpTransport,
            SteeringTools steeringTools, McpJsonMapper workerMcpJsonMapper) {
        return McpServer.sync(steeringMcpTransport)
                .serverInfo("orange-steering", "1")
                .jsonMapper(workerMcpJsonMapper)
                .tools(steeringTools.specifications())
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> steeringMcpServlet(
            @Qualifier("steeringMcpTransport") HttpServletStatelessServerTransport steeringMcpTransport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> bean =
                new ServletRegistrationBean<>(steeringMcpTransport, PATH);
        bean.setName("orangeSteeringMcp");
        return bean;
    }
}
