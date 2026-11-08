package com.video_streaming.project_video.Filter;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

import static com.video_streaming.project_video.Enums.SupportVariables.WHITELISTED_PATHS;

@Component
public class FirebaseTokenFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseTokenFilter.class);
    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return; // To allow preflight requests to pass through
        }

        String path = request.getServletPath();
        boolean isWhitelisted = WHITELISTED_PATHS.stream()
                .anyMatch(whitelistPattern -> pathMatcher.match(whitelistPattern, path));

        String idToken = null;
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            idToken = authHeader.replace("Bearer ", "").trim();
        } else if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("token".equalsIgnoreCase(cookie.getName()) || "firebaseToken".equalsIgnoreCase(cookie.getName()) || "idToken".equalsIgnoreCase(cookie.getName())) {
                    if (cookie.getValue() != null && !cookie.getValue().isBlank()) {
                        idToken = cookie.getValue().trim();
                        break;
                    }
                }
            }
        }

        if (idToken != null && !idToken.isBlank()) {
            try {
                FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);
                String uid = decodedToken.getUid();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(uid, null, Collections.emptyList());

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (FirebaseAuthException e) {
                if (isWhitelisted) {
                    logger.warn("Invalid Firebase ID token on whitelisted path {}: {}", path, e.getMessage());
                } else {
                    logger.error("Authentication failed for path {}: {}", path, e.getMessage());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("Invalid or expired Firebase ID token");
                    return;
                }
            }
        } else if (!isWhitelisted) {
            logger.error("Authentication token not found in Authorization header or cookie for secured path: {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Missing or invalid Authorization header or token cookie");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
