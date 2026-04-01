package com.XYai.myai.Config;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/**
 * Development-only controller that redirects frontend requests to the Vite dev server.
 * Activate with the 'dev' Spring profile, e.g. -Dspring.profiles.active=dev
 */
@Controller
@Profile("dev")
public class DevProxyController {

    @RequestMapping(value = "/**")
    public ResponseEntity<Void> redirectToVite(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Do not intercept API, actuator, swagger or file requests (those usually contain a dot)
        if (path.startsWith("/api") || path.startsWith("/actuator") || path.startsWith("/swagger") || path.contains(".")) {
            return ResponseEntity.notFound().build();
        }

        String target = "http://localhost:5173" + (path.equals("/") ? "/" : path);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(target));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}

