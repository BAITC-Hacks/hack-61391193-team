package astana.innovation.backendakim.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@Profile("postgres")
public class JwtConfiguration {
    @Bean
    SecretKey jwtSigningKey(@Value("${app.auth.jwt.secret}") String encodedSecret,
                            @Value("${app.auth.jwt.ttl-seconds:3600}") long ttlSeconds) {
        if (ttlSeconds < 60 || ttlSeconds > 86400) {
            throw new IllegalStateException("JWT_TTL_SECONDS должен быть от 60 до 86400.");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET должен быть ключом в Base64.");
        }
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET должен содержать не менее 32 случайных байт в Base64.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey,
                          @Value("${app.auth.jwt.issuer:akim-backend}") String issuer,
                          @Value("${app.auth.jwt.audience:akim-api}") String audience) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        var claimConverter = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(claims -> {
            // The default converter can synthesize iat from exp; require the original signed fields.
            if (claims.get(JwtClaimNames.IAT) == null || claims.get(JwtClaimNames.EXP) == null) {
                throw new BadJwtException("Required JWT timestamps are missing");
            }
            return claimConverter.convert(claims);
        });
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audiences -> audiences != null && audiences.contains(audience)),
                jwt -> {
                    try {
                        if (jwt.getSubject() == null || !UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject())
                                || jwt.getExpiresAt() == null || jwt.getIssuedAt() == null
                                || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                                || jwt.getIssuedAt().isAfter(Instant.now())) {
                            return invalidClaims();
                        }
                        return OAuth2TokenValidatorResult.success();
                    } catch (IllegalArgumentException exception) {
                        return invalidClaims();
                    }
                }));
        return decoder;
    }

    private static OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid JWT claims", null));
    }
}
