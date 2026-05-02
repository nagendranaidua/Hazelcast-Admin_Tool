package com.genius.hz.admin.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Blocks every endpoint except /api/auth/* until a freshly-seeded user has
 * rotated their temporary password. Returns 428 PRECONDITION_REQUIRED with a
 * stable error code so the React app can route the user to the change-pw screen.
 */
@Component
public class ForcePasswordChangeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = req.getRequestURI();
        if (path.startsWith("/api/auth/") || path.startsWith("/swagger") || path.startsWith("/api-docs")
                || path.startsWith("/actuator") || path.equals("/")) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails) {
            AppUserDetails u = (AppUserDetails) auth.getPrincipal();
            if (u.isMustChangePassword()) {
                res.setStatus(HttpStatus.PRECONDITION_REQUIRED.value());
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"MUST_CHANGE_PASSWORD\","
                        + "\"message\":\"Rotate temporary password before using the app.\"}");
                return false;
            }
        }
        return true;
    }
}
