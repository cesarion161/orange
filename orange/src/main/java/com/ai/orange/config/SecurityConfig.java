package com.ai.orange.config;

import com.ai.orange.github.GithubProperties;
import com.ai.orange.webhook.GithubWebhookHmacFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public GithubWebhookHmacFilter githubWebhookHmacFilter(GithubProperties props) {
        return new GithubWebhookHmacFilter(props);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   GithubWebhookHmacFilter hmacFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())  // we expose no browser forms; all writes are HMAC- or token-authenticated
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/webhooks/github").permitAll()  // HMAC filter authenticates instead
                        .requestMatchers("/error").permitAll()             // Tomcat forwards here on sendError(); must not denyAll
                        .requestMatchers("/", "/dashboard/**", "/htmx/**", "/static/**", "/webjars/**").permitAll()
                        .requestMatchers("/tasks", "/tasks/**").permitAll()  // operator-driven; auth lands later
                        .requestMatchers("/plans", "/plans/**").permitAll()  // operator-driven; auth lands later
                        .requestMatchers("/claims", "/claims/**").permitAll()  // chat-as-executor; auth lands later
                        .requestMatchers("/events/**").permitAll()           // live narration feed
                        .requestMatchers("/dev-envs", "/dev-envs/**").permitAll()
                        .requestMatchers("/concurrency").permitAll()
                        .requestMatchers("/health/**").permitAll()
                        .requestMatchers("/api/version").permitAll()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(hmacFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
