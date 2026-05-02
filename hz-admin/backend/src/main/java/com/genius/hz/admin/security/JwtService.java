package com.genius.hz.admin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final SecretKey key;
    private final String    issuer;
    private final long      accessTtlSec;
    private final long      refreshTtlSec;

    public JwtService(@Value("${hz-admin.security.jwt.secret}") String secret,
                      @Value("${hz-admin.security.jwt.issuer}") String issuer,
                      @Value("${hz-admin.security.jwt.access-token-ttl-min}") long accessMin,
                      @Value("${hz-admin.security.jwt.refresh-token-ttl-days}") long refreshDays) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT secret must be >= 32 bytes; got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
        this.accessTtlSec  = accessMin * 60;
        this.refreshTtlSec = refreshDays * 24 * 3600;
    }

    public String issueAccess(String username, List<String> roles) {
        return build(username, roles, accessTtlSec, "access");
    }

    public String issueRefresh(String username) {
        return build(username, Collections.emptyList(), refreshTtlSec, "refresh");
    }

    private String build(String username, List<String> roles, long ttl, String type) {
        Instant now = Instant.now();
        return Jwts.builder()
            .setIssuer(issuer)
            .setSubject(username)
            .claim("roles", roles)
            .claim("type", type)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plusSeconds(ttl)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).requireIssuer(issuer).build()
            .parseClaimsJws(token).getBody();
    }

    public List<String> rolesFrom(Claims c) {
        Object o = c.get("roles");
        if (o instanceof List) {
            return ((List<?>) o).stream().map(Object::toString).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
