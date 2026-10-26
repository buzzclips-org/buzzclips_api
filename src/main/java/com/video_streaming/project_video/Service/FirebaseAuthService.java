package com.video_streaming.project_video.Service;

import org.springframework.http.ResponseEntity;

import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.video_streaming.project_video.Entity.FirebaseRefreshTokenResponse;
import com.video_streaming.project_video.Entity.FirebaseSignInResponse;

public interface FirebaseAuthService {
    
    /**
     * Verifies the Firebase ID Token received from the client-side.
     *
     * @param idToken The Firebase ID Token to verify.
     * @return A FirebaseToken object if the token is valid.
     * @throws FirebaseAuthException If the token is invalid or verification fails.
     */
    FirebaseToken verifyToken(String idToken) throws FirebaseAuthException;

    /**
     * Signs in a user with email and password using Firebase Authentication.
     *
     * @param email The user's email address.
     * @param password The user's password.
     * @return A FirebaseSignInResponse object containing the sign-in result.
     */
    FirebaseSignInResponse signInWithEmailAndPassword(String email, String password);

    /**
     * Signs in a user with an identifier (either username or email) and password.
     * Resolves the identifier to an email if a username is provided.
     *
     * @param identifier The user's username or email.
     * @param password The user's password.
     * @return A FirebaseSignInResponse object containing the sign-in result.
     */
    FirebaseSignInResponse signIn(String identifier, String password);

    /**
     * Refreshes the Firebase ID Token using a refresh token.
     *
     * @param refreshToken The refresh token to use for refreshing the ID token.
     * @return A FirebaseRefreshTokenResponse object containing the new ID token and other details.
     */
    FirebaseRefreshTokenResponse refreshIdToken (String refreshToken);

    /**
     * Reset the Firebase Create User Password.
     *
     * @param email send email id for user you want to reset.
     * @return A ResponseEntity with status of reset password request.
     */
    ResponseEntity<String> sendPasswordResetEmail(String email);

    /**
     * Authenticates a user using Firebase Google Sign-In.
     * Verifies the Firebase ID token, provisions or updates the user profile in PostgreSQL,
     * resolves handle conflicts, and returns a rich session response.
     *
     * @param request The Google login payload with idToken and optional custom handle/name.
     * @return GoogleLoginResponseDTO with tokens, user DTO, provider info, and isNewUser flag.
     */
    default com.video_streaming.project_video.DTOs.GoogleLoginResponseDTO signInWithGoogle(com.video_streaming.project_video.DTOs.GoogleLoginRequestDTO request) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default com.video_streaming.project_video.DTOs.AccountSecurityDTO getAccountSecurity(String userId) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default String updateAccountEmail(String userId, String newEmail) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default void setAccountPassword(String userId, String newPassword) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default com.video_streaming.project_video.DTOs.AccountSecurityDTO unlinkProvider(String userId, String providerId) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default com.video_streaming.project_video.DTOs.AccountSecurityDTO linkGoogleProvider(String userId, String idToken) {
        throw new UnsupportedOperationException("Not implemented");
    }

    default String generateEmailVerificationLink(String userId) {
        throw new UnsupportedOperationException("Not implemented");
    }
}


