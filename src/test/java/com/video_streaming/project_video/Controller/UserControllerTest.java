package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.DTOs.UserDTO;
import com.video_streaming.project_video.Service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private com.video_streaming.project_video.Service.FirebaseAuthService firebaseAuthService;

    @InjectMocks
    private UserController userController;


    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testUpdateUser_JsonWithUserId() {
        UserDTO dto = UserDTO.builder()
                .userId("user-123")
                .fullName("John Doe")
                .username("johndoe")
                .avatar_url("https://avatar.png")
                .build();

        when(userService.updateUserProfile(any(UserDTO.class))).thenReturn("User updated successfully");

        ResponseEntity<String> response = userController.updateUserJson(dto, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("User updated successfully", response.getBody());
        verify(userService).updateUserProfile(argThat(u -> 
                "user-123".equals(u.getUserId()) && "John Doe".equals(u.getFullName())));
    }

    @Test
    void testUpdateUser_JsonWithoutUserId_ExtractsFromSecurityContext() {
        // User logged in as 'auth-user-99'
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("auth-user-99", null, Collections.emptyList())
        );

        UserDTO dto = UserDTO.builder()
                .fullName("Jane Doe")
                .username("janedoe")
                .build();

        when(userService.updateUserProfile(any(UserDTO.class))).thenReturn("User updated successfully");

        ResponseEntity<String> response = userController.updateUserJson(dto, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("User updated successfully", response.getBody());
        verify(userService).updateUserProfile(argThat(u -> "auth-user-99".equals(u.getUserId())));
    }

    @Test
    void testUpdateUser_FormData_Success() {
        // Simulates FormData sent by bideo_ui/utility/requests.ts
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("creator-42", null, Collections.emptyList())
        );

        UserDTO formDTO = new UserDTO();
        formDTO.setUserId("creator-42");
        formDTO.setUser_name("great_creator");
        formDTO.setUsername("great_creator");
        formDTO.setFullName("Great Creator");
        formDTO.setUser_email("creator@example.com");
        formDTO.setAvatar_url("https://example.com/avatar.jpg");

        when(userService.updateUserProfile(any(UserDTO.class))).thenReturn("User updated successfully");

        ResponseEntity<String> response = userController.updateUserFormData(formDTO, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("User updated successfully", response.getBody());
        verify(userService).updateUserProfile(argThat(u -> "great_creator".equals(u.getUsername())));
    }

    @Test
    void testUpdateUser_MissingUserId_ReturnsBadRequest() {
        // No security context, and no userId in body
        UserDTO dto = UserDTO.builder()
                .fullName("Anonymous")
                .username("anon")
                .build();

        ResponseEntity<String> response = userController.updateUserJson(dto, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("User ID is required"));
        verify(userService, never()).updateUserProfile(any());
    }

    @Test
    void testUpdateUser_ForbiddenWhenCallerTriesToUpdateDifferentUser() {
        // Logged in as 'user-A'
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-A", null, Collections.emptyList())
        );

        // Trying to update 'user-B'
        UserDTO dto = UserDTO.builder()
                .userId("user-B")
                .fullName("Hacked Name")
                .build();

        when(userService.getUserById("user-B")).thenReturn(UserDTO.builder().userId("user-B").build());

        ResponseEntity<String> response = userController.updateUserJson(dto, null, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(userService, never()).updateUserProfile(any());
    }

    @Test
    void testGetUserById() {
        UserDTO mockUser = UserDTO.builder()
                .userId("user-1")
                .username("mkbhd")
                .fullName("Marques Brownlee")
                .build();
        when(userService.getUserById("mkbhd")).thenReturn(mockUser);

        ResponseEntity<?> response = userController.getUserById("mkbhd");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockUser, response.getBody());
    }

    @Test
    void testSearchUsers() {
        List<UserDTO> results = List.of(UserDTO.builder().userId("1").username("tech_reviewer").build());
        when(userService.searchUsers("tech", 5)).thenReturn(results);

        ResponseEntity<List<UserDTO>> response = userController.searchUsers("tech", 5);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void testGetAccountSecurity_Authenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        com.video_streaming.project_video.DTOs.AccountSecurityDTO profile =
                com.video_streaming.project_video.DTOs.AccountSecurityDTO.builder()
                        .userId("user-test-uid")
                        .email("user@example.com")
                        .hasGoogleProvider(true)
                        .hasPasswordProvider(true)
                        .canUnlinkGoogle(true)
                        .canUnlinkPassword(true)
                        .build();

        when(firebaseAuthService.getAccountSecurity("user-test-uid")).thenReturn(profile);

        ResponseEntity<?> response = userController.getAccountSecurity();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(profile, response.getBody());
    }

    @Test
    void testGetAccountSecurity_Unauthenticated() {
        // No authentication in SecurityContext
        ResponseEntity<?> response = userController.getAccountSecurity();

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void testUpdateAccountEmail_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        com.video_streaming.project_video.DTOs.UpdateEmailRequestDTO request =
                com.video_streaming.project_video.DTOs.UpdateEmailRequestDTO.builder()
                        .newEmail("brand_new@example.com")
                        .build();

        when(firebaseAuthService.updateAccountEmail("user-test-uid", "brand_new@example.com"))
                .thenReturn("https://verify.link");
        when(firebaseAuthService.getAccountSecurity("user-test-uid"))
                .thenReturn(com.video_streaming.project_video.DTOs.AccountSecurityDTO.builder()
                        .email("brand_new@example.com")
                        .build());

        ResponseEntity<com.video_streaming.project_video.DTOs.AccountActionResponseDTO> response =
                userController.updateAccountEmail(request, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("https://verify.link", response.getBody().getActionLink());
        verify(firebaseAuthService).updateAccountEmail("user-test-uid", "brand_new@example.com");
    }

    @Test
    void testUpdateAccountEmail_MissingEmail_ReturnsBadRequest() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        ResponseEntity<com.video_streaming.project_video.DTOs.AccountActionResponseDTO> response =
                userController.updateAccountEmail(null, "", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void testSetAccountPassword_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        com.video_streaming.project_video.DTOs.SetPasswordRequestDTO request =
                com.video_streaming.project_video.DTOs.SetPasswordRequestDTO.builder()
                        .newPassword("securePass123!")
                        .build();

        when(firebaseAuthService.getAccountSecurity("user-test-uid"))
                .thenReturn(com.video_streaming.project_video.DTOs.AccountSecurityDTO.builder().hasPasswordProvider(true).build());

        ResponseEntity<com.video_streaming.project_video.DTOs.AccountActionResponseDTO> response =
                userController.setAccountPassword(request, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(firebaseAuthService).setAccountPassword("user-test-uid", "securePass123!");
    }

    @Test
    void testUnlinkGoogleProvider_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        com.video_streaming.project_video.DTOs.AccountSecurityDTO updated =
                com.video_streaming.project_video.DTOs.AccountSecurityDTO.builder().hasGoogleProvider(false).build();
        when(firebaseAuthService.unlinkProvider("user-test-uid", "google.com")).thenReturn(updated);

        ResponseEntity<com.video_streaming.project_video.DTOs.AccountActionResponseDTO> response =
                userController.unlinkGoogleProvider();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(firebaseAuthService).unlinkProvider("user-test-uid", "google.com");
    }

    @Test
    void testUnlinkGoogleProvider_SoleLoginMethod_FailsWithBadRequest() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        when(firebaseAuthService.unlinkProvider("user-test-uid", "google.com"))
                .thenThrow(new IllegalStateException("Cannot unlink Google: it is your only sign-in method. Please set a password first."));

        ResponseEntity<com.video_streaming.project_video.DTOs.AccountActionResponseDTO> response =
                userController.unlinkGoogleProvider();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertTrue(response.getBody().getMessage().contains("only sign-in method"));
    }

    @Test
    void testLinkGoogleProvider_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        com.video_streaming.project_video.DTOs.LinkGoogleRequestDTO request =
                com.video_streaming.project_video.DTOs.LinkGoogleRequestDTO.builder()
                        .idToken("new-google-token")
                        .build();

        com.video_streaming.project_video.DTOs.AccountSecurityDTO updated =
                com.video_streaming.project_video.DTOs.AccountSecurityDTO.builder().hasGoogleProvider(true).build();
        when(firebaseAuthService.linkGoogleProvider("user-test-uid", "new-google-token")).thenReturn(updated);

        ResponseEntity<com.video_streaming.project_video.DTOs.AccountActionResponseDTO> response =
                userController.linkGoogleProvider(request, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(firebaseAuthService).linkGoogleProvider("user-test-uid", "new-google-token");
    }

    @Test
    void testVerifyEmail_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-test-uid", null, Collections.emptyList())
        );

        when(firebaseAuthService.generateEmailVerificationLink("user-test-uid")).thenReturn("https://verification.link");

        ResponseEntity<com.video_streaming.project_video.DTOs.AccountActionResponseDTO> response =
                userController.verifyEmail();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("https://verification.link", response.getBody().getActionLink());
    }
}

