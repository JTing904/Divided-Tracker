package com.dividendstream.api.config

import com.dividendstream.api.common.ApiErrorResponse
import com.dividendstream.api.security.AuthRateLimitFilter
import com.dividendstream.api.security.JwtAuthenticationFilter
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val authRateLimitFilter: AuthRateLimitFilter,
    private val corsProperties: CorsProperties,
    private val objectMapper: ObjectMapper,
) {

    /** Cost 12: noticeably slower to brute-force, still well under 500ms per login. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // No cookies or sessions are used, so there is no CSRF vector to protect.
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                    // Signing in is what these do; requiring a session to reach them would be
                    // a circle. Each proves identity by its own means.
                    .requestMatchers("/api/auth/google", "/api/auth/google/desktop").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/auth/google/config").permitAll()
                    // A client must be able to learn it is too old to be let in before it has
                    // a session, since being shut out is precisely when it needs to say why.
                    .requestMatchers(HttpMethod.GET, "/api/app/version").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { handling ->
                handling
                    .authenticationEntryPoint { _, response, _ ->
                        writeError(
                            response,
                            HttpStatus.UNAUTHORIZED,
                            ApiErrorResponse("UNAUTHENTICATED", "Please sign in to continue."),
                        )
                    }
                    .accessDeniedHandler { _, response, _ ->
                        writeError(
                            response,
                            HttpStatus.FORBIDDEN,
                            ApiErrorResponse("FORBIDDEN", "You do not have access to this resource."),
                        )
                    }
            }
            .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    private fun writeError(
        response: jakarta.servlet.http.HttpServletResponse,
        status: HttpStatus,
        body: ApiErrorResponse,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, body)
    }

    /**
     * Only origins listed in CORS_ALLOWED_ORIGINS may call the API from a browser. The
     * default is an empty list -- the Android client is not a browser and is unaffected.
     */
    private fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = corsProperties.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
            allowCredentials = false
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", configuration)
        }
    }
}
