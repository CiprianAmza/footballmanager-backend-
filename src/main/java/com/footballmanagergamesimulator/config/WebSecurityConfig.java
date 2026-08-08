package com.footballmanagergamesimulator.config;

import com.footballmanagergamesimulator.user.UserDetailsServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
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
import org.springframework.security.web.util.matcher.RequestMatcher;
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

    /** Phase Lab endpoints (scenario preview + rating). Dev tool; enabled by
     *  default in this test project, disable with phaselab.enabled=false. */
    private static final String PHASE_LAB_PATHS = "/api/dev/phaselab/**";

    /**
     * H2 web console, opened up only while the dev flag below is set.
     *
     * <p>This must be a {@link PathRequest} matcher, not the usual
     * {@code requestMatchers("/h2-console/**")} string. With Spring MVC on the
     * classpath a string builds an {@code MvcRequestMatcher}, which resolves
     * paths against the DispatcherServlet — but the console is its own servlet,
     * so the pattern misses. The symptom is a 401/403 on {@code /h2-console}
     * and on the {@code login.do} form post while {@code /h2-console/} itself
     * happens to work.
     */
    private final UserDetailsServiceImpl userDetailsService;
    private final boolean chairmanEnabled;
    private final boolean faceLabEnabled;
    private final boolean phaseLabEnabled;
    private final boolean h2ConsoleEnabled;
    private final RequestMatcher h2ConsolePaths;
    private final List<String> allowedOrigins;

    public WebSecurityConfig(UserDetailsServiceImpl userDetailsService,
                             ChairmanModeProperties chairmanModeProperties,
                             @Value("${cors.allowed-origins:http://localhost:4200}") List<String> allowedOrigins,
                             @Value("${facelab.enabled:false}") boolean faceLabEnabled,
                             @Value("${phaselab.enabled:true}") boolean phaseLabEnabled,
                             @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) {
        this.userDetailsService = userDetailsService;
        this.chairmanEnabled = chairmanModeProperties.isEnabled();
        this.faceLabEnabled = faceLabEnabled;
        this.phaseLabEnabled = phaseLabEnabled;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
        this.h2ConsolePaths = h2ConsoleEnabled ? PathRequest.toH2Console() : null;
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
                    // The Phase Lab page also renders outside the login shell.
                    if (phaseLabEnabled) csrf.ignoringRequestMatchers(PHASE_LAB_PATHS);
                    // The H2 console is a plain server-rendered form app that knows
                    // nothing about our token. Only reachable while the dev flag is set.
                    if (h2ConsoleEnabled) csrf.ignoringRequestMatchers(h2ConsolePaths);
                })
                .headers(headers -> {
                    // The H2 console renders its query editor inside frames, which the
                    // default DENY policy blocks. Relaxed to same-origin for dev only.
                    if (h2ConsoleEnabled) headers.frameOptions(frame -> frame.sameOrigin());
                })
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers("/", "/home", "/register", "/login").permitAll();
                    requests.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll();
                    requests.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll();
                    requests.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    // DEV-ONLY database console. `spring.h2.console.enabled` lives in the
                    // local application.properties and is absent from the packaged
                    // application.yml, so a production boot keeps the deny rule below AND
                    // Spring never registers the console servlet in the first place.
                    if (h2ConsoleEnabled) requests.requestMatchers(h2ConsolePaths).permitAll();
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
                    if (phaseLabEnabled) requests.requestMatchers(PHASE_LAB_PATHS).permitAll();
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
