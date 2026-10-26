package com.video_streaming.project_video.DTOs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GoogleLoginResponseDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Verified Firebase ID Token.
     */
    private String idToken;

    /**
     * Refresh token if available.
     */
    private String refreshToken;

    /**
     * Indicates whether this user record was just provisioned for the first time.
     */
    private boolean isNewUser;

    /**
     * Primary provider ID, e.g. "google.com".
     */
    private String primaryProvider;

    /**
     * List of all linked providers for this account (e.g. ["google.com", "password"]).
     */
    private List<String> providers;

    /**
     * Whether the account email is verified in Firebase.
     */
    private boolean emailVerified;

    /**
     * Complete user profile details.
     */
    private UserDTO user;
}
