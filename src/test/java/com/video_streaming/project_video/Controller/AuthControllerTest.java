package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.Entity.FirebaseSignInResponse;
import com.video_streaming.project_video.Service.FirebaseAuthService;
import com.video_streaming.project_video.Service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthControllerTest {

    private AuthController authController;
    private StubFirebaseAuthService firebaseAuthService;
    private StubUserService userService;
    private com.video_streaming.project_video.Service.SystemHealthService healthService;

    @BeforeEach
    void setUp() {
        firebaseAuthService = new StubFirebaseAuthService();
        userService = new StubUserService();
        healthService = () -> com.video_streaming.project_video.DTOs.SystemHealthDTO.builder()
                .status("UP")
                .timestamp("2026-10-20T12:00:00Z")
                .uptimeSeconds(100)
                .version("0.0.1")
                .services(java.util.Map.of())
                .build();
        authController = new AuthController(firebaseAuthService, userService, healthService);
    }

    @Test
    @DisplayName("Should return structured SystemHealthDTO from healthCheck endpoint")
    void testHealthCheck() {
        ResponseEntity<com.video_streaming.project_video.DTOs.SystemHealthDTO> response = authController.healthCheck();
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().getStatus());
    }

    @Test
    @DisplayName("Should successfully login using username parameter")
    void testLoginWithUsername() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<FirebaseSignInResponse> result = authController.login(null, "johndoe", null, "secret123", response);

        assertNotNull(result);
        assertEquals(200, result.getStatusCode().value());
        assertEquals("johndoe", firebaseAuthService.lastIdentifier);
        assertEquals("secret123", firebaseAuthService.lastPassword);

        Cookie tokenCookie = response.getCookie("token");
        assertNotNull(tokenCookie);
        assertEquals("mock-id-token", tokenCookie.getValue());
        assertTrue(tokenCookie.isHttpOnly());
        assertTrue(tokenCookie.getSecure());
    }

    @Test
    @DisplayName("Should successfully login using email parameter")
    void testLoginWithEmail() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<FirebaseSignInResponse> result = authController.login("john@example.com", null, null, "secret123", response);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("john@example.com", firebaseAuthService.lastIdentifier);
    }

    @Test
    @DisplayName("Should successfully login using identifier parameter")
    void testLoginWithIdentifier() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<FirebaseSignInResponse> result = authController.login(null, null, "coolcoder", "secret123", response);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("coolcoder", firebaseAuthService.lastIdentifier);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when email/username/identifier are all blank")
    void testLoginWithMissingIdentifier() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(IllegalArgumentException.class, () ->
                authController.login(null, null, null, "secret123", response)
        );
        assertThrows(IllegalArgumentException.class, () ->
                authController.login("   ", "", "  ", "secret123", response)
        );
    }

    @Test
    @DisplayName("Should check username availability via /check-username endpoint")
    void testCheckUsernameAvailability() {
        userService.available = true;
        ResponseEntity<Map<String, Boolean>> res1 = authController.checkUsername("free_user");
        assertTrue(res1.getBody().get("available"));

        userService.available = false;
        ResponseEntity<Map<String, Boolean>> res2 = authController.checkUsername("taken_user");
        assertFalse(res2.getBody().get("available"));
    }

    @Test
    @DisplayName("Should successfully authenticate via Google login JSON payload and set HTTP-only cookie")
    void testGoogleLoginWithJson() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO request =
                com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO.builder()
                        .idToken("valid-google-firebase-token")
                        .username("google_hero")
                        .fullName("Google Hero")
                        .build();

        ResponseEntity<com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO> result =
                authController.googleLogin(request, null, null, null, null, response);

        assertNotNull(result);
        assertEquals(200, result.getStatusCode().value());
        com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO body = result.getBody();
        assertNotNull(body);
        assertEquals("valid-google-firebase-token", body.getIdToken());
        assertTrue(body.isNewUser());
        assertEquals("google.com", body.getPrimaryProvider());
        assertEquals("google_hero", body.getUser().getUsername());

        Cookie cookie = response.getCookie("token");
        assertNotNull(cookie);
        assertEquals("valid-google-firebase-token", cookie.getValue());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
    }

    @Test
    @DisplayName("Should successfully authenticate via Google login form parameters")
    void testGoogleLoginWithFormParams() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO> result =
                authController.googleLogin(null, "param-google-token", null, "custom_handle", "Custom Name", response);

        assertNotNull(result);
        assertEquals(200, result.getStatusCode().value());
        assertEquals("param-google-token", result.getBody().getIdToken());
        assertNotNull(response.getCookie("token"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when Google ID token is missing")
    void testGoogleLoginMissingToken() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(IllegalArgumentException.class, () ->
                authController.googleLogin(null, null, null, null, null, response)
        );
        assertThrows(IllegalArgumentException.class, () ->
                authController.googleLogin(new com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO(), "   ", "", null, null, response)
        );
    }


    static class StubFirebaseAuthService implements FirebaseAuthService {
        String lastIdentifier;
        String lastPassword;

        @Override
        public com.google.firebase.auth.FirebaseToken verifyToken(String idToken) { return null; }

        @Override
        public FirebaseSignInResponse signInWithEmailAndPassword(String email, String password) {
            return signIn(email, password);
        }

        @Override
        public FirebaseSignInResponse signIn(String identifier, String password) {
            this.lastIdentifier = identifier;
            this.lastPassword = password;
            FirebaseSignInResponse response = new FirebaseSignInResponse();
            response.setIdToken("mock-id-token");
            response.setRefreshToken("mock-refresh-token");
            return response;
        }

        @Override
        public com.video_streaming.project_video.Entity.FirebaseRefreshTokenResponse refreshIdToken(String refreshToken) { return null; }

        @Override
        public ResponseEntity<String> sendPasswordResetEmail(String email) { return ResponseEntity.ok("OK"); }

        @Override
        public com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO signInWithGoogle(com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO request) {
            String handle = (request.getUsername() != null && !request.getUsername().isBlank()) ? request.getUsername() : "google_user";
            String name = (request.getFullName() != null && !request.getFullName().isBlank()) ? request.getFullName() : "Google User";
            com.video_streaming.project_video.DTOs.UserDTO user = com.video_streaming.project_video.DTOs.UserDTO.builder()
                    .userId("google-uid-123")
                    .username(handle)
                    .fullName(name)
                    .user_email("user@gmail.com")
                    .avatar_url("https://google.com/avatar.png")
                    .user_role("USER")
                    .build();

            return com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO.builder()
                    .idToken(request.getIdToken())
                    .refreshToken("google-mock-refresh-token")
                    .isNewUser(true)
                    .primaryProvider("google.com")
                    .providers(java.util.List.of("google.com"))
                    .emailVerified(true)
                    .user(user)
                    .build();
        }
    }


    static class StubUserService implements UserService {
        boolean available = true;

        @Override
        public void create(String emailId, String password, String user_name, String avatar_url) {}

        @Override
        public String updateUserProfile(com.video_streaming.project_video.DTOs.UserDTO userDTO) { return "OK"; }

        @Override
        public com.video_streaming.project_video.DTOs.UserDTO getUserById(String userId) { return null; }

        @Override
        public String resolveEmailFromIdentifier(String identifier) { return identifier; }

        @Override
        public boolean isUsernameAvailable(String username) { return available; }
    }
}
