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
public class AccountSecurityDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String userId;
    private String email;
    private boolean emailVerified;
    private String username;
    private String fullName;
    private String avatarUrl;

    /**
     * Detailed list of linked authentication providers (Google, Password, etc.).
     */
    private List<ProviderInfoDTO> providers;

    /**
     * Whether Google sign-in is currently linked to this account.
     */
    private boolean hasGoogleProvider;

    /**
     * Whether Email/Password sign-in is currently linked to this account.
     */
    private boolean hasPasswordProvider;

    /**
     * Whether the Google provider can be safely unlinked (requires at least 1 other login method).
     */
    private boolean canUnlinkGoogle;

    /**
     * Whether password provider can be safely unlinked (requires Google or other provider).
     */
    private boolean canUnlinkPassword;

    /**
     * Account creation timestamp in epoch milliseconds.
     */
    private Long creationTimestamp;

    /**
     * Last sign-in timestamp in epoch milliseconds.
     */
    private Long lastSignInTimestamp;
}
