package com.video_streaming.project_video.DTOs;

import com.video_streaming.project_video.Enums.VideoCategory;
import com.video_streaming.project_video.Enums.VideoVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CompletePresignedUploadDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String s3Key;
    private String processId;
    private String videoTitle;
    private String description;
    private List<String> tags;
    private VideoCategory category;
    private VideoVisibility visibility;
}
