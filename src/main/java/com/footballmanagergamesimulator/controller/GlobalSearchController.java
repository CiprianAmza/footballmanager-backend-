package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.GlobalSearchResponse;
import com.footballmanagergamesimulator.service.GlobalSearchService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class GlobalSearchController {

    private final GlobalSearchService globalSearchService;

    public GlobalSearchController(GlobalSearchService globalSearchService) {
        this.globalSearchService = globalSearchService;
    }

    @GetMapping
    public GlobalSearchResponse search(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(defaultValue = "6") int limit) {
        return globalSearchService.search(q, limit);
    }
}
