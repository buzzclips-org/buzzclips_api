package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.DTOs.*;
import com.video_streaming.project_video.Service.FirebaseAuthService;
import com.video_streaming.project_video.Service.UserService;
import com.video_streaming.project_video.Utils.SecurityUtils;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);
    private final UserService userService;
    private final FirebaseAuthService firebaseAuthService;

    /**
     * Handles JSON updates: application/json
     */
    @RequestMapping(
            value = {"/update", "", "/updateProfile", "/{id}", "/update/{id}"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH},
            consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> updateUserJson(
            @RequestBody(required = false) UserDTO bodyDTO,
            @PathVariable(value = "id", required = false) String pathId,
            @RequestParam(value = "userId", required = false) String queryUserId
    ) {
        return processUpdateUser(bodyDTO, pathId, queryUserId);
    }

    /**
     * Handles multipart/form-data and form-urlencoded updates (e.g., FormData sent from browser)
     */
    @RequestMapping(
            value = {"/update", "", "/updateProfile", "/{id}", "/update/{id}"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH},
            consumes = {org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE, org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE}
    )
    public ResponseEntity<String> updateUserFormData(
            @ModelAttribute UserDTO formDTO,
            @PathVariable(value = "id", required = false) String pathId,
            @RequestParam(value = "userId", required = false) String queryUserId
    ) {
        return processUpdateUser(formDTO, pathId, queryUserId);
    }

    /**
     * Fallback for requests without explicit Content-Type header
     */
    @RequestMapping(
            value = {"/update", "", "/updateProfile", "/{id}", "/update/{id}"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH}
    )
    public ResponseEntity<String> updateUserFallback(
            @ModelAttribute UserDTO formDTO,
            @PathVariable(value = "id", required = false) String pathId,
            @RequestParam(value = "userId", required = false) String queryUserId
    ) {
        return processUpdateUser(formDTO, pathId, queryUserId);
    }

    private ResponseEntity<String> processUpdateUser(UserDTO dto, String pathId, String queryUserId) {
        try {
            if (dto == null) {
                dto = new UserDTO();
            }

            // 1. Resolve userId from DTO, path variable, query param, or SecurityContext
            String resolvedUserId = dto.getUserId();
            if (resolvedUserId == null || resolvedUserId.isBlank()) {
                if (pathId != null && !pathId.isBlank()) {
                    resolvedUserId = pathId;
                } else if (queryUserId != null && !queryUserId.isBlank()) {
                    resolvedUserId = queryUserId;
                } else {
                    resolvedUserId = com.video_streaming.project_video.Utils.SecurityUtils.getCurrentUserIdOptional().orElse(null);
                }
            }

            if (resolvedUserId == null || resolvedUserId.isBlank()) {
                logger.warn("User ID not found in payload, parameters, or SecurityContext");
                return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .body("Validation error: User ID is required to update profile");
            }
            dto.setUserId(resolvedUserId);

            // 2. Prevent updating another user's profile if authenticated
            var currentUserIdOpt = com.video_streaming.project_video.Utils.SecurityUtils.getCurrentUserIdOptional();
            if (currentUserIdOpt.isPresent()) {
                String authenticatedUid = currentUserIdOpt.get();
                if (!authenticatedUid.equalsIgnoreCase(resolvedUserId)) {
                    try {
                        UserDTO target = userService.getUserById(resolvedUserId);
                        if (target != null && !authenticatedUid.equalsIgnoreCase(target.getUserId())) {
                            logger.warn("Caller {} attempted to update profile of {}", authenticatedUid, resolvedUserId);
                            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                                    .body("Forbidden: Cannot update another user's profile");
                        }
                    } catch (Exception ignored) {
                        return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                                .body("Forbidden: Cannot update another user's profile");
                    }
                }
            }

            String response = userService.updateUserProfile(dto);
            logger.info("Updated profile for user: {}", dto.getUserId());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Validation error updating user: {}", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body("Validation error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error updating user: {}", e.getMessage(), e);
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body("Error updating user: " + e.getMessage());
        }
    }

    @GetMapping("/getUser/{id}")
    public ResponseEntity<?> getUserById(@PathVariable("id") String userId) {
        try {
            UserDTO response = userService.getUserById(userId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    @GetMapping("/search")
    public ResponseEntity<java.util.List<UserDTO>> searchUsers(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(userService.searchUsers(query, limit));
    }

    // ==========================================
    // ACCOUNT & FIREBASE PROVIDER MANAGEMENT API
    // ==========================================

    /**
     * Retrieves the current user's account security details, linked providers (Google, Password),
     * email verification status, and unlinking eligibility.
     */
    @GetMapping(value = {"/account", "/account/providers", "/account/security"})
    public ResponseEntity<?> getAccountSecurity() {
        try {
            String currentUid = SecurityUtils.getCurrentUserId();
            AccountSecurityDTO accountSecurity = firebaseAuthService.getAccountSecurity(currentUid);
            return ResponseEntity.ok(accountSecurity);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AccountActionResponseDTO.failure("Authentication required"));
        } catch (Exception e) {
            logger.error("Error retrieving account security profile: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountActionResponseDTO.failure("Failed to fetch account profile: " + e.getMessage()));
        }
    }

    /**
     * Updates the primary account email in both Firebase Auth and PostgreSQL.
     * Triggers verification link generation and collision prevention.
     */
    @RequestMapping(
            value = {"/account/email"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH}
    )
    public ResponseEntity<AccountActionResponseDTO> updateAccountEmail(
            @RequestBody(required = false) UpdateEmailRequestDTO bodyDTO,
            @RequestParam(value = "email", required = false) String paramEmail,
            @RequestParam(value = "newEmail", required = false) String paramNewEmail
    ) {
        try {
            String currentUid = SecurityUtils.getCurrentUserId();
            String email = (bodyDTO != null && bodyDTO.getNewEmail() != null && !bodyDTO.getNewEmail().isBlank())
                    ? bodyDTO.getNewEmail()
                    : ((paramEmail != null && !paramEmail.isBlank()) ? paramEmail : paramNewEmail);

            if (email == null || email.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AccountActionResponseDTO.failure("New email address is required"));
            }

            String verificationLink = firebaseAuthService.updateAccountEmail(currentUid, email);
            AccountSecurityDTO updatedAccount = firebaseAuthService.getAccountSecurity(currentUid);

            return ResponseEntity.ok(AccountActionResponseDTO.success(
                    "Account email updated successfully", verificationLink, updatedAccount));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AccountActionResponseDTO.failure("Authentication required"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AccountActionResponseDTO.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating account email: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountActionResponseDTO.failure("Failed to update email: " + e.getMessage()));
        }
    }

    /**
     * Sets or updates the password for the account (enables password login for Google users).
     */
    @PostMapping("/account/password")
    public ResponseEntity<AccountActionResponseDTO> setAccountPassword(
            @RequestBody(required = false) SetPasswordRequestDTO bodyDTO,
            @RequestParam(value = "password", required = false) String paramPassword,
            @RequestParam(value = "newPassword", required = false) String paramNewPassword
    ) {
        try {
            String currentUid = SecurityUtils.getCurrentUserId();
            String password = (bodyDTO != null && bodyDTO.getNewPassword() != null && !bodyDTO.getNewPassword().isBlank())
                    ? bodyDTO.getNewPassword()
                    : ((paramPassword != null && !paramPassword.isBlank()) ? paramPassword : paramNewPassword);

            if (password == null || password.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AccountActionResponseDTO.failure("New password is required"));
            }

            firebaseAuthService.setAccountPassword(currentUid, password);
            AccountSecurityDTO updatedAccount = firebaseAuthService.getAccountSecurity(currentUid);

            return ResponseEntity.ok(AccountActionResponseDTO.success("Password configured successfully", updatedAccount));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AccountActionResponseDTO.failure("Authentication required"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AccountActionResponseDTO.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating account password: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountActionResponseDTO.failure("Failed to update password: " + e.getMessage()));
        }
    }

    /**
     * Unlinks the Google provider from the account.
     * Prevents unlinking if Google is the user's sole sign-in method to avoid lockout.
     */
    @PostMapping(value = {"/account/providers/google/unlink", "/account/unlink/google"})
    public ResponseEntity<AccountActionResponseDTO> unlinkGoogleProvider() {
        try {
            String currentUid = SecurityUtils.getCurrentUserId();
            AccountSecurityDTO updatedAccount = firebaseAuthService.unlinkProvider(currentUid, "google.com");
            return ResponseEntity.ok(AccountActionResponseDTO.success("Google provider unlinked successfully", updatedAccount));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AccountActionResponseDTO.failure("Authentication required"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AccountActionResponseDTO.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error unlinking Google provider: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountActionResponseDTO.failure("Failed to unlink Google: " + e.getMessage()));
        }
    }

    /**
     * Unlinks the password provider from the account.
     * Prevents unlinking if password is the sole sign-in method.
     */
    @PostMapping(value = {"/account/providers/password/unlink", "/account/unlink/password"})
    public ResponseEntity<AccountActionResponseDTO> unlinkPasswordProvider() {
        try {
            String currentUid = SecurityUtils.getCurrentUserId();
            AccountSecurityDTO updatedAccount = firebaseAuthService.unlinkProvider(currentUid, "password");
            return ResponseEntity.ok(AccountActionResponseDTO.success("Password provider unlinked successfully", updatedAccount));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AccountActionResponseDTO.failure("Authentication required"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AccountActionResponseDTO.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error unlinking password provider: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountActionResponseDTO.failure("Failed to unlink password: " + e.getMessage()));
        }
    }

    /**
     * Links Google provider to an existing account.
     */
    @PostMapping(value = {"/account/providers/google/link", "/account/link/google"})
    public ResponseEntity<AccountActionResponseDTO> linkGoogleProvider(
            @RequestBody(required = false) LinkGoogleRequestDTO bodyDTO,
            @RequestParam(value = "idToken", required = false) String paramIdToken,
            @RequestParam(value = "token", required = false) String paramToken
    ) {
        try {
            String currentUid = SecurityUtils.getCurrentUserId();
            String idToken = (bodyDTO != null && bodyDTO.getIdToken() != null && !bodyDTO.getIdToken().isBlank())
                    ? bodyDTO.getIdToken()
                    : ((paramIdToken != null && !paramIdToken.isBlank()) ? paramIdToken : paramToken);

            if (idToken == null || idToken.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AccountActionResponseDTO.failure("Firebase ID token from Google sign-in is required"));
            }

            AccountSecurityDTO updatedAccount = firebaseAuthService.linkGoogleProvider(currentUid, idToken);
            return ResponseEntity.ok(AccountActionResponseDTO.success("Google provider linked successfully", updatedAccount));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AccountActionResponseDTO.failure("Authentication required"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(AccountActionResponseDTO.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error linking Google provider: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountActionResponseDTO.failure("Failed to link Google: " + e.getMessage()));
        }
    }

    /**
     * Generates and returns an email verification link for the user's account email.
     */
    @PostMapping("/account/verify-email")
    public ResponseEntity<AccountActionResponseDTO> verifyEmail() {
        try {
            String currentUid = SecurityUtils.getCurrentUserId();
            String verificationLink = firebaseAuthService.generateEmailVerificationLink(currentUid);
            AccountSecurityDTO account = firebaseAuthService.getAccountSecurity(currentUid);
            return ResponseEntity.ok(AccountActionResponseDTO.success("Verification link generated successfully", verificationLink, account));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AccountActionResponseDTO.failure("Authentication required"));
        } catch (Exception e) {
            logger.error("Error generating verification link: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AccountActionResponseDTO.failure("Failed to generate verification link: " + e.getMessage()));
        }
    }
}

