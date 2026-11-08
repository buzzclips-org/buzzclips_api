package com.video_streaming.project_video.ServiceImplementation;

import com.video_streaming.project_video.DTOs.WatchProgressDTO;
import com.video_streaming.project_video.DTOMapper.VideoDTOMapper;
import com.video_streaming.project_video.Entity.Video;
import com.video_streaming.project_video.Entity.WatchHistory;
import com.video_streaming.project_video.Repository.VideoRepository;
import com.video_streaming.project_video.Repository.WatchHistoryRepository;
import com.video_streaming.project_video.Service.WatchHistoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WatchHistoryServiceImpl implements WatchHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(WatchHistoryServiceImpl.class);

    private final WatchHistoryRepository watchHistoryRepository;
    private final VideoRepository videoRepository;

    @Override
    @Transactional
    public WatchProgressDTO recordProgress(Long videoId, String userId, Double progressSeconds, Double durationSeconds) {
        if (videoId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Video ID and User ID are required");
        }

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("Video not found with ID: " + videoId));

        double progress = progressSeconds != null ? Math.max(0.0, progressSeconds) : 0.0;
        double duration = durationSeconds != null ? Math.max(0.0, durationSeconds) : 0.0;
        boolean completed = duration > 0.0 && (progress / duration) >= 0.90;

        Optional<WatchHistory> existingOpt = watchHistoryRepository.findByUserIdAndVideo_VideoId(userId, videoId);
        WatchHistory history;

        if (existingOpt.isPresent()) {
            history = existingOpt.get();
            history.setProgressSeconds(progress);
            if (duration > 0.0) {
                history.setDurationSeconds(duration);
            }
            history.setCompleted(completed);
            history.setLastWatchedAt(new Date());
        } else {
            history = WatchHistory.builder()
                    .userId(userId)
                    .video(video)
                    .progressSeconds(progress)
                    .durationSeconds(duration)
                    .completed(completed)
                    .lastWatchedAt(new Date())
                    .build();
        }

        WatchHistory saved = watchHistoryRepository.save(history);
        logger.debug("Updated playback progress for user {} on video {}: {}s (completed={})", userId, videoId, progress, completed);

        return toDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public WatchProgressDTO getProgress(Long videoId, String userId) {
        if (videoId == null || userId == null || userId.isBlank()) {
            return WatchProgressDTO.builder().videoId(videoId).progressSeconds(0.0).build();
        }

        return watchHistoryRepository.findByUserIdAndVideo_VideoId(userId, videoId)
                .map(this::toDTO)
                .orElse(WatchProgressDTO.builder().videoId(videoId).progressSeconds(0.0).build());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WatchProgressDTO> getUserHistory(String userId, Pageable pageable) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required");
        }

        return watchHistoryRepository.findByUserIdOrderByLastWatchedAtDesc(userId, pageable)
                .map(this::toDTO);
    }

    @Override
    @Transactional
    public void deleteHistoryItem(Long videoId, String userId) {
        if (videoId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Video ID and User ID are required");
        }
        watchHistoryRepository.deleteByUserIdAndVideoId(userId, videoId);
        logger.info("Removed video ID {} from watch history for user {}", videoId, userId);
    }

    @Override
    @Transactional
    public void clearAllHistory(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required");
        }
        watchHistoryRepository.deleteAllByUserId(userId);
        logger.info("Cleared all watch history for user {}", userId);
    }

    private WatchProgressDTO toDTO(WatchHistory history) {
        Video video = history.getVideo();
        String channelName = null;
        String channelHandle = null;
        String channelAvatar = null;
        if (video.getVideo_uploader() != null) {
            channelName = video.getVideo_uploader().getFullName();
            channelHandle = video.getVideo_uploader().getUsername();
            channelAvatar = video.getVideo_uploader().getAvatar_url();
        }

        return WatchProgressDTO.builder()
                .videoId(video.getVideoId())
                .videoTitle(video.getVideo_title())
                .slug(video.getSlug())
                .thumbnailUrl(VideoDTOMapper.normalizeS3Url(video.getThumbnail_url()))
                .channelName(channelName)
                .channelHandle(channelHandle)
                .channelAvatar(channelAvatar)
                .progressSeconds(history.getProgressSeconds())
                .durationSeconds(history.getDurationSeconds())
                .completed(history.isCompleted())
                .lastWatchedAt(history.getLastWatchedAt())
                .build();
    }
}
