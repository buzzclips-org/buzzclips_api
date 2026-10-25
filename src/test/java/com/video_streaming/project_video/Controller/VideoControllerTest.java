package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.Service.SenderProcessService;
import com.video_streaming.project_video.Service.VideoService;
import com.video_streaming.project_video.Service.ViewTrackingService;
import com.video_streaming.project_video.Utils.TranscodeProgressManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class VideoControllerTest {

    @Mock
    private VideoService videoService;

    @Mock
    private SenderProcessService messageSender;

    @Mock
    private ViewTrackingService viewTrackingService;

    @Mock
    private TranscodeProgressManager transcodeProgressManager;

    @InjectMocks
    private VideoController videoController;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDeleteVideo_Success() {
        when(videoService.deleteVideo("user-1", 100L)).thenReturn(true);

        ResponseEntity<String> response = videoController.deleteVideoByUser("user-1", 100L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Successfully deleted video", response.getBody());
        verify(videoService).deleteVideo("user-1", 100L);
    }

    @Test
    void testDeleteVideo_Failure() {
        when(videoService.deleteVideo("user-1", 100L)).thenReturn(false);

        ResponseEntity<String> response = videoController.deleteVideoByUser("user-1", 100L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Failed to delete video", response.getBody());
        verify(videoService).deleteVideo("user-1", 100L);
    }

    @Test
    void testDeleteVideo_WithAuthenticatedUser() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("auth-user-99", null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(videoService.deleteVideo("auth-user-99", 200L)).thenReturn(true);

        ResponseEntity<String> response = videoController.deleteVideoByUser("auth-user-99", 200L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Successfully deleted video", response.getBody());
        verify(videoService).deleteVideo("auth-user-99", 200L);
    }
}
