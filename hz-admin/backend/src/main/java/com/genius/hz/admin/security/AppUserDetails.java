package com.genius.hz.admin.security;

import com.genius.hz.admin.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Spring Security wrapper around our domain User. Roles are emitted as
 * authorities prefixed with "ROLE_" so @PreAuthorize("hasRole('SUPER_ADMIN')") works.
 */
@Getter
public class AppUserDetails implements UserDetails {

    private final User user;
    private final Collection<? extends GrantedAuthority> authorities;

    public AppUserDetails(User user) {
        this.user = user;
        this.authorities = user.getRoles().stream()
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getName()))
            .collect(Collectors.toSet());
    }

    @Override public String  getPassword()              { return user.getPasswordHash(); }
    @Override public String  getUsername()              { return user.getUsername(); }
    @Override public boolean isAccountNonExpired()      { return true; }
    @Override public boolean isAccountNonLocked()       { return !user.isLocked(); }
    @Override public boolean isCredentialsNonExpired()  { return true; }
    @Override public boolean isEnabled()                { return user.isEnabled(); }

    public boolean isMustChangePassword() { return user.isMustChangePassword(); }
}
