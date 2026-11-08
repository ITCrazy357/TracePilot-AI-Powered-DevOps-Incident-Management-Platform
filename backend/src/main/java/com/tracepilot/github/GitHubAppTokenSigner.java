package com.tracepilot.github;

import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GitHubAppTokenSigner {
    private final GitHubProperties properties;
    private final Clock clock;

    public GitHubAppTokenSigner(GitHubProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String createToken() {
        properties.requireEnabled();
        var now = clock.instant();
        var claims = new JWTClaimsSet.Builder()
            .issuer(Long.toString(properties.appId()))
            .issueTime(Date.from(now.minusSeconds(60)))
            .expirationTime(Date.from(now.plusSeconds(540)))
            .build();
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();
        var token = new SignedJWT(header, claims);
        try {
            token.sign(new RSASSASigner(readPrivateKey()));
            return token.serialize();
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | JOSEException exception) {
            // Never include the PEM, its path, or token contents in an HTTP error.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "GitHub signing key must be an RSA PKCS#8 PEM file.");
        }
    }

    private RSAPrivateKey readPrivateKey() throws IOException, GeneralSecurityException {
        var pem = Files.readString(properties.privateKeyPath());
        if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")) {
            throw new GeneralSecurityException("PKCS#8 required");
        }
        var encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        var specification = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(specification);
    }
}
