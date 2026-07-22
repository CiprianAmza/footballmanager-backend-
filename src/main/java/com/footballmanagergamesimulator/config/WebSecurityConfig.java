package com.footballmanagergamesimulator.config;

import com.footballmanagergamesimulator.user.UserDetailsServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class WebSecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final boolean regentEnabled;
    private final List<String> allowedOrigins;

    public WebSecurityConfig(UserDetailsServiceImpl userDetailsService,
                             @Value("${regent.enabled:false}") boolean regentEnabled,
                             @Value("${cors.allowed-origins:http://localhost:4200}") List<String> allowedOrigins) {
        this.userDetailsService = userDetailsService;
        this.regentEnabled = regentEnabled;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public DaoAuthenticationProvider authProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(passwordEncoder());
        provider.setUserDetailsService(userDetailsService);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-XSRF-TOKEN");
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .authenticationProvider(authProvider())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers("/", "/home", "/register", "/login").permitAll();
                    requests.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll();
                    requests.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll();
                    requests.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    requests.requestMatchers("/h2-console/**").denyAll();
                    requests.requestMatchers(HttpMethod.POST, "/game/setup").denyAll();
                    requests.requestMatchers(HttpMethod.GET, "/game/isSetupComplete").denyAll();
                    requests.requestMatchers(HttpMethod.GET, "/game/export").hasRole("ADMIN");
                    requests.requestMatchers(HttpMethod.POST, "/game/import").hasRole("ADMIN");
                    if (!regentEnabled) requests.requestMatchers("/boardroom/**").denyAll();
                    else requests.requestMatchers("/boardroom/**").authenticated();
                    requests.requestMatchers("/admin/login").permitAll();
                    requests.requestMatchers("/admin/**").hasRole("ADMIN");
                    requests.anyRequest().authenticated();
                })
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpServletResponse.SC_OK);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"success\":true}");
                        }))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .requestCache(cache -> cache.disable());

        return http.build();
    }
}
