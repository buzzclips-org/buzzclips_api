package com.video_streaming.project_video.DTOs;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

@Getter
@AllArgsConstructor
public class GoogleUserSyncResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UserDTO user;
    private final boolean isNewUser;
}
