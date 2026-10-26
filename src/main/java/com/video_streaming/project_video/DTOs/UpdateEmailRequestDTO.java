package com.video_streaming.project_video.DTOs;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEmailRequestDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonAlias({"email", "user_email", "emailAddress"})
    private String newEmail;
}
