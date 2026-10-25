package com.video_streaming.project_video.Repository;

import com.video_streaming.project_video.Entity.PlaylistVideo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlaylistVideoRepository extends JpaRepository<PlaylistVideo, Long> {

    List<PlaylistVideo> findByPlaylistIdOrderByPositionAsc(Long playlistId);

    Optional<PlaylistVideo> findByPlaylistIdAndVideo_VideoId(Long playlistId, Long videoId);

    boolean existsByPlaylistIdAndVideo_VideoId(Long playlistId, Long videoId);

    long countByPlaylistId(Long playlistId);

    @Modifying
    @Query("DELETE FROM PlaylistVideo pv WHERE pv.playlistId = :playlistId AND pv.video.videoId = :videoId")
    void deleteByPlaylistIdAndVideoId(@Param("playlistId") Long playlistId, @Param("videoId") Long videoId);

    @Modifying
    @Query("DELETE FROM PlaylistVideo pv WHERE pv.playlistId = :playlistId")
    void deleteByPlaylistId(@Param("playlistId") Long playlistId);

    @Modifying
    @Query("DELETE FROM PlaylistVideo pv WHERE pv.video.videoId = :videoId")
    void deleteByVideoId(@Param("videoId") Long videoId);
}
