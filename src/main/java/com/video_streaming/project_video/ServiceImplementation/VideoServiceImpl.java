package com.video_streaming.project_video.ServiceImplementation;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3URI;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.video_streaming.project_video.DTOMapper.VideoDTOMapper;
import com.video_streaming.project_video.DTOs.CompletePresignedUploadDTO;
import com.video_streaming.project_video.DTOs.PresignedUploadResponseDTO;
import com.video_streaming.project_video.DTOs.UserDTO;
import com.video_streaming.project_video.DTOs.VideoDTO;
import com.video_streaming.project_video.DTOs.VideoMetadataUpdateRequest;
import com.video_streaming.project_video.Entity.Video;
import com.video_streaming.project_video.Enums.VideoCategory;
import com.video_streaming.project_video.Enums.VideoStatus;
import com.video_streaming.project_video.Enums.VideoVisibility;
import com.video_streaming.project_video.Repository.VideoRepository;
import com.video_streaming.project_video.Repository.CommentRepository;
import com.video_streaming.project_video.Repository.PlaylistVideoRepository;
import com.video_streaming.project_video.Repository.WatchHistoryRepository;
import com.video_streaming.project_video.Repository.VideoLikeRepository;
import com.video_streaming.project_video.Service.SenderProcessService;
import com.video_streaming.project_video.Service.UserService;
import com.video_streaming.project_video.Service.VideoService;
import com.video_streaming.project_video.Utils.SeoMetadataBuilder;
import com.video_streaming.project_video.Utils.SlugUtils;
import com.video_streaming.project_video.Utils.TranscodeProgressManager;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.time.Duration;
import java.util.*;

import static com.video_streaming.project_video.Enums.SupportVariables.cacheTTLMinutesVideo;
import static com.video_streaming.project_video.Enums.SupportVariables.cacheTTLMinutesViews;

@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

    private static final Logger logger = LoggerFactory.getLogger(VideoServiceImpl.class);
    private final AmazonS3 amazonS3;
    private final UserService userService;
    private final VideoRepository videoRepository;
    private final CommentRepository commentRepository;
    private final PlaylistVideoRepository playlistVideoRepository;
    private final WatchHistoryRepository watchHistoryRepository;
    private final VideoLikeRepository videoLikeRepository;
    private final RedisTemplate<String, VideoDTO> videoRedisTemplate;
    private final RedisTemplate<String, Long> viewsRedisTemplate;
    private final com.video_streaming.project_video.Service.ViewTrackingService viewTrackingService;
    private final com.video_streaming.project_video.Service.EngagementService engagementService;
    private final SenderProcessService senderProcessService;
    private final TranscodeProgressManager transcodeProgressManager;
    private final JdbcTemplate jdbcTemplate;

    private static final Duration VIDEO_CACHE_TTL = Duration.ofMinutes(cacheTTLMinutesVideo);
    private static final Duration VIEWS_CACHE_TTL = Duration.ofMinutes(cacheTTLMinutesViews);

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Override
    public String uploadFile(File file) {
        if (file == null || !file.exists()) {
            logger.error("File does not exist");
            return "Error: File does not exist - " + (file != null ? file.getAbsolutePath() : "null");
        }

        try {
            PutObjectRequest request = new PutObjectRequest(bucketName, file.getName(), file);
            amazonS3.putObject(request);
            return amazonS3.getUrl(bucketName, file.getName()).toString();
        } catch (AmazonServiceException ase) {
            logger.error("Service error uploading file", ase);
            return "Service error uploading file: " + ase.getMessage();
        } catch (SdkClientException sce) {
            logger.error("Client error uploading file", sce);
            return "Client error uploading file: " + sce.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error uploading file", e);
            return "Unexpected error uploading file: " + e.getMessage();
        }
    }

    @Override
    public PresignedUploadResponseDTO generatePresignedUploadUrl(String fileName, String contentType, String userId) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("File name cannot be empty");
        }
        String cleanFileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String s3Key = "uploads/" + (userId != null && !userId.isBlank() ? userId : "anonymous") + "/" + UUID.randomUUID() + "_" + cleanFileName;
        String processId = "proc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        Date expiration = new Date(System.currentTimeMillis() + 60 * 60 * 1000L); // 1 hour expiration
        GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucketName, s3Key)
                .withMethod(HttpMethod.PUT)
                .withExpiration(expiration);

        if (contentType != null && !contentType.isBlank()) {
            generatePresignedUrlRequest.setContentType(contentType);
        }

        URL presignedUrl = amazonS3.generatePresignedUrl(generatePresignedUrlRequest);
        String publicS3Url = amazonS3.getUrl(bucketName, s3Key).toString();

        transcodeProgressManager.registerProcess(
                processId,
                "READY_FOR_UPLOAD",
                0,
                "PENDING",
                "Presigned upload URL generated; awaiting client direct upload to S3"
        );

        return PresignedUploadResponseDTO.builder()
                .uploadUrl(presignedUrl.toString())
                .s3Key(s3Key)
                .publicS3Url(publicS3Url)
                .processId(processId)
                .expiresInSeconds(3600)
                .build();
    }

    @Override
    @Transactional
    public Long completePresignedUpload(CompletePresignedUploadDTO request, String userId) throws IOException {
        if (request == null || request.getS3Key() == null || request.getS3Key().isBlank()) {
            throw new IllegalArgumentException("S3 key is required to complete presigned upload");
        }

        // Verify that the file actually exists on S3 before registering metadata or queueing transcoding!
        boolean exists = false;
        try {
            exists = amazonS3.doesObjectExist(bucketName, request.getS3Key());
        } catch (Exception e) {
            logger.warn("Could not check S3 object existence for key '{}': {}", request.getS3Key(), e.getMessage());
        }

        if (!exists) {
            logger.error("Upload completion rejected: S3 key '{}' does not exist in bucket '{}'", request.getS3Key(), bucketName);
            throw new IllegalStateException("Video file was not found in S3 (key: " + request.getS3Key() + 
                    "). Please ensure browser upload completes successfully before triggering encoding.");
        }

        String s3Url = amazonS3.getUrl(bucketName, request.getS3Key()).toString();

        Long videoId = uploadVideoMetadata(
                s3Url,
                request.getVideoTitle(),
                request.getDescription(),
                request.getTags(),
                request.getCategory(),
                request.getVisibility(),
                userId
        );

        if (request.getProcessId() != null && !request.getProcessId().isBlank()) {
            transcodeProgressManager.linkProcessToVideo(request.getProcessId(), videoId);
        }

        transcodeProgressManager.updateProgress(videoId, "QUEUED", 5, "PROCESSING");
        senderProcessService.sendVideoPath(s3Url, videoId);
        return videoId;
    }

    @Override
    public String[] uploadDirectory(File directory, String fileNamePrefix) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            logger.error("File does not exist or is not a directory");
            return new String[]{"Error: File is not a directory - ", (directory != null ? directory.getAbsolutePath() : "null"), null, null};
        }
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            throw new RuntimeException("Directory is empty: " + directory.getAbsolutePath());
        }

        String s3FileURL = null;
        String s3ThumbnailURL = null;
        String s3StoryboardURL = null;
        String s3VttURL = null;

        for (File file : files) {
            String filenameString = fileNamePrefix + "/" + file.getName();

            PutObjectRequest request = new PutObjectRequest(bucketName, filenameString, file);
            amazonS3.putObject(request);

            if (file.getName().endsWith(".m3u8")) {
                if ("master.m3u8".equalsIgnoreCase(file.getName()) || s3FileURL == null) {
                    s3FileURL = amazonS3.getUrl(bucketName, filenameString).toString();
                }
            }

            if (file.getName().endsWith(".webp") && !file.getName().toLowerCase().contains("storyboard")) {
                s3ThumbnailURL = amazonS3.getUrl(bucketName, filenameString).toString();
            }

            if (file.getName().toLowerCase().contains("storyboard") && file.getName().endsWith(".webp")) {
                s3StoryboardURL = amazonS3.getUrl(bucketName, filenameString).toString();
            }

            if (file.getName().endsWith(".vtt")) {
                s3VttURL = amazonS3.getUrl(bucketName, filenameString).toString();
            }
        }

        if (s3FileURL == null) {
            throw new RuntimeException(".m3u8 file not found in directory: " + directory.getAbsolutePath());
        }
        return new String[]{ s3FileURL, s3ThumbnailURL, s3StoryboardURL, s3VttURL };
    }

    @Override
    public Page<VideoDTO> getAllVideos(Pageable pageable) {
        Page<Video> videosPage = videoRepository.findByVisibilityAndVideoStatus(
                VideoVisibility.PUBLIC,
                VideoStatus.COMPLETED,
                pageable
        );
        VideoDTOMapper videoDTOMapper = new VideoDTOMapper();
        return videosPage.map(videoDTOMapper::convertEntityToDTO);
    }

    @Override
    @Transactional
    public Long uploadVideoMetadata(String result, String videoTitle, String userId) throws IOException {
        return uploadVideoMetadata(result, videoTitle, null, Collections.emptyList(), VideoCategory.OTHER, VideoVisibility.PUBLIC, userId);
    }

    @Override
    @Transactional
    public Long uploadVideoMetadata(
            String result,
            String videoTitle,
            String description,
            List<String> tags,
            VideoCategory category,
            VideoVisibility visibility,
            String userId
    ) throws IOException {
        Video video = new Video();
        video.setVideo_title(videoTitle);
        video.setDescription(description);
        video.setTags(tags != null ? new ArrayList<>(tags) : new ArrayList<>());
        video.setCategory(category != null ? category : VideoCategory.OTHER);
        video.setVisibility(visibility != null ? visibility : VideoVisibility.PUBLIC);

        // Generate unique SEO slug
        String slug = SlugUtils.generateSlug(videoTitle);
        while (videoRepository.existsBySlug(slug)) {
            slug = SlugUtils.generateSlug(videoTitle);
        }
        video.setSlug(slug);

        video.setVideo_uploadDate(new Date(System.currentTimeMillis()));
        video.setM3u8Url(null);
        video.setVideo_views(0L);
        video.setVideoStatus(VideoStatus.PROCESSING);
        video.setOriginalVideoPath(result);

        if (userId != null && !userId.isBlank()) {
            var userDTO = userService.getUserById(userId);
            if (userDTO != null) {
                com.video_streaming.project_video.DTOMapper.UserDTOMapper userDTOMapper = new com.video_streaming.project_video.DTOMapper.UserDTOMapper();
                video.setVideo_uploader(userDTOMapper.convertDTOToEntity(userDTO));
            }
        }

        // Parse video durations (human-readable and ISO-8601)
        double durationInSeconds = parseDurationInSeconds(result);
        video.setVideo_duration(formatDuration(durationInSeconds));
        video.setIso_duration(SeoMetadataBuilder.toIsoDuration(durationInSeconds));

        Video savedVideo = videoRepository.save(video);
        logger.info("Video metadata uploaded successfully with ID: {} and Slug: {}", savedVideo.getVideoId(), savedVideo.getSlug());
        return savedVideo.getVideoId();
    }

    @Override
    @Transactional
    public void updateVideoEncodedPath(Long videoID, String encodedVideoPath, String s3ThumbnailURL, String s3StoryboardURL, String s3VttURL, boolean flag) {
        Video video = videoRepository.findById(videoID)
                .orElseThrow(() -> new RuntimeException("Video not found with id: " + videoID));
        if (flag) {
            video.setM3u8Url(encodedVideoPath);
            video.setThumbnail_url(s3ThumbnailURL);
            if (s3StoryboardURL != null) video.setStoryboard_url(s3StoryboardURL);
            if (s3VttURL != null) video.setVtt_url(s3VttURL);
            video.setVideoStatus(VideoStatus.COMPLETED);
        } else {
            video.setVideoStatus(VideoStatus.FAILED);
        }
        videoRepository.save(video);
        invalidateVideoCache(video);
    }

    @Override
    @Transactional
    public void updateVideoEncodedPath(Long videoID, String encodedVideoPath, String s3ThumbnailURL, boolean flag) {
        updateVideoEncodedPath(videoID, encodedVideoPath, s3ThumbnailURL, null, null, flag);
    }

    @Override
    @Transactional
    public void updateVideoEncodedPath(Long videoID, String encodedVideoPath, String s3ThumbnailURL) {
        updateVideoEncodedPath(videoID, encodedVideoPath, s3ThumbnailURL, null, null, true);
    }

    @Override
    public VideoDTO viewVideo(Long videoID) {
        String videoKey = "video:id:" + videoID;

        VideoDTO cached = videoRedisTemplate.opsForValue().get(videoKey);
        if (cached != null) {
            cached.setVideo_views(viewTrackingService.getEffectiveViews(cached.getVideoId(), cached.getVideo_views()));
            cached.setLikesCount(engagementService.getLikesCount(cached.getVideoId(), cached.getLikesCount()));
            cached.setDislikesCount(engagementService.getDislikesCount(cached.getVideoId(), cached.getDislikesCount()));
            return cached;
        }

        Video video = videoRepository.findById(videoID)
                .orElseThrow(() -> new RuntimeException("Video not found with id: " + videoID));

        VideoDTOMapper videoDTOMapper = new VideoDTOMapper();
        VideoDTO videoDTO = videoDTOMapper.convertEntityToDTO(video);

        // Cache video metadata by both ID and Slug
        videoRedisTemplate.opsForValue().set(videoKey, videoDTO, VIDEO_CACHE_TTL);
        if (video.getSlug() != null) {
            videoRedisTemplate.opsForValue().set("video:slug:" + video.getSlug(), videoDTO, VIDEO_CACHE_TTL);
        }
        videoDTO.setVideo_views(viewTrackingService.getEffectiveViews(videoDTO.getVideoId(), videoDTO.getVideo_views()));
        videoDTO.setLikesCount(engagementService.getLikesCount(videoDTO.getVideoId(), videoDTO.getLikesCount()));
        videoDTO.setDislikesCount(engagementService.getDislikesCount(videoDTO.getVideoId(), videoDTO.getDislikesCount()));
        return videoDTO;
    }

    @Override
    public VideoDTO viewVideoBySlug(String slug) {
        String slugKey = "video:slug:" + slug;

        VideoDTO cached = videoRedisTemplate.opsForValue().get(slugKey);
        if (cached != null) {
            cached.setVideo_views(viewTrackingService.getEffectiveViews(cached.getVideoId(), cached.getVideo_views()));
            cached.setLikesCount(engagementService.getLikesCount(cached.getVideoId(), cached.getLikesCount()));
            cached.setDislikesCount(engagementService.getDislikesCount(cached.getVideoId(), cached.getDislikesCount()));
            return cached;
        }

        Video video = videoRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Video not found with slug: " + slug));

        VideoDTOMapper videoDTOMapper = new VideoDTOMapper();
        VideoDTO videoDTO = videoDTOMapper.convertEntityToDTO(video);

        videoRedisTemplate.opsForValue().set(slugKey, videoDTO, VIDEO_CACHE_TTL);
        videoRedisTemplate.opsForValue().set("video:id:" + video.getVideoId(), videoDTO, VIDEO_CACHE_TTL);
        videoDTO.setVideo_views(viewTrackingService.getEffectiveViews(videoDTO.getVideoId(), videoDTO.getVideo_views()));
        videoDTO.setLikesCount(engagementService.getLikesCount(videoDTO.getVideoId(), videoDTO.getLikesCount()));
        videoDTO.setDislikesCount(engagementService.getDislikesCount(videoDTO.getVideoId(), videoDTO.getDislikesCount()));
        return videoDTO;
    }

    @Override
    @Transactional
    public VideoDTO updateVideoMetadata(Long videoId, String userId, VideoMetadataUpdateRequest updateRequest) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("Video not found with id: " + videoId));

        if (video.getVideo_uploader() == null || !video.getVideo_uploader().getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized: Only the video creator can update this video");
        }

        video.updateMetadata(updateRequest);

        Video updatedVideo = videoRepository.save(video);
        invalidateVideoCache(updatedVideo);

        VideoDTOMapper mapper = new VideoDTOMapper();
        return mapper.convertEntityToDTO(updatedVideo);
    }

    @Override
    public Page<VideoDTO> searchVideos(String query, List<VideoCategory> categories, List<String> tags, Pageable pageable) {
        String searchQuery = (query != null && !query.trim().isEmpty()) ? query.trim() : null;
        List<String> validCategories = (categories != null)
                ? categories.stream().filter(Objects::nonNull).map(Enum::name).toList()
                : Collections.emptyList();
        List<String> validTags = (tags != null)
                ? tags.stream().filter(t -> t != null && !t.isBlank()).map(t -> t.trim().toLowerCase()).toList()
                : Collections.emptyList();

        Page<Video> page;

        if (searchQuery != null) {
            try {
                // If single category/tag or none, try fast native trigram similarity
                String singleCat = validCategories.size() == 1 ? validCategories.get(0) : null;
                String singleTag = validTags.size() == 1 ? validTags.get(0) : null;
                if (validCategories.size() <= 1 && validTags.size() <= 1) {
                    page = videoRepository.searchVideosFuzzy(searchQuery, singleCat, singleTag, pageable);
                } else {
                    page = searchVideosFallback(searchQuery, categories, validTags, pageable);
                }
            } catch (Exception e) {
                logger.warn("Native fuzzy search failed, falling back to multi-token specification search: {}", e.getMessage());
                page = searchVideosFallback(searchQuery, categories, validTags, pageable);
            }
        } else {
            // When no query keyword is given, browse/filter by category and tag
            page = searchVideosFallback(null, categories, validTags, pageable);
        }

        VideoDTOMapper mapper = new VideoDTOMapper();
        return page.map(mapper::convertEntityToDTO);
    }

    private Page<Video> searchVideosFallback(String query, List<VideoCategory> categories, List<String> tags, Pageable pageable) {
        Specification<Video> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.or(
                    cb.equal(root.get("visibility"), VideoVisibility.PUBLIC),
                    cb.isNull(root.get("visibility"))
            ));

            predicates.add(cb.or(
                    cb.equal(root.get("videoStatus"), VideoStatus.COMPLETED),
                    cb.isNull(root.get("videoStatus"))
            ));

            if (query != null && !query.isBlank()) {
                String[] terms = query.toLowerCase().trim().split("\\s+");
                List<Predicate> termPredicates = new ArrayList<>();
                for (String term : terms) {
                    String pattern = "%" + term + "%";
                    termPredicates.add(cb.like(cb.lower(root.get("video_title")), pattern));
                    termPredicates.add(cb.like(cb.lower(root.get("description")), pattern));
                    termPredicates.add(cb.like(cb.lower(root.get("slug")), pattern));
                }
                predicates.add(cb.or(termPredicates.toArray(new Predicate[0])));
            }

            if (categories != null && !categories.isEmpty()) {
                predicates.add(root.get("category").in(categories));
            }

            if (tags != null && !tags.isEmpty()) {
                Join<Video, String> tagJoin = root.join("tags", JoinType.LEFT);
                predicates.add(cb.lower(tagJoin).in(tags));
            }

            cq.distinct(true);
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return videoRepository.findAll(spec, pageable);
    }

    @Override
    public Page<VideoDTO> getVideosByUserId(String identifier, Pageable pageable) {
        VideoDTOMapper videoDTOMapper = new VideoDTOMapper();
        String resolvedUserId = identifier;
        try {
            UserDTO u = userService.getUserById(identifier);
            if (u != null && u.getUserId() != null) {
                resolvedUserId = u.getUserId();
            }
        } catch (Exception ignored) {
        }
        return videoRepository.findByUploaderId(resolvedUserId, pageable)
                .map(videoDTOMapper::convertEntityToDTO);
    }

    @Override
    @Transactional
    public boolean deleteVideo(String userId, Long videoID) {
        if (videoID == null) {
            return false;
        }

        Optional<Video> videoOpt = videoRepository.findById(videoID);
        if (videoOpt.isEmpty()) {
            // Video might have been partially deleted on a previous attempt (DB record wiped, but orphan rows/cache linger)
            logger.info("Video {} not found in database; executing idempotent cleanup on related tables, cache, and progress", videoID);
            forcePurgeChildRecords(videoID);
            invalidateVideoCacheById(videoID, null);
            transcodeProgressManager.remove(videoID);
            return false;
        }

        Video video = videoOpt.get();
        String resolvedUserId = userId;
        UserDTO callerDTO = null;
        try {
            callerDTO = userService.getUserById(userId);
            if (callerDTO != null && callerDTO.getUserId() != null) {
                resolvedUserId = callerDTO.getUserId();
            }
        } catch (Exception ignored) {
        }

        boolean isAuthorized = false;
        if (callerDTO != null && "ADMIN".equalsIgnoreCase(callerDTO.getUser_role())) {
            isAuthorized = true;
        } else if (video.getVideo_uploader() != null) {
            String uploaderId = video.getVideo_uploader().getUserId();
            String uploaderUsername = video.getVideo_uploader().getUsername();
            if (uploaderId != null && (uploaderId.equals(userId) || uploaderId.equals(resolvedUserId))) {
                isAuthorized = true;
            } else if (uploaderUsername != null && (uploaderUsername.equalsIgnoreCase(userId) || uploaderUsername.equalsIgnoreCase(resolvedUserId))) {
                isAuthorized = true;
            }
        } else if (video.getOriginalVideoPath() != null) {
            if (video.getOriginalVideoPath().contains("/" + userId + "/") ||
                (resolvedUserId != null && video.getOriginalVideoPath().contains("/" + resolvedUserId + "/"))) {
                isAuthorized = true;
            }
        } else {
            // Video has no uploader (e.g. failed/orphaned initial upload) - allow caller to clean it up
            isAuthorized = true;
        }

        if (!isAuthorized) {
            logger.warn("User {} is not authorized to delete video {}", userId, videoID);
            return false;
        }

        // 1. Delete associated child entities via repositories AND direct JDBC to guarantee FK integrity
        try {
            commentRepository.deleteByVideoId(videoID);
        } catch (Exception e) {
            logger.warn("Repository comment deletion notice for video {}: {}", videoID, e.getMessage());
        }
        try {
            playlistVideoRepository.deleteByVideoId(videoID);
        } catch (Exception e) {
            logger.warn("Repository playlist deletion notice for video {}: {}", videoID, e.getMessage());
        }
        try {
            watchHistoryRepository.deleteByVideoId(videoID);
        } catch (Exception e) {
            logger.warn("Repository watch history deletion notice for video {}: {}", videoID, e.getMessage());
        }
        try {
            videoLikeRepository.deleteByVideoId(videoID);
        } catch (Exception e) {
            logger.warn("Repository likes deletion notice for video {}: {}", videoID, e.getMessage());
        }

        // Guarantee all child tables (including tags) are purged via direct JDBC
        forcePurgeChildRecords(videoID);

        // 2. Delete S3 assets safely (stripping query parameters and handling missing objects)
        deleteVideoFromS3(video);

        // 3. Invalidate Redis cache & transcode progress
        invalidateVideoCache(video);
        transcodeProgressManager.remove(videoID);

        // 4. Delete the video row directly via repository and fallback to JDBC if needed
        try {
            videoRepository.delete(video);
        } catch (Exception e) {
            logger.warn("videoRepository.delete threw for video {}: {}. Purging via JDBC directly.", videoID, e.getMessage());
            try {
                jdbcTemplate.update("DELETE FROM vid_metadata WHERE video_id = ?", videoID);
            } catch (Exception ex) {
                logger.error("Failed to delete from vid_metadata for video {}: {}", videoID, ex.getMessage());
                throw ex;
            }
        }

        logger.info("Video {} successfully deleted from database and S3 by user {}", videoID, userId);
        return true;
    }

    @Override
    @Transactional
    public void forceDeleteVideo(Long videoId) {
        if (videoId == null) return;
        logger.info("Executing forceful deletion and cleanup for video {}", videoId);

        // 1. Try deleting S3 assets if video entity exists
        try {
            videoRepository.findById(videoId).ifPresent(this::deleteVideoFromS3);
        } catch (Exception e) {
            logger.warn("Error deleting S3 assets during force delete of video {}: {}", videoId, e.getMessage());
        }

        // 2. Purge child records from all related tables
        forcePurgeChildRecords(videoId);

        // 3. Purge main video record from vid_metadata
        try {
            jdbcTemplate.update("DELETE FROM vid_metadata WHERE video_id = ?", videoId);
        } catch (Exception e) {
            logger.warn("JDBC purge from vid_metadata for video {}: {}", videoId, e.getMessage());
            videoRepository.findById(videoId).ifPresent(videoRepository::delete);
        }

        // 4. Invalidate caches and progress tracking
        invalidateVideoCacheById(videoId, null);
        transcodeProgressManager.remove(videoId);
        logger.info("Force delete completed for video {}", videoId);
    }

    @Override
    public boolean abortUploadProcess(String processId, String userId) {
        if (processId == null || processId.isBlank()) return false;
        logger.info("Aborting upload process {} for user {}", processId, userId);
        transcodeProgressManager.updateProcessProgress(processId, "ABORTED", 0, "ABORTED", "Upload aborted by user");
        transcodeProgressManager.removeProcess(processId);
        return true;
    }

    private void forcePurgeChildRecords(Long videoId) {
        if (videoId == null) return;
        try {
            jdbcTemplate.update("DELETE FROM video_comments WHERE video_id = ?", videoId);
        } catch (Exception e) {
            logger.debug("Notice: video_comments purge for video {}: {}", videoId, e.getMessage());
        }
        try {
            jdbcTemplate.update("DELETE FROM video_likes WHERE video_id = ?", videoId);
        } catch (Exception e) {
            logger.debug("Notice: video_likes purge for video {}: {}", videoId, e.getMessage());
        }
        try {
            jdbcTemplate.update("DELETE FROM watch_history WHERE video_id = ?", videoId);
        } catch (Exception e) {
            logger.debug("Notice: watch_history purge for video {}: {}", videoId, e.getMessage());
        }
        try {
            jdbcTemplate.update("DELETE FROM playlist_videos WHERE video_id = ?", videoId);
        } catch (Exception e) {
            logger.debug("Notice: playlist_videos purge for video {}: {}", videoId, e.getMessage());
        }
        try {
            jdbcTemplate.update("DELETE FROM video_tags WHERE video_id = ?", videoId);
        } catch (Exception e) {
            logger.debug("Notice: video_tags purge for video {}: {}", videoId, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void syncPendingViewsToDatabase() {
        viewTrackingService.flushPendingViewsToDatabase();
    }

    private void invalidateVideoCache(Video video) {
        if (video == null) return;
        invalidateVideoCacheById(video.getVideoId(), video.getSlug());
    }

    private void invalidateVideoCacheById(Long videoId, String slug) {
        if (videoId != null) {
            try {
                videoRedisTemplate.delete("video:id:" + videoId);
                videoRedisTemplate.delete("video:" + videoId); // legacy fallback
                viewsRedisTemplate.delete("video:pending_views:" + videoId);
            } catch (Exception ignored) {}
        }
        if (slug != null) {
            try {
                videoRedisTemplate.delete("video:slug:" + slug);
            } catch (Exception ignored) {}
        }
    }

    private void deleteVideoFromS3(Video video) {
        if (video == null) return;

        // 1. Delete original uploaded video file
        deleteS3UrlSafe(video.getOriginalVideoPath());

        // 2. Delete HLS chunks directory and playlist
        if (video.getM3u8Url() != null && !video.getM3u8Url().isBlank()) {
            try {
                String cleanUrl = stripQueryParams(video.getM3u8Url());
                AmazonS3URI hlsUri = new AmazonS3URI(cleanUrl);
                String key = hlsUri.getKey();
                int lastSlashIndex = key.lastIndexOf('/');
                if (lastSlashIndex > 0) {
                    String prefix = key.substring(0, lastSlashIndex + 1);
                    deleteS3DirectoryByPrefix(hlsUri.getBucket(), prefix);
                } else {
                    amazonS3.deleteObject(hlsUri.getBucket(), key);
                }
            } catch (Exception e) {
                logger.warn("Could not parse HLS folder URI {}: {}. Attempting fallback key deletion.", video.getM3u8Url(), e.getMessage());
                deleteS3UrlSafe(video.getM3u8Url());
            }
        }

        // 3. Delete thumbnail if separate
        deleteS3UrlSafe(video.getThumbnail_url());

        // 4. Delete WebP storyboard spritesheet
        deleteS3UrlSafe(video.getStoryboard_url());

        // 5. Delete WebVTT cue file
        deleteS3UrlSafe(video.getVtt_url());
    }

    private void deleteS3UrlSafe(String s3Url) {
        if (s3Url == null || s3Url.isBlank()) return;
        try {
            String cleanUrl = stripQueryParams(s3Url);
            AmazonS3URI uri = new AmazonS3URI(cleanUrl);
            amazonS3.deleteObject(uri.getBucket(), uri.getKey());
            logger.info("Deleted S3 asset: bucket={}, key={}", uri.getBucket(), uri.getKey());
        } catch (Exception e) {
            try {
                String key = extractKeyFromUrl(s3Url);
                if (key != null) {
                    amazonS3.deleteObject(bucketName, key);
                    logger.info("Deleted S3 asset via fallback key extraction: {}", key);
                }
            } catch (Exception ex) {
                logger.warn("Notice: could not delete S3 asset '{}': {}", s3Url, ex.getMessage());
            }
        }
    }

    private String stripQueryParams(String url) {
        if (url == null) return null;
        int idx = url.indexOf('?');
        return idx != -1 ? url.substring(0, idx) : url;
    }

    private String extractKeyFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String clean = stripQueryParams(url);
        int uploadsIdx = clean.indexOf("uploads/");
        if (uploadsIdx != -1) {
            return clean.substring(uploadsIdx);
        }
        int hlsIdx = clean.indexOf("hls/");
        if (hlsIdx != -1) {
            return clean.substring(hlsIdx);
        }
        return null;
    }

    private void deleteS3DirectoryByPrefix(String bucket, String prefix) {
        ListObjectsV2Request listRequest = new ListObjectsV2Request()
                .withBucketName(bucket)
                .withPrefix(prefix);
        ListObjectsV2Result result;
        do {
            result = amazonS3.listObjectsV2(listRequest);
            for (S3ObjectSummary summary : result.getObjectSummaries()) {
                amazonS3.deleteObject(bucket, summary.getKey());
                logger.debug("Deleted S3 chunk: {}", summary.getKey());
            }
            listRequest.setContinuationToken(result.getNextContinuationToken());
        } while (result.isTruncated());
        logger.info("Completed S3 directory deletion for prefix: {}", prefix);
    }

    private double parseDurationInSeconds(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            return 0.0;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe",
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    inputPath
            );

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String durationStr = reader.readLine();
                if (durationStr != null && !durationStr.isBlank()) {
                    return Double.parseDouble(durationStr.trim());
                }
            }
        } catch (Exception e) {
            logger.warn("Could not parse ffprobe duration for {}: {}", inputPath, e.getMessage());
        }
        return 0.0;
    }

    private String formatDuration(double seconds) {
        int hours = (int) seconds / 3600;
        int minutes = ((int) seconds % 3600) / 60;
        int secs = (int) seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
