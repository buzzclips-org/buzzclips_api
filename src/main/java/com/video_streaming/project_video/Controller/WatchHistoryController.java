package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.DTOs.WatchProgressDTO;
import com.video_streaming.project_video.Service.WatchHistoryService;
import com.video_streaming.project_video.Utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WatchHistoryController {

    private final WatchHistoryService watchHistoryService;

    @PostMapping("/api/vid/{videoId}/progress")
    public ResponseEntity<WatchProgressDTO> recordProgress(
            @PathVariable Long videoId,
            @RequestParam(value = "seconds", required = false) Double secondsParam,
            @RequestParam(value = "duration", required = false) Double durationParam,
            @RequestBody(required = false) Map<String, Object> body) {
        Double seconds = secondsParam;
        Double duration = durationParam;
        if (body != null) {
            if (seconds == null && body.containsKey("seconds")) {
                Object secObj = body.get("seconds");
                if (secObj instanceof Number) {
                    seconds = ((Number) secObj).doubleValue();
                } else if (secObj != null) {
                    try {
                        seconds = Double.parseDouble(secObj.toString());
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (duration == null && body.containsKey("duration")) {
                Object durObj = body.get("duration");
                if (durObj instanceof Number) {
                    duration = ((Number) durObj).doubleValue();
                } else if (durObj != null) {
                    try {
                        duration = Double.parseDouble(durObj.toString());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (seconds == null) {
            seconds = 0.0;
        }

        String userId = SecurityUtils.getCurrentUserIdOptional().orElse(null);
        if (userId == null) {
            return ResponseEntity.ok(WatchProgressDTO.builder()
                    .videoId(videoId)
                    .progressSeconds(seconds)
                    .durationSeconds(duration != null ? duration : 0.0)
                    .build());
        }

        WatchProgressDTO progress = watchHistoryService.recordProgress(videoId, userId, seconds, duration);
        return ResponseEntity.ok(progress);
    }

    @GetMapping("/api/vid/{videoId}/progress")
    public ResponseEntity<WatchProgressDTO> getProgress(@PathVariable Long videoId) {
        String userId = SecurityUtils.getCurrentUserIdOptional().orElse(null);
        WatchProgressDTO progress = watchHistoryService.getProgress(videoId, userId);
        return ResponseEntity.ok(progress);
    }

    @GetMapping("/api/user/history")
    public ResponseEntity<Page<WatchProgressDTO>> getUserHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(page, size);
        Page<WatchProgressDTO> history = watchHistoryService.getUserHistory(userId, pageable);
        return ResponseEntity.ok(history);
    }

    @DeleteMapping("/api/user/history/{videoId}")
    public ResponseEntity<Map<String, String>> deleteHistoryItem(@PathVariable Long videoId) {
        String userId = SecurityUtils.getCurrentUserId();
        watchHistoryService.deleteHistoryItem(videoId, userId);
        return ResponseEntity.ok(Map.of("message", "History item removed", "videoId", String.valueOf(videoId)));
    }

    @DeleteMapping("/api/user/history")
    public ResponseEntity<Map<String, String>> clearAllHistory() {
        String userId = SecurityUtils.getCurrentUserId();
        watchHistoryService.clearAllHistory(userId);
        return ResponseEntity.ok(Map.of("message", "Watch history cleared successfully"));
    }
}
