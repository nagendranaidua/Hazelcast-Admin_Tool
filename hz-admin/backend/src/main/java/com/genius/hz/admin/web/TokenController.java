package com.genius.hz.admin.web;

import com.genius.hz.admin.security.AppUserDetails;
import com.genius.hz.admin.security.JwtService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Bearer-token endpoints for service-to-service / OpenAPI consumers. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Token", description = "JWT bearer-token issuance for API integrations")
public class TokenController {

    private final AuthenticationManager authMgr;
    private final JwtService jwt;

    public TokenController(AuthenticationManager authMgr, JwtService jwt) {
        this.authMgr = authMgr;
        this.jwt     = jwt;
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String,Object>> token(@Valid @RequestBody TokenRequest req) {
        Authentication a = authMgr.authenticate(
            new UsernamePasswordAuthenticationToken(req.username, req.password));
        AppUserDetails ud = (AppUserDetails) a.getPrincipal();
        List<String> roles = ud.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
            .collect(Collectors.toList());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("tokenType",    "Bearer");
        body.put("accessToken",  jwt.issueAccess(ud.getUsername(), roles));
        body.put("refreshToken", jwt.issueRefresh(ud.getUsername()));
        body.put("username",     ud.getUsername());
        body.put("roles",        roles);
        return ResponseEntity.ok(body);
    }

    @Data public static class TokenRequest {
        @NotBlank private String username;
        @NotBlank private String password;
    }
}
