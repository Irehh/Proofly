package com.proofly.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Asymmetric RS256 JWT issuer & verifier supporting live key rotation via kid/JWKS.
 */
@Service
public class JwtKeyService {

    private final SecurityProperties props;
    private final CopyOnWriteArrayList<RSAKey> keyRing = new CopyOnWriteArrayList<>();

    public JwtKeyService(SecurityProperties props) {
        this.props = props;
        this.keyRing.add(generateRsaKey(props.jwt().keyId()));
    }

    public String issueAccessToken(UUID userId, UUID tenantId, UUID membershipId, String role, String jti) {
        RSAKey signingKey = keyRing.getFirst();
        Instant now = Instant.now();
        long ttl = Math.min(900L, props.jwt().accessTokenTtlSeconds());
        Instant exp = now.plusSeconds(ttl);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.jwt().issuer())
                .subject(userId.toString())
                .jwtID(jti)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("tid", tenantId.toString())
                .claim("mid", membershipId.toString())
                .claim("role", role)
                .claim("ver", 1)
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims
            );
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT access token", e);
        }
    }

    public synchronized String rotateKey(String newKeyId) {
        RSAKey next = generateRsaKey(newKeyId);
        keyRing.addFirst(next);
        while (keyRing.size() > 3) {
            keyRing.removeLast();
        }
        return next.getKeyID();
    }

    public Map<String, Object> publicJwks() {
        List<JWK> publicKeys = new ArrayList<>();
        for (RSAKey key : keyRing) {
            publicKeys.add(key.toPublicJWK());
        }
        return new JWKSet(publicKeys).toJSONObject();
    }

    public JwtDecoder buildJwtDecoder() {
        return token -> {
            List<JWK> publicKeys = new ArrayList<>();
            for (RSAKey key : keyRing) {
                publicKeys.add(key.toPublicJWK());
            }
            ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
            jwtProcessor.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(new JWKSet(publicKeys)))
            );
            NimbusJwtDecoder decoder = new NimbusJwtDecoder(jwtProcessor);
            try {
                Jwt decoded = decoder.decode(token);
                if (!props.jwt().issuer().equals(decoded.getIssuer() != null ? decoded.getIssuer().toString() : null)) {
                    throw new JwtException("Invalid JWT issuer");
                }
                return decoded;
            } catch (JwtException e) {
                throw e;
            } catch (Exception e) {
                throw new JwtException("Invalid JWT token", e);
            }
        };
    }

    private static RSAKey generateRsaKey(String kid) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair pair = kpg.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(kid)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA JWK", e);
        }
    }
}
