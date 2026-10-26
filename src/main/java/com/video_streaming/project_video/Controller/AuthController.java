package com.video_streaming.project_video.Controller;

import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import com.video_streaming.project_video.Entity.FirebaseRefreshTokenResponse;
import com.video_streaming.project_video.Entity.FirebaseSignInResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.video_streaming.project_video.Service.FirebaseAuthService;
import com.video_streaming.project_video.Service.UserService;

import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

import com.video_streaming.project_video.DTOs.SystemHealthDTO;
import com.video_streaming.project_video.Service.SystemHealthService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/app")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private final FirebaseAuthService firebaseAuthService;
    private final UserService userService;
    private final SystemHealthService healthService;

    @GetMapping("/health")
    public ResponseEntity<SystemHealthDTO> healthCheck() {
        return ResponseEntity.ok(healthService.getSystemHealth());
    }

    @PostMapping("/createUser")
    public ResponseEntity<String> createUser(
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "user_name", required = false) String user_name,
            @RequestParam(value = "fullName", required = false) String fullName,
            @RequestParam(value = "full_name", required = false) String full_name,
            @RequestParam(value = "avatar_url", required = false) String avatar_url
    ) {
        try {
            String finalUsername = (username != null && !username.isBlank()) ? username : user_name;
            String finalFullName = (fullName != null && !fullName.isBlank()) ? fullName : full_name;
            if (finalUsername == null || finalUsername.isBlank()) {
                return ResponseEntity.status(400).body("Error creating user: Username is required");
            }
            userService.create(email, password, finalUsername, finalFullName, avatar_url);
            logger.info("Successfully created user: {}", finalUsername);
            return ResponseEntity.ok("User created successfully!");
        } catch (Exception e) {
            logger.error("Error creating user: {}", e.getMessage());
            return ResponseEntity.status(400).body("Error creating user: " + e.getMessage());
        }
    }

    @PostMapping("/verifyToken")
    public ResponseEntity<Map<String, String>> verifyToken(@RequestBody String idToken) {
        try {
            FirebaseToken decodedToken = firebaseAuthService.verifyToken(idToken);
            String uid = decodedToken.getUid();

            Map<String, String> response = new HashMap<>();
            response.put("id", uid);

            return ResponseEntity.ok(response);
        } catch (FirebaseAuthException e) {
            logger.error(e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "Invalid token: " + e.getMessage());
            return ResponseEntity.status(401).body(error);
        }
    }


    @PostMapping("/login")
    public ResponseEntity<FirebaseSignInResponse> login(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "identifier", required = false) String identifier,
            @RequestParam("password") String password,
            HttpServletResponse response
    ) {
        String loginIdentifier = (identifier != null && !identifier.isBlank()) ? identifier
                : ((username != null && !username.isBlank()) ? username : email);

        if (loginIdentifier == null || loginIdentifier.isBlank()) {
            throw new IllegalArgumentException("Email or username is required");
        }

        FirebaseSignInResponse firebaseResponse = firebaseAuthService.signIn(loginIdentifier, password);

        Cookie cookie = new Cookie("token", firebaseResponse.getIdToken());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24); // 1 day
        cookie.setSecure(true);
        response.addCookie(cookie);

        return ResponseEntity.ok(firebaseResponse);
    }

    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Boolean>> checkUsername(@RequestParam String username) {
        boolean valid = userService.isUsernameValid(username);
        boolean available = userService.isUsernameAvailable(username);
        return ResponseEntity.ok(Map.of("available", available, "valid", valid));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<FirebaseRefreshTokenResponse> refreshToken(@RequestParam String refreshToken) {
        FirebaseRefreshTokenResponse response = firebaseAuthService.refreshIdToken(refreshToken);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetUser(@RequestParam("email") String email){
        return firebaseAuthService.sendPasswordResetEmail(email);
    }

    /**
     * Firebase Google Sign-In & Seamless Account Sync.
     * Supports JSON body or form parameters (idToken, optional username/fullName).
     * Automatically registers new users with clean handles or updates existing profiles,
     * sets HTTP-only session cookie, and returns rich GoogleLoginResponseDTO.
     */
    @RequestMapping(
            value = {"/google-login", "/auth/google", "/oauth/google"},
            method = {RequestMethod.POST, RequestMethod.GET}
    )
    public ResponseEntity<com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO> googleLogin(
            @RequestBody(required = false) com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO bodyDTO,
            @RequestParam(value = "idToken", required = false) String paramIdToken,
            @RequestParam(value = "token", required = false) String paramToken,
            @RequestParam(value = "username", required = false) String paramUsername,
            @RequestParam(value = "fullName", required = false) String paramFullName,
            HttpServletResponse response
    ) {
        String token = (bodyDTO != null && bodyDTO.getIdToken() != null && !bodyDTO.getIdToken().isBlank())
                ? bodyDTO.getIdToken()
                : ((paramIdToken != null && !paramIdToken.isBlank()) ? paramIdToken : paramToken);

        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Firebase ID token (idToken) is required for Google Sign-In");
        }

        String username = (bodyDTO != null && bodyDTO.getUsername() != null && !bodyDTO.getUsername().isBlank())
                ? bodyDTO.getUsername() : paramUsername;

        String fullName = (bodyDTO != null && bodyDTO.getFullName() != null && !bodyDTO.getFullName().isBlank())
                ? bodyDTO.getFullName() : paramFullName;

        com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO loginRequest =
                com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO.builder()
                        .idToken(token.trim())
                        .username(username)
                        .fullName(fullName)
                        .build();

        com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO loginResponse =
                firebaseAuthService.signInWithGoogle(loginRequest);

        // Set secure HTTP-only cookie
        Cookie cookie = new Cookie("token", loginResponse.getIdToken());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(60 * 60 * 24); // 1 day
        cookie.setSecure(true);
        response.addCookie(cookie);

        return ResponseEntity.ok(loginResponse);
    }
}