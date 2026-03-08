package com.footballmanagergamesimulator.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CrossOrigin;

@Controller
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class LoginController {

    @GetMapping("/login")
    public String login() throws Exception {
        return "login";
    }
}
