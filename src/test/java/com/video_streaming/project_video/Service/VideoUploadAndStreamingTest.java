package com.video_streaming.project_video.Service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.video_streaming.project_video.DTOMapper.VideoDTOMapper;
import com.video_streaming.project_video.DTOs.CompletePresignedUploadDTO;
import com.video_streaming.project_video.DTOs.PresignedUploadResponseDTO;
import com.video_streaming.project_video.DTOs.UserDTO;
import com.video_streaming.project_video.DTOs.VideoDTO;
import com.video_streaming.project_video.Entity.User;
import com.video_streaming.project_video.Entity.Video;
import com.video_streaming.project_video.Enums.VideoCategory;
import com.video_streaming.project_video.Enums.VideoStatus;
import com.video_streaming.project_video.Enums.VideoVisibility;
import com.video_streaming.project_video.Repository.CommentRepository;
import com.video_streaming.project_video.Repository.PlaylistVideoRepository;
import com.video_streaming.project_video.Repository.WatchHistoryRepository;
import com.video_streaming.project_video.Repository.VideoLikeRepository;
import com.video_streaming.project_video.Repository.VideoRepository;
import com.video_streaming.project_video.ServiceImplementation.VideoServiceImpl;
import com.video_streaming.project_video.Utils.TranscodeProgressManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class VideoUploadAndStreamingTest {

    @Mock
    private AmazonS3 amazonS3;

    @Mock
    private UserService userService;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private PlaylistVideoRepository playlistVideoRepository;

    @Mock
    private WatchHistoryRepository watchHistoryRepository;

    @Mock
    private VideoLikeRepository videoLikeRepository;

    @Mock
    private RedisTemplate<String, VideoDTO> videoRedisTemplate;

    @Mock
    private RedisTemplate<String, Long> viewsRedisTemplate;

    @Mock
    private ViewTrackingService viewTrackingService;

    @Mock
    private EngagementService engagementService;

    @Mock
    private SenderProcessService senderProcessService;

    @Mock
    private ValueOperations<String, VideoDTO> valueOperations;

    private TranscodeProgressManager transcodeProgressManager;
    private VideoServiceImpl videoService;

    @BeforeEach
    void setUp() {
        transcodeProgressManager = new TranscodeProgressManager();
        videoService = new VideoServiceImpl(
                amazonS3,
                userService,
                videoRepository,
                commentRepository,
                playlistVideoRepository,
                watchHistoryRepository,
                videoLikeRepository,
                videoRedisTemplate,
                viewsRedisTemplate,
                viewTrackingService,
                engagementService,
                senderProcessService,
                transcodeProgressManager
        );
        ReflectionTestUtils.setField(videoService, "bucketName", "test-bideo-bucket");
    }

    @Test
    void testGeneratePresignedUploadUrl() throws Exception {
        URL mockUrl = new URL("https://test-bideo-bucket.s3.amazonaws.com/uploads/user-1/mock-presigned-url");
        when(amazonS3.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(mockUrl);
        when(amazonS3.getUrl(eq("test-bideo-bucket"), anyString())).thenReturn(mockUrl);

        PresignedUploadResponseDTO response = videoService.generatePresignedUploadUrl("epic_highlight.mp4", "video/mp4", "user-1");

        assertNotNull(response);
        assertEquals(mockUrl.toString(), response.getUploadUrl());
        assertTrue(response.getS3Key().startsWith("uploads/user-1/"));
        assertTrue(response.getS3Key().contains("epic_highlight.mp4"));
        assertEquals(3600, response.getExpiresInSeconds());
        verify(amazonS3).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }

    @Test
    void testCompletePresignedUpload() throws IOException {
        String s3Key = "uploads/creator-42/video_123.mp4";
        URL s3Url = new URL("https://test-bideo-bucket.s3.amazonaws.com/" + s3Key);
        when(amazonS3.getUrl("test-bideo-bucket", s3Key)).thenReturn(s3Url);

        UserDTO uploader = UserDTO.builder()
                .userId("creator-42")
                .username("awesome_creator")
                .fullName("Awesome Creator")
                .build();
        when(userService.getUserById("creator-42")).thenReturn(uploader);

        Video savedVideo = Video.builder()
                .videoId(101L)
                .video_title("My 4K Vlog")
                .description("Vlog in Tokyo")
                .category(VideoCategory.ENTERTAINMENT)
                .visibility(VideoVisibility.PUBLIC)
                .videoStatus(VideoStatus.PROCESSING)
                .originalVideoPath(s3Url.toString())
                .build();
        when(videoRepository.save(any(Video.class))).thenReturn(savedVideo);

        CompletePresignedUploadDTO request = CompletePresignedUploadDTO.builder()
                .s3Key(s3Key)
                .videoTitle("My 4K Vlog")
                .description("Vlog in Tokyo")
                .tags(List.of("vlog", "travel", "japan"))
                .category(VideoCategory.ENTERTAINMENT)
                .visibility(VideoVisibility.PUBLIC)
                .build();

        Long videoId = videoService.completePresignedUpload(request, "creator-42");

        assertEquals(101L, videoId);
        verify(senderProcessService).sendVideoPath(eq(s3Url.toString()), eq(101L));

        TranscodeProgressManager.TranscodeProgress progress = transcodeProgressManager.getProgress(101L);
        assertNotNull(progress);
        assertEquals("QUEUED", progress.stage());
        assertEquals(5, progress.percent());
    }

    @Test
    void testTranscodeProgressLifecycle() {
        Long videoId = 999L;
        SseEmitter emitter = transcodeProgressManager.subscribe(videoId);
        assertNotNull(emitter);

        transcodeProgressManager.updateProgress(videoId, "PREPARING", 10, "PROCESSING");
        assertEquals("PREPARING", transcodeProgressManager.getProgress(videoId).stage());
        assertEquals(10, transcodeProgressManager.getProgress(videoId).percent());

        transcodeProgressManager.updateProgress(videoId, "ENCODING_ABR_HLS", 50, "PROCESSING");
        assertEquals("ENCODING_ABR_HLS", transcodeProgressManager.getProgress(videoId).stage());
        assertEquals(50, transcodeProgressManager.getProgress(videoId).percent());

        transcodeProgressManager.updateProgress(videoId, "COMPLETED", 100, "COMPLETED");
        assertEquals("COMPLETED", transcodeProgressManager.getProgress(videoId).stage());
        assertEquals(100, transcodeProgressManager.getProgress(videoId).percent());
    }

    @Test
    void testStoryboardAndVttMapping() {
        Video video = Video.builder()
                .videoId(500L)
                .video_title("Action Movie")
                .storyboard_url("https://s3.amazonaws.com/test/storyboard.webp")
                .vtt_url("https://s3.amazonaws.com/test/thumbnails.vtt")
                .build();

        VideoDTOMapper mapper = new VideoDTOMapper();
        VideoDTO dto = mapper.convertEntityToDTO(video);

        assertEquals("https://s3.amazonaws.com/test/storyboard.webp", dto.getStoryboard_url());
        assertEquals("https://s3.amazonaws.com/test/thumbnails.vtt", dto.getVtt_url());

        Video entityAgain = mapper.convertDTOToEntity(dto);
        assertEquals("https://s3.amazonaws.com/test/storyboard.webp", entityAgain.getStoryboard_url());
        assertEquals("https://s3.amazonaws.com/test/thumbnails.vtt", entityAgain.getVtt_url());
    }

    @Test
    void testVideoStatusFallback() {
        Video videoWithM3u8 = new Video();
        videoWithM3u8.setM3u8Url("https://s3.amazonaws.com/bucket/hls/master.m3u8");
        videoWithM3u8.setVideoStatus(null);
        assertEquals(VideoStatus.COMPLETED, videoWithM3u8.getVideoStatus());

        Video videoWithoutM3u8 = new Video();
        videoWithoutM3u8.setM3u8Url(null);
        videoWithoutM3u8.setVideoStatus(null);
        assertEquals(VideoStatus.PENDING, videoWithoutM3u8.getVideoStatus());

        Video videoProcessing = new Video();
        videoProcessing.setVideoStatus(VideoStatus.PROCESSING);
        assertEquals(VideoStatus.PROCESSING, videoProcessing.getVideoStatus());
    }

    @Test
    void testDeleteVideoSuccess() {
        Long videoId = 777L;
        String userId = "owner-uid-123";

        User uploader = User.builder()
                .userId(userId)
                .user_name("creator_owner")
                .build();

        Video video = Video.builder()
                .videoId(videoId)
                .video_title("Video to delete")
                .slug("video-to-delete-777")
                .video_uploader(uploader)
                .originalVideoPath("https://test-bideo-bucket.s3.amazonaws.com/uploads/" + userId + "/orig.mp4")
                .thumbnail_url("https://test-bideo-bucket.s3.amazonaws.com/uploads/" + userId + "/thumb.jpg")
                .build();

        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        boolean result = videoService.deleteVideo(userId, videoId);

        assertTrue(result);
        verify(commentRepository).deleteByVideoId(videoId);
        verify(playlistVideoRepository).deleteByVideoId(videoId);
        verify(watchHistoryRepository).deleteByVideoId(videoId);
        verify(videoLikeRepository).deleteByVideoId(videoId);
        verify(videoRedisTemplate).delete("video:id:" + videoId);
        verify(videoRedisTemplate).delete("video:slug:video-to-delete-777");
        verify(videoRepository).delete(video);
    }

    @Test
    void testDeleteVideoUnauthorized() {
        Long videoId = 777L;
        String ownerId = "owner-uid-123";
        String intruderId = "intruder-uid-456";

        User uploader = User.builder()
                .userId(ownerId)
                .user_name("creator_owner")
                .build();

        Video video = Video.builder()
                .videoId(videoId)
                .video_title("Protected video")
                .video_uploader(uploader)
                .build();

        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        boolean result = videoService.deleteVideo(intruderId, videoId);

        assertFalse(result);
        verify(commentRepository, never()).deleteByVideoId(any());
        verify(playlistVideoRepository, never()).deleteByVideoId(any());
        verify(watchHistoryRepository, never()).deleteByVideoId(any());
        verify(videoLikeRepository, never()).deleteByVideoId(any());
        verify(videoRepository, never()).delete(any(Video.class));
    }

    @Test
    void testDeleteVideoNotFound() {
        Long videoId = 9999L;
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        boolean result = videoService.deleteVideo("user-1", videoId);

        assertFalse(result);
        verify(videoRepository, never()).delete(any(Video.class));
    }
}
