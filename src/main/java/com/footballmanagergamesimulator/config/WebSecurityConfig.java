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

    /** Face Lab endpoints, opened up only while the dev flag below is set. */
    private static final String FACE_LAB_PATHS = "/api/dev/facelab/**";

    private final UserDetailsServiceImpl userDetailsService;
    private final boolean chairmanEnabled;
    private final boolean faceLabEnabled;
    private final List<String> allowedOrigins;

    public WebSecurityConfig(UserDetailsServiceImpl userDetailsService,
                             ChairmanModeProperties chairmanModeProperties,
                             @Value("${cors.allowed-origins:http://localhost:4200}") List<String> allowedOrigins,
                             @Value("${facelab.enabled:false}") boolean faceLabEnabled) {
        this.userDetailsService = userDetailsService;
        this.chairmanEnabled = chairmanModeProperties.isEnabled();
        this.faceLabEnabled = faceLabEnabled;
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
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "X-XSRF-TOKEN", "X-Admin-Token"));
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
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler);
                    // The Face Lab gallery posts without a session, so there is no CSRF
                    // token to present. Only reachable while facelab.enabled is set.
                    if (faceLabEnabled) csrf.ignoringRequestMatchers(FACE_LAB_PATHS);
                })
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
                    // Save files contain the shared football world but deliberately
                    // exclude account credentials and identity bindings. Every
                    // authenticated career must therefore be able to download a
                    // save, while restoring that global world remains admin-only.
                    requests.requestMatchers(HttpMethod.GET, "/game/export").authenticated();
                    requests.requestMatchers(HttpMethod.POST, "/game/import").hasRole("ADMIN");
                    // The legacy Boardroom accepts caller-supplied owner IDs and
                    // client prices. It is never a Phase-1 compatibility API.
                    requests.requestMatchers("/boardroom/**").denyAll();
                    if (!chairmanEnabled) {
                        requests.requestMatchers(HttpMethod.GET, "/api/market/instruments",
                                "/api/market/instruments/*/history").authenticated();
                        requests.requestMatchers(HttpMethod.POST, "/api/market/instruments/*/advice",
                                "/api/me/trades", "/api/me/market-adviser/hire").authenticated();
                        requests.requestMatchers(HttpMethod.GET, "/api/me/portfolio", "/api/me/trades",
                                "/api/me/market-adviser").authenticated();
                        requests.requestMatchers("/api/me/**", "/api/people/**", "/api/market/**", "/api/clubs/**",
                                "/api/club-cash-transfers",
                                "/api/assets/**", "/api/wealth-rankings/**").denyAll();
                    } else {
                        requests.requestMatchers("/api/me/**", "/api/people/**", "/api/market/**", "/api/clubs/**",
                                "/api/club-cash-transfers",
                                "/api/assets/**", "/api/wealth-rankings/**").authenticated();
                    }
                    // DEV-ONLY Face Lab. `facelab.enabled` lives in the local
                    // application.properties and is absent from the packaged
                    // application.yml, so in a production boot this branch never runs AND
                    // DevFaceLabController is not even instantiated (@ConditionalOnProperty).
                    // The gallery deliberately renders outside the login shell, so it has
                    // no session to authenticate with.
                    if (faceLabEnabled) requests.requestMatchers(FACE_LAB_PATHS).permitAll();
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
