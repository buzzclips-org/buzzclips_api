package com.video_streaming.project_video.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Server-Sent Events (SSE) and live progress updates during upload and multi-rendition ABR transcoding.
 */
@Component
public class TranscodeProgressManager {

    private static final Logger logger = LoggerFactory.getLogger(TranscodeProgressManager.class);

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<Long, TranscodeProgress> progressMap = new ConcurrentHashMap<>();

    // Ephemeral process ID mappings (String processId -> TranscodeProgress / SseEmitter)
    private final Map<String, TranscodeProgress> processProgressMap = new ConcurrentHashMap<>();
    private final Map<String, SseEmitter> processEmitters = new ConcurrentHashMap<>();
    private final Map<String, Long> processToVideoIdMap = new ConcurrentHashMap<>();

    public record TranscodeProgress(
            String processId,
            Long videoId,
            String stage,
            int percent,
            String status,
            String message
    ) {
        public TranscodeProgress(Long videoId, String stage, int percent, String status) {
            this(null, videoId, stage, percent, status, null);
        }
    }

    public void registerProcess(String processId, String stage, int percent, String status, String message) {
        TranscodeProgress progress = new TranscodeProgress(processId, null, stage, percent, status, message);
        processProgressMap.put(processId, progress);
        logger.debug("Registered process {}: {} ({}%)", processId, stage, percent);
    }

    public void linkProcessToVideo(String processId, Long videoId) {
        if (processId != null && videoId != null) {
            processToVideoIdMap.put(processId, videoId);
            TranscodeProgress existing = processProgressMap.get(processId);
            if (existing != null) {
                TranscodeProgress updated = new TranscodeProgress(
                        processId, videoId, existing.stage(), existing.percent(), existing.status(), existing.message()
                );
                processProgressMap.put(processId, updated);
            }
        }
    }

    public SseEmitter subscribe(Long videoId) {
        SseEmitter emitter = new SseEmitter(15 * 60 * 1000L); // 15 min timeout
        emitters.put(videoId, emitter);

        emitter.onCompletion(() -> emitters.remove(videoId));
        emitter.onTimeout(() -> emitters.remove(videoId));
        emitter.onError(e -> emitters.remove(videoId));

        TranscodeProgress current = progressMap.getOrDefault(
                videoId,
                new TranscodeProgress(null, videoId, "QUEUED", 5, "PROCESSING", "Queued for transcoding")
        );

        try {
            emitter.send(SseEmitter.event().name("progress").data(current));
        } catch (IOException e) {
            emitters.remove(videoId);
        }

        return emitter;
    }

    public SseEmitter subscribeProcess(String processId) {
        SseEmitter emitter = new SseEmitter(15 * 60 * 1000L);
        processEmitters.put(processId, emitter);

        emitter.onCompletion(() -> processEmitters.remove(processId));
        emitter.onTimeout(() -> processEmitters.remove(processId));
        emitter.onError(e -> processEmitters.remove(processId));

        TranscodeProgress current = getProgressByProcess(processId);
        try {
            emitter.send(SseEmitter.event().name("progress").data(current));
        } catch (IOException e) {
            processEmitters.remove(processId);
        }

        return emitter;
    }

    public void updateProgress(Long videoId, String stage, int percent, String status) {
        updateProgress(videoId, stage, percent, status, null);
    }

    public void updateProgress(Long videoId, String stage, int percent, String status, String message) {
        TranscodeProgress progress = new TranscodeProgress(null, videoId, stage, percent, status, message);
        progressMap.put(videoId, progress);
        logger.debug("Transcode progress for video {}: {} ({}%) - {}", videoId, stage, percent, status);

        // Notify video subscriber
        SseEmitter emitter = emitters.get(videoId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(progress));
                if ("COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                    emitter.complete();
                    emitters.remove(videoId);
                }
            } catch (Exception e) {
                emitters.remove(videoId);
            }
        }

        // Also notify linked processId subscriber if any
        processToVideoIdMap.forEach((pId, vId) -> {
            if (vId.equals(videoId)) {
                TranscodeProgress pProgress = new TranscodeProgress(pId, videoId, stage, percent, status, message);
                processProgressMap.put(pId, pProgress);
                SseEmitter pEmitter = processEmitters.get(pId);
                if (pEmitter != null) {
                    try {
                        pEmitter.send(SseEmitter.event().name("progress").data(pProgress));
                        if ("COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                            pEmitter.complete();
                            processEmitters.remove(pId);
                        }
                    } catch (Exception ex) {
                        processEmitters.remove(pId);
                    }
                }
            }
        });
    }

    public void updateProcessProgress(String processId, String stage, int percent, String status, String message) {
        Long videoId = processToVideoIdMap.get(processId);
        TranscodeProgress progress = new TranscodeProgress(processId, videoId, stage, percent, status, message);
        processProgressMap.put(processId, progress);

        SseEmitter emitter = processEmitters.get(processId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(progress));
                if ("COMPLETED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status) || "ABORTED".equalsIgnoreCase(status)) {
                    emitter.complete();
                    processEmitters.remove(processId);
                }
            } catch (Exception e) {
                processEmitters.remove(processId);
            }
        }

        if (videoId != null) {
            updateProgress(videoId, stage, percent, status, message);
        }
    }

    public TranscodeProgress getProgress(Long videoId) {
        return progressMap.getOrDefault(videoId, new TranscodeProgress(null, videoId, "UNKNOWN", 0, "PENDING", null));
    }

    public TranscodeProgress getProgressByProcess(String processId) {
        if (processId == null) return new TranscodeProgress(null, null, "UNKNOWN", 0, "PENDING", null);
        Long linkedVideoId = processToVideoIdMap.get(processId);
        if (linkedVideoId != null && progressMap.containsKey(linkedVideoId)) {
            return progressMap.get(linkedVideoId);
        }
        return processProgressMap.getOrDefault(
                processId,
                new TranscodeProgress(processId, null, "UNKNOWN", 0, "PENDING", "Process not found")
        );
    }

    public void remove(Long videoId) {
        if (videoId != null) {
            progressMap.remove(videoId);
            SseEmitter em = emitters.remove(videoId);
            if (em != null) {
                try { em.complete(); } catch (Exception ignored) {}
            }
        }
    }

    public void removeProcess(String processId) {
        if (processId != null) {
            processProgressMap.remove(processId);
            processToVideoIdMap.remove(processId);
            SseEmitter em = processEmitters.remove(processId);
            if (em != null) {
                try { em.complete(); } catch (Exception ignored) {}
            }
        }
    }
}
