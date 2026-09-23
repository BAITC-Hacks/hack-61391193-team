package astana.innovation.backendakim.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;

    public JwtTokenService(JwtEncoder encoder, @Value("${app.auth.jwt.issuer:akim-backend}") String issuer,
                           @Value("${app.auth.jwt.audience:akim-api}") String audience,
                           @Value("${app.auth.jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
    }

    public AuthResponse issue(AuthUserResponse user) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(user.id().toString()).issuedAt(now).expiresAt(now.plusSeconds(ttlSeconds))
                .id(UUID.randomUUID().toString()).claim("role", user.role()).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthResponse(token, "Bearer", ttlSeconds, user);
    }
}
