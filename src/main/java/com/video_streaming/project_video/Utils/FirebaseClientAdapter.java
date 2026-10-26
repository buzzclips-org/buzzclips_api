package com.video_streaming.project_video.Utils;

import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;

import java.util.List;

/**
 * Adapter interface abstracting Firebase Authentication SDK operations.
 * Enables seamless unit testing without bytecode mocking constraints on final classes.
 */
public interface FirebaseClientAdapter {

    /**
     * Verifies the client-provided Firebase ID token and extracts claims.
     */
    FirebaseTokenClaims verifyIdTokenClaims(String idToken) throws FirebaseAuthException;

    /**
     * Directly verifies and returns the native FirebaseToken.
     */
    FirebaseToken verifyIdToken(String idToken) throws FirebaseAuthException;

    /**
     * Retrieves the authoritative user record from Firebase Authentication.
     */
    UserRecord getUser(String uid) throws FirebaseAuthException;

    /**
     * Updates user attributes, password, email, or providers in Firebase Authentication.
     */
    void updateUser(UserRecord.UpdateRequest request) throws FirebaseAuthException;

    /**
     * Generates an email verification link for the specified email.
     */
    String generateEmailVerificationLink(String email) throws FirebaseAuthException;

    /**
     * Extracts linked provider IDs for a user.
     */
    List<String> getProviderIds(String uid) throws FirebaseAuthException;
}
