package com.video_streaming.project_video.Utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;

@Getter
@Builder
@AllArgsConstructor
public class FirebaseTokenClaims implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String uid;
    private final String email;
    private final String name;
    private final String picture;
    private final boolean emailVerified;
}
