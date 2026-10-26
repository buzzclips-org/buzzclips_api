package com.video_streaming.project_video.DTOs;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GoogleLoginRequestDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonAlias({"token", "credential", "firebaseToken", "id_token"})
    private String idToken;

    @JsonAlias({"user_name", "userName", "handle"})
    private String username;

    @JsonAlias({"full_name", "displayName", "name"})
    private String fullName;
}
