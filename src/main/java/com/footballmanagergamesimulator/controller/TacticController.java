package com.footballmanagergamesimulator.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tactic")
@CrossOrigin(origins = "http://localhost:4200")
public class TacticController {

    @PostMapping("/firstEleven")
    public void saveFirstEleven() {
        // TODO
    }
}
