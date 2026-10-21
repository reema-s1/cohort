package dev.cohort.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Two keys: the admin key for {@code /api/**} (flag changes, results) and the SDK key for
 * {@code /sdk/**} (config reads, event ingest). The admin key also works on SDK endpoints.
 * Swagger and actuator stay open.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final SecurityProperties properties;

    public ApiKeyFilter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String key = request.getHeader(HEADER);
        boolean allowed;
        if (path.startsWith("/api/")) {
            allowed = matches(key, properties.adminKey());
        } else if (path.startsWith("/sdk/")) {
            allowed = matches(key, properties.sdkKey()) || matches(key, properties.adminKey());
        } else {
            allowed = true;
        }
        if (!allowed) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"missing or invalid " + HEADER + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(String given, String expected) {
        if (given == null || expected == null) {
            return false;
        }
        // Constant-time compare so the key cannot be guessed byte by byte from response timing.
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
