package com.genius.hz.admin.security;

import com.genius.hz.admin.repo.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) { this.users = users; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
            .map(AppUserDetails::new)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
    }
}
