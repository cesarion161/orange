package com.ai.orange.config;

import com.ai.orange.github.GithubProperties;
import com.ai.orange.webhook.GithubWebhookHmacFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public GithubWebhookHmacFilter githubWebhookHmacFilter(GithubProperties props) {
        return new GithubWebhookHmacFilter(props);
    }

    @Bean
    public ApiToken apiToken(@Value("${orange.api.token:}") String token) {
        return new ApiToken(token);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   GithubWebhookHmacFilter hmacFilter,
                                                   ApiToken apiToken) throws Exception {
        // Decided inside the chain, on the path Spring Security matched, so no spelling of the path
        // (percent-encoding, a trailing slash) gets around the token.
        AuthorizationManager<RequestAuthorizationContext> hasApiToken =
                (authentication, context) -> new AuthorizationDecision(apiToken.allows(context.getRequest()));
        http
                .csrf(csrf -> csrf.disable())  // we expose no browser forms; all writes are HMAC- or token-authenticated
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/webhooks/github").permitAll()  // HMAC filter authenticates instead
                        .requestMatchers("/mcp/worker").permitAll()       // WorkerTokenFilter authenticates (per-run bearer token)
                        .requestMatchers("/error").permitAll()             // Tomcat forwards here on sendError(); must not denyAll
                        .requestMatchers("/tasks", "/tasks/**").permitAll()  // operator-driven; auth lands later
                        .requestMatchers("/plans", "/plans/**").permitAll()  // operator-driven; auth lands later
                        .requestMatchers("/dispatch", "/dispatch/**").permitAll()  // operator-driven; auth lands later
                        .requestMatchers("/scheduler", "/scheduler/**").permitAll()  // operator-driven; auth lands later
                        .requestMatchers("/runs/**").permitAll()  // operator-driven; auth lands later
                        .requestMatchers("/events/**").permitAll()           // live narration feed
                        // nodes and steering clients: the API token, when orange.api.token is set
                        .requestMatchers("/sessions", "/sessions/**").access(hasApiToken)
                        .requestMatchers("/attention", "/attention/**").access(hasApiToken)
                        .requestMatchers("/mcp/steering").access(hasApiToken)
                        .requestMatchers("/brief").access(hasApiToken)  // the latest brief
                        .requestMatchers("/brief/*").permitAll()  // one brief: its page key or the API token, checked by BriefPageController
                        .requestMatchers("/dev-envs", "/dev-envs/**").permitAll()
                        .requestMatchers("/health/**").permitAll()
                        .requestMatchers("/api/status").permitAll()  // status smoke check
                        .requestMatchers("/api/ready").permitAll()  // smoke / readiness probe
                        .anyRequest().denyAll()
                )
                .addFilterBefore(hmacFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
