package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.DTOs.CreatorAnalyticsDTO;
import com.video_streaming.project_video.Service.CreatorStudioService;
import com.video_streaming.project_video.Utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/creator")
public class CreatorStudioController {

    private final CreatorStudioService creatorStudioService;

    @GetMapping("/analytics")
    public ResponseEntity<CreatorAnalyticsDTO> getMyAnalytics() {
        String userId = SecurityUtils.getCurrentUserId();
        CreatorAnalyticsDTO analytics = creatorStudioService.getCreatorAnalytics(userId);
        return ResponseEntity.ok(analytics);
    }

    @GetMapping("/{creatorId}/analytics")
    public ResponseEntity<CreatorAnalyticsDTO> getCreatorAnalytics(@PathVariable String creatorId) {
        String currentUid = SecurityUtils.getCurrentUserId();
        if (creatorId == null || !currentUid.equalsIgnoreCase(creatorId.trim())) {
            throw new org.springframework.security.access.AccessDeniedException("Unauthorized: Cannot view another creator's analytics");
        }
        CreatorAnalyticsDTO analytics = creatorStudioService.getCreatorAnalytics(creatorId);
        return ResponseEntity.ok(analytics);
    }
}
