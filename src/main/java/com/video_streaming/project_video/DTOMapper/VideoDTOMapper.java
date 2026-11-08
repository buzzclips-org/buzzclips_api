package com.video_streaming.project_video.DTOMapper;

import java.util.ArrayList;
import com.video_streaming.project_video.DTOs.VideoDTO;
import com.video_streaming.project_video.Entity.Video;
import com.video_streaming.project_video.Utils.SeoMetadataBuilder;

public class VideoDTOMapper {

    private final SeoMetadataBuilder seoMetadataBuilder = new SeoMetadataBuilder();

    public VideoDTO convertEntityToDTO(Video video) {
        if (video == null) return null;

        VideoDTO videoDTO = new VideoDTO();
        videoDTO.setVideoId(video.getVideoId());
        videoDTO.setM3u8Url(normalizeS3Url(video.getM3u8Url()));
        videoDTO.setVideo_title(video.getVideo_title());
        videoDTO.setSlug(video.getSlug());
        videoDTO.setDescription(video.getDescription());
        videoDTO.setTags(video.getTags() != null ? new ArrayList<>(video.getTags()) : new ArrayList<>());
        videoDTO.setCategory(video.getCategory());
        videoDTO.setVisibility(video.getVisibility());
        videoDTO.setVideo_uploadDate(video.getVideo_uploadDate());
        videoDTO.setVideo_views(video.getVideo_views());
        videoDTO.setLikesCount(video.getLikesCount() != null ? video.getLikesCount() : 0L);
        videoDTO.setDislikesCount(video.getDislikesCount() != null ? video.getDislikesCount() : 0L);
        videoDTO.setVideo_duration(video.getVideo_duration());
        videoDTO.setIso_duration(video.getIso_duration());
        videoDTO.setThumbnail_url(normalizeS3Url(video.getThumbnail_url()));
        videoDTO.setStoryboard_url(normalizeS3Url(video.getStoryboard_url()));
        videoDTO.setVtt_url(normalizeS3Url(video.getVtt_url()));
        videoDTO.setVideoStatus(video.getVideoStatus());

        if (video.getVideo_uploader() != null) {
            UserDTOMapper userDTOMapper = new UserDTOMapper();
            videoDTO.setVideo_uploader(userDTOMapper.convertEntityToDTO(video.getVideo_uploader()));
        }

        videoDTO.setSeo(seoMetadataBuilder.buildSeoMetadata(video));
        return videoDTO;
    }

    public static String normalizeS3Url(String url) {
        if (url == null || url.isBlank()) return url;
        // Fix virtual-hosted S3 URL with dots in bucket name (e.g. https://buzzclips.app.s3.ap-southeast-2.amazonaws.com/...)
        // into path-style URL (https://s3.ap-southeast-2.amazonaws.com/buzzclips.app/...) to avoid SSL cert mismatch
        if (url.contains(".s3.") || url.contains(".s3-")) {
            return url.replaceAll(
                    "^https?://([a-zA-Z0-9.-]+\\.[a-zA-Z0-9.-]+)\\.s3[.-]([a-zA-Z0-9-]+)\\.amazonaws\\.com/(.*)$",
                    "https://s3.$2.amazonaws.com/$1/$3"
            );
        }
        return url;
    }

    public Video convertDTOToEntity(VideoDTO videoDTO) {
        if (videoDTO == null) return null;

        Video video = new Video();
        video.setVideoId(videoDTO.getVideoId());
        video.setM3u8Url(videoDTO.getM3u8Url());
        video.setVideo_title(videoDTO.getVideo_title());
        video.setSlug(videoDTO.getSlug());
        video.setDescription(videoDTO.getDescription());
        video.setTags(videoDTO.getTags() != null ? new ArrayList<>(videoDTO.getTags()) : new ArrayList<>());
        video.setCategory(videoDTO.getCategory());
        video.setVisibility(videoDTO.getVisibility());
        video.setVideo_uploadDate(videoDTO.getVideo_uploadDate());
        video.setVideo_views(videoDTO.getVideo_views());
        video.setLikesCount(videoDTO.getLikesCount() != null ? videoDTO.getLikesCount() : 0L);
        video.setDislikesCount(videoDTO.getDislikesCount() != null ? videoDTO.getDislikesCount() : 0L);
        video.setVideo_duration(videoDTO.getVideo_duration());
        video.setIso_duration(videoDTO.getIso_duration());
        video.setThumbnail_url(videoDTO.getThumbnail_url());
        video.setStoryboard_url(videoDTO.getStoryboard_url());
        video.setVtt_url(videoDTO.getVtt_url());
        video.setVideoStatus(videoDTO.getVideoStatus());

        if (videoDTO.getVideo_uploader() != null) {
            UserDTOMapper userDTOMapper = new UserDTOMapper();
            video.setVideo_uploader(userDTOMapper.convertDTOToEntity(videoDTO.getVideo_uploader()));
        }
        
        return video;
    }
}
