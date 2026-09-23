package astana.innovation.backendakim.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    @Profile("postgres")
    SecurityFilterChain accountSecurity(HttpSecurity http, TokenAuthenticationManager tokens,
                                        SecurityProblemWriter problems) throws Exception {
        stateless(http);
        var resolver = new DefaultBearerTokenResolver();
        http.authorizeHttpRequests(requests -> requests
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/auth/me").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/v1/simulations", "/api/v1/simulations/**")
                    .hasAnyRole("USER", "ADMIN", "LEGACY_USER")
                .anyRequest().permitAll());
        http.oauth2ResourceServer(resource -> {
            resource
                .authenticationManagerResolver(request -> tokens)
                .bearerTokenResolver(request -> {
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    // Logging in again must work even when a client still sends its expired JWT.
                    boolean protectedPath = path.equals("/api/v1/auth/me") || path.startsWith("/api/v1/admin/")
                            || path.equals("/api/v1/simulations") || path.startsWith("/api/v1/simulations/");
                    return protectedPath ? resolver.resolve(request) : null;
                })
                .authenticationEntryPoint(problems::unauthorized)
                .accessDeniedHandler((request, response, exception) -> problems.forbidden(request, response));
            resource.addObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                @Override
                public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                    // Keep authentication storage errors as 503 instead of the default rethrow/500.
                    filter.setAuthenticationFailureHandler(problems::unauthorized);
                    return filter;
                }
            });
        });
        http.exceptionHandling(errors -> errors.authenticationEntryPoint(problems::unauthorized)
                .accessDeniedHandler((request, response, exception) -> problems.forbidden(request, response)));
        return http.build();
    }

    @Bean
    @Profile("!postgres")
    SecurityFilterChain standaloneSecurity(HttpSecurity http) throws Exception {
        stateless(http);
        // Controllers return 503 for storage operations; public calculation/Swagger still work offline.
        return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
    }

    private void stateless(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults()).csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable);
    }
}
