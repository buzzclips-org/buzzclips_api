package com.video_streaming.project_video.Service;

import com.video_streaming.project_video.DTOs.CompletePresignedUploadDTO;
import com.video_streaming.project_video.DTOs.PresignedUploadResponseDTO;
import com.video_streaming.project_video.DTOs.VideoDTO;
import com.video_streaming.project_video.DTOs.VideoMetadataUpdateRequest;
import com.video_streaming.project_video.Enums.VideoCategory;
import com.video_streaming.project_video.Enums.VideoVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface VideoService {

    /**
     * Uploads a video file to the server.
     *
     * @param file The video file to be uploaded.
     * @return A string indicating the result of the upload operation.
     */
    String uploadFile(File file);

    /**
     * Generates an AWS S3 presigned PUT URL allowing clients to upload videos directly to S3,
     * bypassing server multipart size limitations.
     *
     * @param fileName Original file name
     * @param contentType MIME content type (e.g., video/mp4)
     * @param userId Authenticated user ID
     * @return PresignedUploadResponseDTO containing upload URL, key, and public S3 URL
     */
    PresignedUploadResponseDTO generatePresignedUploadUrl(String fileName, String contentType, String userId);

    /**
     * Completes a presigned upload by registering the video metadata and queueing it for transcoding.
     *
     * @param request Metadata and S3 key
     * @param userId Authenticated user ID
     * @return Created video ID
     */
    Long completePresignedUpload(CompletePresignedUploadDTO request, String userId) throws IOException;

    /**
     * Uploads a directory containing video files to the server.
     *
     * @param directory The directory containing video files.
     * @param fileNamePrefix A prefix for the file names.
     * @return Strings for m3u8 file, thumbnail url, storyboard url, and vtt url.
     */
    String[] uploadDirectory(File directory, String fileNamePrefix);

    /**
     * Retrieves a list of all public completed videos available on the server.
     * @param pageable Information required for pagination.
     * @return A page of VideoDTO objects representing the videos.
     */
    Page<VideoDTO> getAllVideos(Pageable pageable);

    /**
     * Uploads metadata for a video with title and user ID.
     *
     * @param result S3 URL of the original video.
     * @param videoTitle The title of the video.
     * @param userId The ID of the uploader.
     * @return Video id of the uploaded video.
     */
    Long uploadVideoMetadata(String result, String videoTitle, String userId) throws IOException;

    /**
     * Uploads metadata for a video with full SEO fields.
     */
    Long uploadVideoMetadata(
            String result,
            String videoTitle,
            String description,
            List<String> tags,
            VideoCategory category,
            VideoVisibility visibility,
            String userId
    ) throws IOException;

    /**
     * Updates the encoded video path, thumbnail, storyboard, and vtt URLs with success flag.
     */
    void updateVideoEncodedPath(Long videoID, String encodedVideoPath, String s3ThumbnailURL, String s3StoryboardURL, String s3VttURL, boolean flag);

    /**
     * Updates the encoded video path for a specific video with success flag.
     *
     * @param videoID The ID of the video.
     * @param encodedVideoPath The new encoded video path to be set.
     * @param s3ThumbnailURL The path for thumbnail of video to be set.
     * @param flag The flag to check if video upload successful or failed.
     */
    default void updateVideoEncodedPath(Long videoID, String encodedVideoPath, String s3ThumbnailURL, boolean flag) {
        updateVideoEncodedPath(videoID, encodedVideoPath, s3ThumbnailURL, null, null, flag);
    }

    /**
     * Updates the encoded video path for a specific video (default flag true).
     */
    default void updateVideoEncodedPath(Long videoID, String encodedVideoPath, String s3ThumbnailURL) {
        updateVideoEncodedPath(videoID, encodedVideoPath, s3ThumbnailURL, null, null, true);
    }

    /**
     * Retrieves the video information from video ID.
     *
     * @param videoID The ID of the video.
     * @return VideoDTO object for specific video ID.
     */
    VideoDTO viewVideo(Long videoID);

    /**
     * Retrieves the video information from SEO slug.
     *
     * @param slug The SEO slug of the video.
     * @return VideoDTO object.
     */
    VideoDTO viewVideoBySlug(String slug);

    /**
     * Updates video metadata (title, description, tags, category, visibility).
     */
    VideoDTO updateVideoMetadata(Long videoId, String userId, VideoMetadataUpdateRequest updateRequest);

    /**
     * Searches videos by keyword, multiple categories, and multiple tags with pagination.
     */
    Page<VideoDTO> searchVideos(String query, List<VideoCategory> categories, List<String> tags, Pageable pageable);

    /**
     * Backward-compatible search for single category and single tag.
     */
    default Page<VideoDTO> searchVideos(String query, VideoCategory category, String tag, Pageable pageable) {
        return searchVideos(
                query,
                category != null ? List.of(category) : null,
                tag != null && !tag.isBlank() ? List.of(tag) : null,
                pageable
        );
    }

    /**
     * Retrieves videos uploaded by current user.
     *
     * @param userId The ID of the uploader.
     * @param pageable The parameters of pagination.
     * @return VideoDTO for all user uploaded videos.
     */
    Page<VideoDTO> getVideosByUserId(String userId, Pageable pageable);

    /**
     * Deletes the video relevant for specific video ID, including S3 cleanup.
     * @param userId The ID of the uploader.
     * @param videoID The ID of the video.
     */
    boolean deleteVideo(String userId, Long videoID);

    /**
     * Forcefully deletes all artifacts (database records, child rows, S3 assets, Redis keys)
     * for a video ID even if partially corrupt, orphaned, or failed during prior deletion.
     * @param videoId The ID of the video to purge.
     */
    void forceDeleteVideo(Long videoId);

    /**
     * Aborts an active upload or transcoding process and cleans up any partial S3 file.
     * @param processId The ephemeral process identifier.
     * @param userId The authenticated user ID.
     */
    boolean abortUploadProcess(String processId, String userId);

    /**
     * Flushes accumulated pending views from Redis to PostgreSQL.
     */
    void syncPendingViewsToDatabase();
}
