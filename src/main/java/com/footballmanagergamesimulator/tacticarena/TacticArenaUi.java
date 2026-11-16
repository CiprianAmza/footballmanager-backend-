package com.footballmanagergamesimulator.tacticarena;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * With {@code tactic-arena.ui-dir} set (the simulator build of the interface), the backend
 * serves the interface itself: static files from that folder, and {@code index.html} for
 * the page routes of Tactic Arena. The JSON routes under {@code /tactic-arena/...} are
 * mapped by {@link TacticArenaController} and keep precedence.
 */
@Controller
@ConditionalOnExpression("!'${tactic-arena.ui-dir:}'.isBlank()")
public class TacticArenaUi implements WebMvcConfigurer {

    /** Static file patterns opened in the security configuration while the UI is served. */
    public static final String[] PUBLIC_PATHS = {
            "/", "/index.html", "/*.js", "/*.css", "/*.ico", "/*.txt", "/*.png", "/*.svg", "/*.webmanifest",
            "/*.woff", "/*.woff2", "/*.ttf", "/*.json",
            "/assets/**", "/media/**"
    };

    private final Path uiDir;

    public TacticArenaUi(@Value("${tactic-arena.ui-dir}") String uiDir) {
        this.uiDir = Path.of(uiDir.trim()).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = uiDir.toUri().toString();
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/**").addResourceLocations(location)
                .setCacheControl(CacheControl.noCache());
    }

    @GetMapping({"/", "/tactic-arena", "/tactic-arena/new", "/tactic-arena/{id:\\d+}"})
    public ResponseEntity<FileSystemResource> index() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache())
                .body(new FileSystemResource(uiDir.resolve("index.html")));
    }
}
