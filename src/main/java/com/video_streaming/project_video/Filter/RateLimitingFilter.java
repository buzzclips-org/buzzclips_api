package com.video_streaming.project_video.Filter;

import com.video_streaming.project_video.Service.RateLimitingService;
import com.video_streaming.project_video.Utils.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(10)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitingService rateLimitingService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Determine rate limit policy based on path
        RateLimitPolicy policy = resolvePolicy(path, method);
        if (policy != null) {
            String clientKey = resolveClientKey(request, policy.tier);
            boolean allowed = rateLimitingService.isAllowed(clientKey, policy.maxRequests, policy.windowSeconds);
            if (!allowed) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.setHeader("Retry-After", String.valueOf(policy.windowSeconds));
                response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded for " 
                        + policy.tier + ". Please retry in " + policy.windowSeconds + " seconds.\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private record RateLimitPolicy(String tier, int maxRequests, int windowSeconds) {}

    private RateLimitPolicy resolvePolicy(String path, String method) {
        if (path.startsWith("/api/app/")) {
            // Auth endpoints (login, register, username checks): 30 requests / 60 seconds
            return new RateLimitPolicy("auth", 30, 60);
        }
        if (path.startsWith("/api/vid/upload")) {
            // Upload endpoints: 10 requests / 60 seconds
            return new RateLimitPolicy("upload", 10, 60);
        }
        if (path.contains("/comments") && "POST".equalsIgnoreCase(method)) {
            // Comments creation: 20 comments / 60 seconds
            return new RateLimitPolicy("comments", 20, 60);
        }
        if ((path.endsWith("/like") || path.endsWith("/dislike")) && "POST".equalsIgnoreCase(method)) {
            // Likes/dislikes toggle: 60 reactions / 60 seconds
            return new RateLimitPolicy("reactions", 60, 60);
        }
        return null;
    }

    private String resolveClientKey(HttpServletRequest request, String tier) {
        String userId = SecurityUtils.getCurrentUserIdOptional().orElse(null);
        if (userId != null && !userId.isBlank()) {
            return tier + ":user:" + userId;
        }
        String ip = extractClientIp(request);
        return tier + ":ip:" + ip;
    }

    private String extractClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
