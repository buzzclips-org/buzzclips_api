package com.video_streaming.project_video.Enums;

import java.util.List;

public interface SupportVariables {
    List<String> WHITELISTED_PATHS = List.of(
            "/api/health",
            "/api/app/**",
            "/api/user/getUser/**",
            "/api/vid/view/**",
            "/api/vid/slug/**",
            "/api/vid/search",
            "/api/vid/all",
            "/api/vid/user/**",
            "/api/vid/*/view",
            "/api/vid/*/engagement",
            "/api/vid/*/comments/**",
            "/api/vid/trending",
            "/api/vid/search/suggest",
            "/api/vid/*/related",
            "/api/vid/*/status",
            "/api/vid/*/progress",
            "/api/vid/*/transcode-progress",
            "/api/channel/*/status",
            "/api/channel/*/subscribers",
            "/api/playlists/*",
            "/api/creator/*/analytics",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/webjars/**"
            );

    List<String> ALLOWED_ORIGINS = List.of(
            "https://buzzclips.app",
            "http://buzzclips.app",
            "https://www.buzzclips.app",
            "https://bideo.rajit.cc",
            "http://bideo.rajit.cc",
            "https://www.bideo.rajit.cc"
            // Discarded domains
            //"https://bideo.tech",
            //"https://www.bideo.tech"
            // Uncomment the line below for development mode
            // ,"http://localhost:3000"
            );

    String refreshTokenURLSuffix = "https://securetoken.googleapis.com/v1/token?key=";

    String signInTokenURLSuffix = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=";

    String resetUserURLSuffix = "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=";

    String thumbnailURLDefault = "https://github.com/shadcn.png";

    String processedVideosFolderPath = "processed_videos";

    int cacheTTLMinutesVideo = 3;

    int cacheTTLMinutesViews = 130;

    String DEFAULT_FIREBASE_PROJECT_ID = "project-video-70341";

    String DEFAULT_FIREBASE_AUTH_DOMAIN = "project-video-70341.firebaseapp.com";
}
