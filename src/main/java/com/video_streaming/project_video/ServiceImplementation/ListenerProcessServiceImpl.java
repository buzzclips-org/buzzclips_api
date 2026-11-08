package com.video_streaming.project_video.ServiceImplementation;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.video_streaming.project_video.Configurations.RabbitMQConfig;
import com.video_streaming.project_video.DTOs.MessageDTO;
import com.video_streaming.project_video.Service.ListenerProcessService;
import com.video_streaming.project_video.Service.VideoService;

import lombok.RequiredArgsConstructor;

import static com.video_streaming.project_video.Enums.SupportVariables.processedVideosFolderPath;

@Service
@RequiredArgsConstructor
public class ListenerProcessServiceImpl implements ListenerProcessService {

    private static final Logger logger = LoggerFactory.getLogger(ListenerProcessServiceImpl.class);
    private final VideoService videoService;
    private final com.video_streaming.project_video.Utils.TranscodeProgressManager transcodeProgressManager;

    @Value("${ffmpeg.timeout.minutes:15}")
    private int ffmpegTimeoutMinutes = 15;

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void receiveVideo(MessageDTO message) {
        String inputPath = message.getVideoFile();
        Long videoID = message.getVideoID();
        long timestamp = System.currentTimeMillis();

        File encodedMp4 = null;
        File hlsDir = null;
        File outputImagePath = null;

        try {
            if (videoID != null) {
                transcodeProgressManager.updateProgress(videoID, "PREPARING", 10, "PROCESSING");
            }

            URI uri = new URI(inputPath);
            String rawFileName = Paths.get(uri.getPath()).getFileName().toString();
            String safeFileName = rawFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String baseName = safeFileName.replaceAll("\\.\\w+$", "");

            File outputDir = new File(processedVideosFolderPath);
            if (!outputDir.exists()) outputDir.mkdirs();
            
            encodedMp4 = new File(outputDir, baseName + "_720p_" + timestamp + ".mp4");
            hlsDir = new File(outputDir, baseName + "_hls_" + timestamp);
            if (!hlsDir.exists()) hlsDir.mkdirs();

            // 1. Generate poster thumbnail
            outputImagePath = new File(outputDir, baseName + "thumbnail" + ".webp");
            File generatedThumbnail = generateThumbnailFromVideo(inputPath, outputImagePath);
            Path targetPath = Paths.get(hlsDir.getAbsolutePath(), generatedThumbnail.getName());
            Files.move(generatedThumbnail.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            
            // 2. Normalize / encode to base 720p MP4
            if (videoID != null) {
                transcodeProgressManager.updateProgress(videoID, "ENCODING_BASE", 25, "PROCESSING");
            }
            boolean encodingSuccess = encodeTo720p(inputPath, encodedMp4);
            if (!encodingSuccess) throw new RuntimeException("MP4 encoding failed");

            // 3. Multi-rendition ABR HLS ladder (720p, 480p, 360p) + master.m3u8
            if (videoID != null) {
                transcodeProgressManager.updateProgress(videoID, "ENCODING_ABR_HLS", 50, "PROCESSING");
            }
            boolean hlsSuccess = convertToMultiRenditionHLS(encodedMp4, hlsDir);
            if (!hlsSuccess) {
                logger.warn("Multi-rendition HLS failed, falling back to single rendition HLS");
                boolean singleHls = convertToHLS(encodedMp4, hlsDir);
                if (!singleHls) throw new RuntimeException("HLS conversion failed");
            }

            // 4. Generate WebVTT scrubbing storyboard spritesheet
            if (videoID != null) {
                transcodeProgressManager.updateProgress(videoID, "GENERATING_STORYBOARD", 75, "PROCESSING");
            }
            try {
                generateStoryboardAndVtt(encodedMp4, hlsDir);
            } catch (Exception e) {
                logger.warn("Storyboard generation notice: {}", e.getMessage());
            }

            // 5. Upload all transcoded media assets to S3
            if (videoID != null) {
                transcodeProgressManager.updateProgress(videoID, "UPLOADING_S3", 88, "PROCESSING");
            }
            String[] s3Info = videoService.uploadDirectory(hlsDir, baseName + "_hls_" + timestamp);
            String s3URL = s3Info[0];
            String s3ThumbnailURL = s3Info[1];
            String s3StoryboardURL = s3Info.length > 2 ? s3Info[2] : null;
            String s3VttURL = s3Info.length > 3 ? s3Info[3] : null;

            logger.info("Video assets uploaded to S3: master={}, thumbnail={}, storyboard={}, vtt={}", 
                    s3URL, s3ThumbnailURL, s3StoryboardURL, s3VttURL);
            videoService.updateVideoEncodedPath(videoID, s3URL, s3ThumbnailURL, s3StoryboardURL, s3VttURL, true);

            if (videoID != null) {
                transcodeProgressManager.updateProgress(videoID, "COMPLETED", 100, "COMPLETED");
            }

        } catch (Exception e) {
            logger.error("Video processing/upload failed for video ID {}: {}", videoID, e.getMessage(), e);
            if (videoID != null) {
                transcodeProgressManager.updateProgress(videoID, "FAILED", 0, "FAILED");
                videoService.updateVideoEncodedPath(videoID, null, null, null, null, false);
            }
        } finally {
            if (hlsDir != null && hlsDir.exists()) {
                deleteRecursively(hlsDir);
            }
            if (encodedMp4 != null && encodedMp4.exists()) {
                encodedMp4.delete();
            }
            if (outputImagePath != null && outputImagePath.exists()) {
                outputImagePath.delete();
            }
        }
    }

    private boolean convertToMultiRenditionHLS(File encodedMp4, File hlsDir) {
        try {
            boolean r720 = encodeRendition(encodedMp4, hlsDir, "720p", 1280, 720, "2500k", "2675k", "3750k", "128k");
            boolean r480 = encodeRendition(encodedMp4, hlsDir, "480p", 854, 480, "1200k", "1284k", "1800k", "96k");
            boolean r360 = encodeRendition(encodedMp4, hlsDir, "360p", 640, 360, "600k", "642k", "900k", "64k");

            if (!r720 && !r480 && !r360) {
                return false;
            }

            File masterPlaylist = new File(hlsDir, "master.m3u8");
            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n#EXT-X-VERSION:3\n");
            if (r720) {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=2628000,RESOLUTION=1280x720,NAME=\"720p\"\n720p.m3u8\n");
            }
            if (r480) {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=1296000,RESOLUTION=854x480,NAME=\"480p\"\n480p.m3u8\n");
            }
            if (r360) {
                sb.append("#EXT-X-STREAM-INF:BANDWIDTH=664000,RESOLUTION=640x360,NAME=\"360p\"\n360p.m3u8\n");
            }
            Files.writeString(masterPlaylist.toPath(), sb.toString());

            // Backward compatibility: copy master.m3u8 to output.m3u8 for existing players
            File outputPlaylist = new File(hlsDir, "output.m3u8");
            Files.copy(masterPlaylist.toPath(), outputPlaylist.toPath(), StandardCopyOption.REPLACE_EXISTING);

            return true;
        } catch (Exception e) {
            logger.error("Error creating multi-rendition ABR HLS: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean encodeRendition(File inputMp4, File hlsDir, String name, int width, int height,
                                    String vBitrate, String maxRate, String bufSize, String aBitrate) {
        File playlist = new File(hlsDir, name + ".m3u8");
        File segmentPattern = new File(hlsDir, name + "_%03d.ts");

        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputMp4.getAbsolutePath(),
                "-vf", "scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease,pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2",
                "-c:v", "libx264",
                "-preset", "fast",
                "-b:v", vBitrate,
                "-maxrate", maxRate,
                "-bufsize", bufSize,
                "-c:a", "aac",
                "-b:a", aBitrate,
                "-hls_time", "5",
                "-hls_list_size", "0",
                "-hls_segment_filename", segmentPattern.getAbsolutePath(),
                "-f", "hls",
                playlist.getAbsolutePath()
        );
        try {
            return processBuilderFFmpeg(builder);
        } catch (Exception e) {
            logger.warn("Failed to encode rendition {}: {}", name, e.getMessage());
            return false;
        }
    }

    private void generateStoryboardAndVtt(File inputVideo, File hlsDir) {
        File storyboardWebp = new File(hlsDir, "storyboard.webp");
        int cols = 5;
        int rows = 5;
        int thumbW = 160;
        int thumbH = 90;
        int intervalSec = 5;

        // Tile 5x5 grid of thumbnails taken every 5 seconds
        ProcessBuilder storyboardBuilder = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputVideo.getAbsolutePath(),
                "-vf", "fps=1/" + intervalSec + ",scale=" + thumbW + ":" + thumbH + ",tile=" + cols + "x" + rows,
                "-frames:v", "1",
                storyboardWebp.getAbsolutePath()
        );

        try {
            boolean success = processBuilderFFmpeg(storyboardBuilder);
            if (success && storyboardWebp.exists() && storyboardWebp.length() > 0) {
                File vttFile = new File(hlsDir, "thumbnails.vtt");
                StringBuilder sb = new StringBuilder();
                sb.append("WEBVTT\n\n");
                int totalFrames = cols * rows;
                for (int i = 0; i < totalFrames; i++) {
                    int col = i % cols;
                    int row = i / cols;
                    int x = col * thumbW;
                    int y = row * thumbH;
                    int startSec = i * intervalSec;
                    int endSec = (i + 1) * intervalSec;

                    String startTime = formatVttTime(startSec);
                    String endTime = formatVttTime(endSec);

                    sb.append(startTime).append(" --> ").append(endTime).append("\n");
                    sb.append("storyboard.webp#xywh=").append(x).append(",").append(y).append(",")
                            .append(thumbW).append(",").append(thumbH).append("\n\n");
                }
                Files.writeString(vttFile.toPath(), sb.toString());
                logger.info("Successfully generated storyboard.webp and thumbnails.vtt");
            }
        } catch (Exception e) {
            logger.warn("Storyboard generation failed (non-critical): {}", e.getMessage());
        }
    }

    private String formatVttTime(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d.000", hours, minutes, seconds);
    }

    private File generateThumbnailFromVideo(String inputVideoPath, File outputImagePath) throws IOException, InterruptedException {
        ProcessBuilder thumbnailBuilder = new ProcessBuilder(
            "ffmpeg",
            "-i", inputVideoPath,
            "-vf", "thumbnail,scale=320:-1",
            "-frames:v", "1",
            outputImagePath.getAbsolutePath()
        );

        processBuilderFFmpeg(thumbnailBuilder);
        return outputImagePath;
    }
    
    private boolean encodeTo720p(String inputPath, File encodedMp4) throws IOException, InterruptedException {
        ProcessBuilder encodeBuilder = new ProcessBuilder(
                "ffmpeg", "-i", inputPath,
                "-vf", "scale=-2:720",
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "23",
                "-y",
                encodedMp4.getAbsolutePath()
        );
        return processBuilderFFmpeg(encodeBuilder);
    }

    private boolean convertToHLS(File encodedMp4, File hlsDir) throws IOException, InterruptedException {
        File playlistFile = new File(hlsDir, "output.m3u8");

        ProcessBuilder hlsBuilder = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", encodedMp4.getAbsolutePath(),
                "-force_key_frames", "expr:gte(t,n_forced*5)",
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "23",
                "-start_number", "0",
                "-hls_time", "5",
                "-hls_list_size", "0",
                "-f", "hls",
                playlistFile.getAbsolutePath()
        );
        return processBuilderFFmpeg(hlsBuilder);
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            for (File child : Objects.requireNonNull(file.listFiles())) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private boolean processBuilderFFmpeg(ProcessBuilder processBuilder) throws IOException, InterruptedException {
        processBuilder.redirectErrorStream(true);
        Process hlsProcess = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(hlsProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[FFmpeg log]: {}", line);
            }
        }

        boolean finished = hlsProcess.waitFor(ffmpegTimeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            hlsProcess.destroyForcibly();
            throw new RuntimeException("FFmpeg timed out after " + ffmpegTimeoutMinutes + " minutes");
        }

        if (hlsProcess.exitValue() != 0) {
            throw new RuntimeException("FFmpeg exited with non-zero exit code: " + hlsProcess.exitValue());
        }

        return true;
    }
}