package com.video_streaming.project_video.Service;

import com.video_streaming.project_video.DTOs.UserDTO;

import java.util.List;

public interface UserService {
    
    /**
     * Legacy method to create a new user with user_name as both handle and display name.
     */
    void create(String emailId, String password, String user_name, String avatar_url) throws Exception;

    /**
     * Production-grade user creation with distinct username (handle) and fullName (display name).
     *
     * @param emailId The email ID of the user.
     * @param password The password for the user.
     * @param username The unique handle for the user (e.g., "johndoe").
     * @param fullName The display / real name of the user (e.g., "John Doe").
     * @param avatar_url The avatar URL for the user.
     * @throws Exception If validation or Firebase creation fails.
     */
    default void create(String emailId, String password, String username, String fullName, String avatar_url) throws Exception {
        create(emailId, password, username, avatar_url);
    }

    /**
     * This method is used to update the user profile with the provided UserDTO.
     * 
     * @param userDTO The UserDTO containing user details to be updated.
     * @return A string indicating the result of the update operation.
     */
    String updateUserProfile(UserDTO userDTO);

    /**
     * This method retrieves a user by their user ID or username handle.
     * 
     * @param userId The ID or username of the user to retrieve.
     * @return UserDTO containing user details.
     */
    UserDTO getUserById(String userId);

    /**
     * Resolves the user's email from their identifier (which may be a username, @handle, or email).
     *
     * @param identifier The username, @handle, or email entered by the user.
     * @return The resolved email address.
     */
    String resolveEmailFromIdentifier(String identifier);

    /**
     * Checks if a given username is available (not already taken and valid).
     *
     * @param username The username to check.
     * @return true if available, false if already taken or invalid.
     */
    boolean isUsernameAvailable(String username);

    default boolean isUsernameValid(String username) {
        return username != null && !username.isBlank();
    }

    /**
     * Searches users / creators by handle or full display name.
     *
     * @param query Search query text.
     * @param limit Max results to return.
     * @return List of matching UserDTOs.
     */
    default List<UserDTO> searchUsers(String query, int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * Provisions or synchronizes a user profile authenticated via Google / Firebase.
     * If the user already exists, updates any missing fields. If new, derives a unique, clean handle.
     *
     * @param uid The Firebase UID.
     * @param email The user's email.
     * @param displayName Display name from Google.
     * @param photoUrl Avatar URL from Google.
     * @param preferredUsername Optional preferred custom handle.
     * @param preferredFullName Optional preferred custom display name.
     * @return GoogleUserSyncResult containing UserDTO and boolean isNewUser.
     */
    default com.video_streaming.project_video.DTOs.GoogleUserSyncResult provisionOrSyncGoogleUser(
            String uid,
            String email,
            String displayName,
            String photoUrl,
            String preferredUsername,
            String preferredFullName
    ) {
        throw new UnsupportedOperationException("Not implemented");
    }
}

