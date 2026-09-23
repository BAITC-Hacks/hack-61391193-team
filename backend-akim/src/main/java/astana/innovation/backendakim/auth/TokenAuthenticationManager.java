package astana.innovation.backendakim.auth;

import astana.innovation.backendakim.history.AnonymousTokens;
import astana.innovation.backendakim.history.AnonymousUserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
public class TokenAuthenticationManager implements AuthenticationManager {
    private final JwtDecoder decoder;
    private final AccountRepository accounts;
    private final AnonymousUserRepository anonymousUsers;

    public TokenAuthenticationManager(JwtDecoder decoder, AccountRepository accounts,
                                      AnonymousUserRepository anonymousUsers) {
        this.decoder = decoder;
        this.accounts = accounts;
        this.anonymousUsers = anonymousUsers;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) {
            throw new BadCredentialsException("Invalid authentication type");
        }
        String token = bearer.getToken();
        try {
            // Compatibility with previously saved anonymous scenarios. Never grants account/admin roles.
            if (token.matches("[A-Za-z0-9_-]{43}")) {
                UUID id = anonymousUsers.findIdByTokenHash(AnonymousTokens.hash(token))
                        .orElseThrow(() -> new BadCredentialsException("Invalid token"));
                return UsernamePasswordAuthenticationToken.authenticated(id.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_LEGACY_USER")));
            }
            var jwt = decoder.decode(token);
            var account = accounts.findById(UUID.fromString(jwt.getSubject()))
                    .orElseThrow(() -> new BadCredentialsException("Invalid token"));
            // Read current roles from the database so a stale JWT cannot retain revoked privileges.
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + account.role())), account.id().toString());
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid token");
        } catch (DataAccessException exception) {
            throw new AuthenticationServiceException("Authentication storage unavailable", exception);
        }
    }
}
