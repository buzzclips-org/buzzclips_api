package com.video_streaming.project_video.DTOs;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetPasswordRequestDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonAlias({"password", "userPassword"})
    private String newPassword;
}
