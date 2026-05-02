package com.genius.hz.admin.web;

import com.genius.hz.admin.domain.User;
import com.genius.hz.admin.repo.UserRepository;
import com.genius.hz.admin.security.AppUserDetails;
import com.genius.hz.admin.security.JwtService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Browser session login + change-password")
public class AuthController {

    private final AuthenticationManager authMgr;
    private final UserRepository        users;
    private final PasswordEncoder       enc;
    private final SecurityContextRepository contextRepo = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authMgr, UserRepository users, PasswordEncoder enc) {
        this.authMgr = authMgr;
        this.users   = users;
        this.enc     = enc;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String,Object>> login(@Valid @RequestBody LoginRequest req,
                                                    HttpServletRequest http, HttpServletResponse res) {
        Authentication auth = authMgr.authenticate(
            new UsernamePasswordAuthenticationToken(req.username, req.password));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, http, res);

        AppUserDetails ud = (AppUserDetails) auth.getPrincipal();
        users.findByUsername(ud.getUsername()).ifPresent(u -> {
            u.setLastLoginAt(Instant.now());
            u.setFailedLoginCount(0);
            users.save(u);
        });
        return ResponseEntity.ok(meBody(ud));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String,Object>> me(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(meBody((AppUserDetails) auth.getPrincipal()));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePwRequest req, Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        String encodedPwd = enc.encode(req.currentPassword);
        User u = users.findByUsername(auth.getName())
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + auth.getName()));
        if (!enc.matches(req.currentPassword, u.getPasswordHash())) {
            return ResponseEntity.status(400).build();
        }
        if (req.newPassword == null || req.newPassword.length() < 12) {
            return ResponseEntity.status(400).build();   // length policy enforced in v1
        }
        u.setPasswordHash(enc.encode(req.newPassword));
        u.setMustChangePassword(false);
        u.setUpdatedAt(Instant.now());
        users.save(u);
        return ResponseEntity.noContent().build();
    }

    private static Map<String,Object> meBody(AppUserDetails ud) {
        List<String> roles = ud.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toList());
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("username",           ud.getUsername());
        body.put("fullName",           ud.getUser().getFullName() == null ? "" : ud.getUser().getFullName());
        body.put("email",              ud.getUser().getEmail()    == null ? "" : ud.getUser().getEmail());
        body.put("roles",              roles);
        body.put("mustChangePassword", ud.isMustChangePassword());
        body.put("authSource",         ud.getUser().getAuthSource());
        return body;
    }

    @Data public static class LoginRequest {
        @NotBlank private String username;
        @NotBlank private String password;
    }
    @Data public static class ChangePwRequest {
        @NotBlank private String currentPassword;
        @NotBlank private String newPassword;
    }
}
