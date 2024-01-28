package com.footballmanagergamesimulator.config;



import com.footballmanagergamesimulator.user.UserDetailsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfiguration implements WebMvcConfigurer {

    @Autowired
    UserDetailsServiceImpl userDetailsService;

    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/home").setViewName("signup_form");
        registry.addViewController("/").setViewName("home");
        registry.addViewController("/hello").setViewName("hello");
        registry.addViewController("/login").setViewName("login");
    }

}