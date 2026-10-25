package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.DTOs.CompletePresignedUploadDTO;
import com.video_streaming.project_video.DTOs.PresignedUploadRequestDTO;
import com.video_streaming.project_video.DTOs.PresignedUploadResponseDTO;
import com.video_streaming.project_video.DTOs.VideoDTO;
import com.video_streaming.project_video.DTOs.VideoMetadataUpdateRequest;
import com.video_streaming.project_video.Enums.VideoCategory;
import com.video_streaming.project_video.Enums.VideoVisibility;
import com.video_streaming.project_video.Service.VideoService;
import com.video_streaming.project_video.Utils.SecurityUtils;
import com.video_streaming.project_video.Utils.TranscodeProgressManager;

import lombok.RequiredArgsConstructor;

import com.video_streaming.project_video.Service.SenderProcessService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.apache.tika.Tika;

import com.video_streaming.project_video.Service.ViewTrackingService;
import jakarta.servlet.http.HttpServletRequest;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vid")
public class VideoController {

    private static final Logger logger = LoggerFactory.getLogger(VideoController.class);
    private final VideoService videoService;
    private final SenderProcessService messageSender;
    private final ViewTrackingService viewTrackingService;
    private final TranscodeProgressManager transcodeProgressManager;

    @PostMapping("/upload/presigned")
    public ResponseEntity<PresignedUploadResponseDTO> generatePresignedUploadUrl(
            @RequestBody PresignedUploadRequestDTO request) {
        String userId = SecurityUtils.getCurrentUserId();
        PresignedUploadResponseDTO response = videoService.generatePresignedUploadUrl(
                request != null ? request.getFileName() : null,
                request != null ? request.getContentType() : null,
                userId
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/upload/complete-presigned")
    public ResponseEntity<Map<String, Object>> completePresignedUpload(
            @RequestBody CompletePresignedUploadDTO request) throws IOException {
        String userId = SecurityUtils.getCurrentUserId();
        Long videoId = videoService.completePresignedUpload(request, userId);
        return ResponseEntity.ok(Map.of(
                "videoId", videoId,
                "message", "Presigned upload completed and queued for multi-rendition transcoding"
        ));
    }

    @GetMapping(value = "/{videoId}/transcode-progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getTranscodeProgress(@PathVariable Long videoId) {
        return transcodeProgressManager.subscribe(videoId);
    }

    @GetMapping("/{videoId}/status")
    public ResponseEntity<Map<String, Object>> getVideoStatus(@PathVariable Long videoId) {
        VideoDTO video = videoService.viewVideo(videoId);
        TranscodeProgressManager.TranscodeProgress progress = transcodeProgressManager.getProgress(videoId);
        return ResponseEntity.ok(Map.of(
                "videoId", videoId,
                "videoStatus", video.getVideoStatus() != null ? video.getVideoStatus().name() : "PENDING",
                "stage", progress != null ? progress.stage() : "UNKNOWN",
                "percent", progress != null ? progress.percent() : 0,
                "m3u8Url", video.getM3u8Url() != null ? video.getM3u8Url() : ""
        ));
    }

    @GetMapping(value = "/upload/process/{processId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getProcessProgress(@PathVariable String processId) {
        return transcodeProgressManager.subscribeProcess(processId);
    }

    @GetMapping("/upload/process/{processId}/status")
    public ResponseEntity<Map<String, Object>> getProcessStatus(@PathVariable String processId) {
        TranscodeProgressManager.TranscodeProgress progress = transcodeProgressManager.getProgressByProcess(processId);
        return ResponseEntity.ok(Map.of(
                "processId", processId,
                "videoId", progress.videoId() != null ? progress.videoId() : 0,
                "stage", progress.stage(),
                "percent", progress.percent(),
                "status", progress.status(),
                "message", progress.message() != null ? progress.message() : ""
        ));
    }

    @PostMapping("/upload/process/{processId}/abort")
    public ResponseEntity<Map<String, Object>> abortProcess(@PathVariable String processId) {
        String userId = SecurityUtils.getCurrentUserId();
        boolean aborted = videoService.abortUploadProcess(processId, userId);
        return ResponseEntity.ok(Map.of(
                "processId", processId,
                "aborted", aborted,
                "message", "Upload process aborted successfully"
        ));
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadVidResponseEntity(
            @RequestParam("file") MultipartFile videoFile, 
            @RequestParam String videoTitle, 
            @RequestParam String userId,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false, defaultValue = "OTHER") VideoCategory category,
            @RequestParam(required = false, defaultValue = "PUBLIC") VideoVisibility visibility) {

        if (videoFile.isEmpty()) {
            return ResponseEntity.badRequest().body("File is empty");
        }

        File tempFile = null;
        try {
            Tika tika = new Tika();
            String detectedType = tika.detect(videoFile.getBytes());
            if (!detectedType.startsWith("video/")) {
                return ResponseEntity.badRequest().body("File is not a valid video type");
            }
            
            // Create a temp file in the system's temp directory
            tempFile = File.createTempFile("upload-", "-" + Objects.requireNonNull(videoFile.getOriginalFilename()).
                    substring(0, Math.min(videoFile.getOriginalFilename().length(), 10)));
            videoFile.transferTo(tempFile);

            String result = videoService.uploadFile(tempFile);
            Long videoID = videoService.uploadVideoMetadata(
                    result,
                    videoTitle,
                    description,
                    tags,
                    category,
                    visibility,
                    userId
            );

            transcodeProgressManager.updateProgress(videoID, "QUEUED", 5, "PROCESSING");
            messageSender.sendVideoPath(result, videoID);
            return ResponseEntity.ok(result);

        } catch (IOException e) {
            logger.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to process file: " + e.getMessage());
            
        } finally {
            if (tempFile != null && tempFile.exists()) {
                boolean file_status = tempFile.delete();
                if (!file_status) {
                    logger.error("Temp file not deleted");
                }
            }
        }
    }

    @GetMapping("/view/{videoID}")
    public ResponseEntity<VideoDTO> getVidResponseEntity(@PathVariable Long videoID) {
        VideoDTO videoDTO = videoService.viewVideo(videoID);
        return ResponseEntity.ok(videoDTO);
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<VideoDTO> getVideoBySlug(@PathVariable String slug) {
        VideoDTO videoDTO = videoService.viewVideoBySlug(slug);
        return ResponseEntity.ok(videoDTO);
    }

    @PostMapping("/{videoId}/view")
    public ResponseEntity<Map<String, Object>> recordPlaybackView(
            @PathVariable Long videoId,
            @RequestParam(required = false) String userId,
            HttpServletRequest request) {
        String clientIp = extractClientIp(request);
        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        boolean recorded = viewTrackingService.recordView(videoId, clientIp, userAgent, userId);
        return ResponseEntity.ok(Map.of(
                "recorded", recorded,
                "videoId", videoId
        ));
    }

    private String extractClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    @PutMapping("/{videoId}")
    public ResponseEntity<VideoDTO> updateVideoMetadata(
            @PathVariable Long videoId,
            @RequestParam(required = false) String userId,
            @RequestBody VideoMetadataUpdateRequest updateRequest) {
        String authenticatedUserId = com.video_streaming.project_video.Utils.SecurityUtils.getCurrentUserIdOptional().orElse(userId);
        if (userId != null && !userId.isBlank()) {
            var currentOpt = com.video_streaming.project_video.Utils.SecurityUtils.getCurrentUserIdOptional();
            if (currentOpt.isPresent() && !currentOpt.get().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }
        VideoDTO updatedVideo = videoService.updateVideoMetadata(videoId, authenticatedUserId, updateRequest);
        return ResponseEntity.ok(updatedVideo);
    }

    @GetMapping("/search")
    public ResponseEntity<Page<VideoDTO>> searchVideos(
            @RequestParam(required = false) String query,
            @RequestParam(required = false, name = "category") List<VideoCategory> categories,
            @RequestParam(required = false, name = "tag") List<String> tags,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("videoId").descending());
        Page<VideoDTO> results = videoService.searchVideos(query, categories, tags, pageable);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/all")
    public ResponseEntity<Page<VideoDTO>> getAllVideosList(@RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("videoId").descending());
        Page<VideoDTO> videoList = videoService.getAllVideos(pageable);
        return ResponseEntity.ok(videoList);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<VideoDTO>> getUserUploadedVideos(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("videoId").descending());
        Page<VideoDTO> videos = videoService.getVideosByUserId(userId, pageable);
        return ResponseEntity.ok(videos);
    }

    @RequestMapping(value = "/user/{userId}/{videoId}", method = {RequestMethod.POST, RequestMethod.DELETE})
    public ResponseEntity<String> deleteVideoByUser(@PathVariable String userId, @PathVariable Long videoId) {
        var currentOpt = com.video_streaming.project_video.Utils.SecurityUtils.getCurrentUserIdOptional();
        String effectiveUserId = currentOpt.orElse(userId);

        if (currentOpt.isPresent() && !currentOpt.get().equals(userId)) {
            logger.info("Caller UID {} is attempting to delete video {} with path userId {}", currentOpt.get(), videoId, userId);
        }

        try {
            boolean response = videoService.deleteVideo(effectiveUserId, videoId);
            if (!response && !effectiveUserId.equals(userId)) {
                response = videoService.deleteVideo(userId, videoId);
            }

            if (response) {
                return ResponseEntity.ok("Successfully deleted video");
            } else {
                // If video was already deleted or partially wiped from database, run forceful cleanup
                videoService.forceDeleteVideo(videoId);
                return ResponseEntity.ok("Successfully deleted video");
            }
        } catch (Exception e) {
            logger.error("Error during deleteVideo for video {} and user {}: {}. Attempting fallback force delete.", videoId, userId, e.getMessage(), e);
            try {
                videoService.forceDeleteVideo(videoId);
                return ResponseEntity.ok("Successfully deleted video");
            } catch (Exception ex) {
                logger.error("Fallback force delete also failed for video {}: {}", videoId, ex.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to delete video: " + e.getMessage());
            }
        }
    }

}