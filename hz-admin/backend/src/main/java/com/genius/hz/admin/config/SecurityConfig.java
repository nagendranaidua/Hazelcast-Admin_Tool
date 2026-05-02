package com.genius.hz.admin.config;

import com.genius.hz.admin.security.AppUserDetailsService;
import com.genius.hz.admin.security.JwtAuthenticationFilter;
import com.genius.hz.admin.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;

/**
 * Two SecurityFilterChain beans, ordered by request matcher:
 *   (1) /api/v1/**   -> stateless JWT bearer (machine integrations)
 *   (2) everything   -> session cookie + CSRF (browser UI)
 *
 * AuthenticationProvider chain is order-driven so LDAP/OIDC adapters
 * (Phase 5, separate @Configuration classes guarded by Spring profile)
 * slot in WITHOUT touching this file.
 */
@Configuration
public class SecurityConfig {

    @Value("${hz-admin.cors.allowed-origins}")
    private String corsAllowed;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(AppUserDetailsService uds, PasswordEncoder enc) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(uds);
        p.setPasswordEncoder(enc);
        return p;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(Arrays.asList(corsAllowed.split("\\s*,\\s*")));
        c.setAllowedMethods(Arrays.asList("GET","POST","PUT","DELETE","PATCH","OPTIONS"));
        c.setAllowedHeaders(Collections.singletonList("*"));
        c.setExposedHeaders(Collections.singletonList("X-XSRF-TOKEN"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    @Bean @Order(1)
    public SecurityFilterChain apiV1Chain(HttpSecurity http, JwtService jwt) throws Exception {
        http
            .antMatcher("/api/v1/**")
            .cors()
                .and()
            .csrf()
                .disable()
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
            .exceptionHandling()
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .and()
            .authorizeRequests()
                .antMatchers("/api/v1/auth/token", "/api/v1/auth/refresh").permitAll()
                .anyRequest().authenticated()
                .and()
            .addFilterBefore(new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean @Order(2)
    public SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
        http
            .cors()
                .and()
            .csrf()
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringAntMatchers("/api/auth/login", "/api/auth/logout")
                .and()
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .and()
            .exceptionHandling()
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .and()
            .authorizeRequests()
                .antMatchers("/api/auth/**", "/swagger-ui/**", "/swagger-ui.html",
                             "/api-docs/**", "/actuator/health", "/actuator/info",
                             "/error", "/").permitAll()
                .anyRequest().authenticated()
                .and()
            .formLogin()
                .disable()
            .httpBasic()
                .disable()
            .logout()
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req,res,auth) -> res.setStatus(204));
        return http.build();
    }
}
