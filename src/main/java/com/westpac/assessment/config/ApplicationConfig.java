package com.westpac.assessment.config;

import static com.westpac.assessment.constants.ApiConstants.ACCOUNTS_PATH;
import static com.westpac.assessment.constants.ApiConstants.API_KEY_HEADER;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.*;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
@EnableCaching
public class ApplicationConfig {
  @Bean
  OpenAPI accountOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Savings Account API").version("2.0"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"))
                .addSecuritySchemes(
                    "apiKeyAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(API_KEY_HEADER)))
        // A single requirement containing both schemes means AND, not OR.
        .security(List.of(new SecurityRequirement().addList("bearerAuth").addList("apiKeyAuth")));
  }

  @Bean
  JwtDecoder jwtDecoder(
      @Value("${security.jwt.secret}") String secret,
      @Value("${security.jwt.issuer}") String issuer,
      @Value("${security.jwt.audience}") String audience) {
    byte[] key = Base64.getDecoder().decode(secret);
    if (key.length < 32)
      throw new IllegalArgumentException(
          "JWT_SECRET must be Base64 encoding at least 32 random bytes");
    var decoder =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    OAuth2TokenValidator<Jwt> requiredClaims =
        jwt -> {
          boolean valid =
              jwt.getSubject() != null
                  && !jwt.getSubject().isBlank()
                  && jwt.getSubject().length() <= 200
                  && !jwt.getSubject().equals("legacy-unclaimed")
                  && jwt.getExpiresAt() != null
                  && jwt.getAudience().contains(audience);
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(
                  new OAuth2Error(
                      "invalid_token", "Required JWT claims are missing or invalid", null));
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), requiredClaims));
    return decoder;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Value("${security.api-key}") String apiKey,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
      throws Exception {
    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/error")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, ACCOUNTS_PATH)
                    .hasAuthority("SCOPE_accounts:write")
                    .requestMatchers(HttpMethod.GET, "/api/accounts/**")
                    .hasAuthority("SCOPE_accounts:read")
                    .anyRequest()
                    .denyAll())
        .csrf(csrf -> csrf.ignoringRequestMatchers(ACCOUNTS_PATH, "/api/accounts/**"))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> {})
                    .authenticationEntryPoint(
                        (req, res, ex) -> resolver.resolveException(req, res, null, ex))
                    .accessDeniedHandler(
                        (req, res, ex) -> resolver.resolveException(req, res, null, ex)))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (req, res, ex) -> resolver.resolveException(req, res, null, ex))
                    .accessDeniedHandler(
                        (req, res, ex) -> resolver.resolveException(req, res, null, ex)))
        .addFilterBefore(new ApiKeyFilter(apiKey, resolver), BearerTokenAuthenticationFilter.class)
        .build();
  }
}
