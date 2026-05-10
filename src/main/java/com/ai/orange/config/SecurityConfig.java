package com.ai.orange.config;

import com.ai.orange.webhook.GithubWebhookHmacFilter;
import com.ai.orange.webhook.GithubWebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(GithubWebhookProperties.class)
public class SecurityConfig {

    @Bean
    public GithubWebhookHmacFilter githubWebhookHmacFilter(GithubWebhookProperties props) {
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
                        .requestMatchers("/", "/dashboard/**", "/htmx/**", "/static/**", "/webjars/**").permitAll()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(hmacFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
