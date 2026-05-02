package com.genius.hz.admin.config;

import com.genius.hz.admin.security.ForcePasswordChangeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ForcePasswordChangeInterceptor forceChange;

    public WebMvcConfig(ForcePasswordChangeInterceptor forceChange) { this.forceChange = forceChange; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(forceChange).addPathPatterns("/api/**");
    }
}
