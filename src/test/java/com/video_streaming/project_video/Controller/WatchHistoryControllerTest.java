package com.video_streaming.project_video.Controller;

import com.video_streaming.project_video.DTOs.WatchProgressDTO;
import com.video_streaming.project_video.Service.WatchHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WatchHistoryControllerTest {

    @Mock
    private WatchHistoryService watchHistoryService;

    @InjectMocks
    private WatchHistoryController watchHistoryController;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateUser(String uid) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(uid, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("recordProgress: authenticated with query params")
    void testRecordProgress_queryParams() {
        authenticateUser("user-123");
        WatchProgressDTO mockDto = WatchProgressDTO.builder().videoId(10L).progressSeconds(45.0).durationSeconds(120.0).build();
        when(watchHistoryService.recordProgress(10L, "user-123", 45.0, 120.0)).thenReturn(mockDto);

        ResponseEntity<WatchProgressDTO> response = watchHistoryController.recordProgress(10L, 45.0, 120.0, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(45.0, response.getBody().getProgressSeconds());
        verify(watchHistoryService).recordProgress(10L, "user-123", 45.0, 120.0);
    }

    @Test
    @DisplayName("recordProgress: authenticated with json body")
    void testRecordProgress_jsonBody() {
        authenticateUser("user-123");
        WatchProgressDTO mockDto = WatchProgressDTO.builder().videoId(10L).progressSeconds(60.0).durationSeconds(180.0).build();
        when(watchHistoryService.recordProgress(10L, "user-123", 60.0, 180.0)).thenReturn(mockDto);

        Map<String, Object> body = Map.of("seconds", 60.0, "duration", 180.0);
        ResponseEntity<WatchProgressDTO> response = watchHistoryController.recordProgress(10L, null, null, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(60.0, response.getBody().getProgressSeconds());
        verify(watchHistoryService).recordProgress(10L, "user-123", 60.0, 180.0);
    }

    @Test
    @DisplayName("recordProgress: unauthenticated caller returns noop 200 without throwing")
    void testRecordProgress_unauthenticated() {
        // No authentication in SecurityContext
        ResponseEntity<WatchProgressDTO> response = watchHistoryController.recordProgress(10L, 25.0, 100.0, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10L, response.getBody().getVideoId());
        assertEquals(25.0, response.getBody().getProgressSeconds());
        verifyNoInteractions(watchHistoryService);
    }

    @Test
    @DisplayName("getProgress: authenticated and unauthenticated")
    void testGetProgress() {
        WatchProgressDTO mockDto = WatchProgressDTO.builder().videoId(10L).progressSeconds(30.0).build();
        when(watchHistoryService.getProgress(10L, null)).thenReturn(mockDto);

        ResponseEntity<WatchProgressDTO> response = watchHistoryController.getProgress(10L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(30.0, response.getBody().getProgressSeconds());
        verify(watchHistoryService).getProgress(10L, null);
    }

    @Test
    @DisplayName("getUserHistory: retrieves page of history")
    void testGetUserHistory() {
        authenticateUser("user-123");
        WatchProgressDTO item = WatchProgressDTO.builder().videoId(10L).build();
        when(watchHistoryService.getUserHistory(eq("user-123"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item)));

        ResponseEntity<Page<WatchProgressDTO>> response = watchHistoryController.getUserHistory(0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getTotalElements());
    }

    @Test
    @DisplayName("deleteHistoryItem and clearAllHistory")
    void testDeleteAndClearHistory() {
        authenticateUser("user-123");

        ResponseEntity<Map<String, String>> delResp = watchHistoryController.deleteHistoryItem(10L);
        assertEquals(HttpStatus.OK, delResp.getStatusCode());
        verify(watchHistoryService).deleteHistoryItem(10L, "user-123");

        ResponseEntity<Map<String, String>> clearResp = watchHistoryController.clearAllHistory();
        assertEquals(HttpStatus.OK, clearResp.getStatusCode());
        verify(watchHistoryService).clearAllHistory("user-123");
    }
}
