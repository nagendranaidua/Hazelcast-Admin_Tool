package com.genius.hz.admin.web;

import com.genius.hz.admin.domain.Role;
import com.genius.hz.admin.domain.User;
import com.genius.hz.admin.repo.RoleRepository;
import com.genius.hz.admin.repo.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.AbstractMap.SimpleEntry;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "User & role administration (SUPER_ADMIN only)")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserAdminController {

    private final UserRepository  users;
    private final RoleRepository  roles;
    private final PasswordEncoder enc;

    public UserAdminController(UserRepository u, RoleRepository r, PasswordEncoder e) {
        this.users = u; this.roles = r; this.enc = e;
    }

    @GetMapping
    public List<Map<String,Object>> list() {
        return users.findAll().stream().map(UserAdminController::dto).collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<Map<String,Object>> create(@Valid @RequestBody CreateUserRequest req) {
        if (users.existsByUsername(req.username))
            return ResponseEntity.status(409).build();
        Set<Role> rs = req.roles.stream()
            .map(name -> roles.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + name)))
            .collect(Collectors.toSet());
        User u = User.builder()
            .username(req.username)
            .fullName(req.fullName)
            .email(req.email)
            .passwordHash(enc.encode(req.tempPassword))
            .mustChangePassword(true)
            .roles(rs)
            .build();
        return ResponseEntity.status(201).body(dto(users.save(u)));
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<Void> setEnabled(@PathVariable Long id, @RequestParam boolean value) {
        users.findById(id).ifPresent(u -> { u.setEnabled(value); users.save(u); });
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        users.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private static Map<String,Object> dto(User u) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id",                 u.getId());
        m.put("username",           u.getUsername());
        m.put("fullName",           u.getFullName() == null ? "" : u.getFullName());
        m.put("email",              u.getEmail()    == null ? "" : u.getEmail());
        m.put("enabled",            u.isEnabled());
        m.put("mustChangePassword", u.isMustChangePassword());
        m.put("authSource",         u.getAuthSource());
        m.put("roles",              u.getRoles().stream().map(Role::getName).collect(Collectors.toList()));
        return m;
    }

    @Data public static class CreateUserRequest {
        @NotBlank private String username;
        private String fullName;
        private String email;
        @NotBlank private String tempPassword;
        private Set<String> roles = new HashSet<>();
    }
}
