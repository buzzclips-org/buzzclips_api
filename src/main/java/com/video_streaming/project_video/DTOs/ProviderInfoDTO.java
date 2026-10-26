package com.video_streaming.project_video.DTOs;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProviderInfoDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Provider identifier, e.g. "google.com", "password", "phone", "github.com".
     */
    private String providerId;

    /**
     * Unique identifier for this provider.
     */
    private String uid;

    /**
     * Associated email from this provider.
     */
    private String email;

    /**
     * Display name from this provider.
     */
    private String displayName;

    /**
     * Photo / avatar URL from this provider.
     */
    private String photoUrl;
}
