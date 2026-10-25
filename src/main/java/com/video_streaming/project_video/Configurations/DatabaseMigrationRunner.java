package com.video_streaming.project_video.Configurations;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DatabaseMigrationRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        logger.info("Checking and applying resilient database schema migrations...");

        // 0. Enable pg_trgm extension for fuzzy trigram search
        executeSafe("pg_trgm extension", "CREATE EXTENSION IF NOT EXISTS pg_trgm;");

        // 1. User table migrations (Username vs Full Name distinction)
        executeSafe("user_info full_name column", "ALTER TABLE user_info ADD COLUMN IF NOT EXISTS full_name VARCHAR(100);");
        executeSafe("user_info full_name backfill", "UPDATE user_info SET full_name = user_name WHERE full_name IS NULL OR TRIM(full_name) = '';");
        executeSafe("user_info username lowercase unique index", "CREATE UNIQUE INDEX IF NOT EXISTS idx_user_info_user_name_lower ON user_info (LOWER(user_name));");
        executeSafe("user_info full_name index", "CREATE INDEX IF NOT EXISTS idx_user_info_full_name ON user_info (full_name);");

        // 2. Video metadata columns
        executeSafe("vid_metadata slug column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS slug VARCHAR(255);");
        executeSafe("vid_metadata description column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS description TEXT;");
        executeSafe("vid_metadata category column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS category VARCHAR(50) DEFAULT 'OTHER';");
        executeSafe("vid_metadata visibility column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS visibility VARCHAR(50) DEFAULT 'PUBLIC';");
        executeSafe("vid_metadata iso_duration column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS iso_duration VARCHAR(50);");
        executeSafe("vid_metadata likes_count column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS likes_count BIGINT DEFAULT 0;");
        executeSafe("vid_metadata dislikes_count column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS dislikes_count BIGINT DEFAULT 0;");
        executeSafe("vid_metadata storyboard_url column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS storyboard_url VARCHAR(500);");
        executeSafe("vid_metadata vtt_url column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS vtt_url VARCHAR(500);");
        executeSafe("vid_metadata video_status column", "ALTER TABLE vid_metadata ADD COLUMN IF NOT EXISTS video_status VARCHAR(50) DEFAULT 'PENDING';");

        // 3. Backfill video metadata defaults
        executeSafe("vid_metadata visibility backfill", "UPDATE vid_metadata SET visibility = 'PUBLIC' WHERE visibility IS NULL;");
        executeSafe("vid_metadata category backfill", "UPDATE vid_metadata SET category = 'OTHER' WHERE category IS NULL;");
        executeSafe("vid_metadata slug backfill", "UPDATE vid_metadata SET slug = CONCAT('video-', video_id) WHERE slug IS NULL;");
        executeSafe("vid_metadata likes_count backfill", "UPDATE vid_metadata SET likes_count = 0 WHERE likes_count IS NULL;");
        executeSafe("vid_metadata dislikes_count backfill", "UPDATE vid_metadata SET dislikes_count = 0 WHERE dislikes_count IS NULL;");
        executeSafe("vid_metadata video_status backfill completed (snake_case)", "UPDATE vid_metadata SET video_status = 'COMPLETED' WHERE m3u8_url IS NOT NULL AND (video_status IS NULL OR video_status = 'PENDING');");
        executeSafe("vid_metadata video_status backfill completed (lowercase)", "UPDATE vid_metadata SET video_status = 'COMPLETED' WHERE m3u8url IS NOT NULL AND (video_status IS NULL OR video_status = 'PENDING');");
        executeSafe("vid_metadata video_status backfill pending", "UPDATE vid_metadata SET video_status = 'PENDING' WHERE video_status IS NULL;");

        // 4. Video tags table
        executeSafe("video_tags table", "CREATE TABLE IF NOT EXISTS video_tags (" +
                "video_id BIGINT NOT NULL, " +
                "tag VARCHAR(255) NOT NULL, " +
                "CONSTRAINT fk_video_tags_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE" +
                ");");

        // 5. Video likes table
        executeSafe("video_likes table", "CREATE TABLE IF NOT EXISTS video_likes (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "video_id BIGINT NOT NULL, " +
                "user_id VARCHAR(255) NOT NULL, " +
                "is_like BOOLEAN NOT NULL, " +
                "created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, " +
                "CONSTRAINT uk_video_likes_video_user UNIQUE (video_id, user_id)" +
                ");");
        executeSafe("video_likes video_id index", "CREATE INDEX IF NOT EXISTS idx_video_likes_video ON video_likes(video_id);");
        executeSafe("video_likes user_id index", "CREATE INDEX IF NOT EXISTS idx_video_likes_user ON video_likes(user_id);");

        // 6. Video comments table
        executeSafe("video_comments table", "CREATE TABLE IF NOT EXISTS video_comments (" +
                "comment_id BIGSERIAL PRIMARY KEY, " +
                "video_id BIGINT NOT NULL, " +
                "user_id VARCHAR(255) NOT NULL, " +
                "parent_comment_id BIGINT, " +
                "content TEXT NOT NULL, " +
                "timestamp_in_seconds INTEGER, " +
                "like_count BIGINT DEFAULT 0, " +
                "is_pinned BOOLEAN DEFAULT FALSE, " +
                "created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, " +
                "updated_at TIMESTAMP WITHOUT TIME ZONE, " +
                "CONSTRAINT fk_comments_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE" +
                ");");
        executeSafe("video_comments video created index", "CREATE INDEX IF NOT EXISTS idx_comments_video_created ON video_comments(video_id, created_at);");
        executeSafe("video_comments parent index", "CREATE INDEX IF NOT EXISTS idx_comments_parent ON video_comments(parent_comment_id);");

        // 7. Watch history table
        executeSafe("watch_history table", "CREATE TABLE IF NOT EXISTS watch_history (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "user_id VARCHAR(255) NOT NULL, " +
                "video_id BIGINT NOT NULL, " +
                "progress_seconds DOUBLE PRECISION NOT NULL, " +
                "duration_seconds DOUBLE PRECISION, " +
                "completed BOOLEAN DEFAULT FALSE, " +
                "last_watched_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, " +
                "CONSTRAINT uk_watch_history_user_video UNIQUE (user_id, video_id), " +
                "CONSTRAINT fk_watch_history_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE" +
                ");");
        executeSafe("watch_history user time index", "CREATE INDEX IF NOT EXISTS idx_watch_history_user_time ON watch_history(user_id, last_watched_at);");

        // 8. Subscriptions table
        executeSafe("subscriptions table", "CREATE TABLE IF NOT EXISTS subscriptions (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "subscriber_user_id VARCHAR(255) NOT NULL, " +
                "channel_user_id VARCHAR(255) NOT NULL, " +
                "created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, " +
                "CONSTRAINT uk_subscription UNIQUE (subscriber_user_id, channel_user_id)" +
                ");");
        executeSafe("subscriptions channel index", "CREATE INDEX IF NOT EXISTS idx_sub_channel ON subscriptions(channel_user_id);");
        executeSafe("subscriptions subscriber index", "CREATE INDEX IF NOT EXISTS idx_sub_subscriber ON subscriptions(subscriber_user_id);");

        // 9. Playlists table
        executeSafe("playlists table", "CREATE TABLE IF NOT EXISTS playlists (" +
                "playlist_id BIGSERIAL PRIMARY KEY, " +
                "title VARCHAR(255) NOT NULL, " +
                "description TEXT, " +
                "user_id VARCHAR(255) NOT NULL, " +
                "visibility VARCHAR(50) DEFAULT 'PUBLIC', " +
                "is_system BOOLEAN DEFAULT FALSE, " +
                "created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, " +
                "updated_at TIMESTAMP WITHOUT TIME ZONE" +
                ");");
        executeSafe("playlists user index", "CREATE INDEX IF NOT EXISTS idx_playlist_user ON playlists(user_id);");

        // 10. Playlist videos table
        executeSafe("playlist_videos table", "CREATE TABLE IF NOT EXISTS playlist_videos (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "playlist_id BIGINT NOT NULL, " +
                "video_id BIGINT NOT NULL, " +
                "position INT DEFAULT 0, " +
                "added_at TIMESTAMP WITHOUT TIME ZONE NOT NULL, " +
                "CONSTRAINT uk_playlist_video UNIQUE (playlist_id, video_id), " +
                "CONSTRAINT fk_pv_playlist FOREIGN KEY (playlist_id) REFERENCES playlists(playlist_id) ON DELETE CASCADE, " +
                "CONSTRAINT fk_pv_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE" +
                ");");
        executeSafe("playlist_videos position index", "CREATE INDEX IF NOT EXISTS idx_pv_playlist ON playlist_videos(playlist_id, position);");

        // 11. Ensure ON DELETE CASCADE constraints for foreign keys referencing vid_metadata
        executeSafe("cascade fk_video_tags_video",
                "DO $$ BEGIN " +
                "IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_video_tags_video') THEN " +
                "ALTER TABLE video_tags DROP CONSTRAINT fk_video_tags_video; " +
                "END IF; " +
                "ALTER TABLE video_tags ADD CONSTRAINT fk_video_tags_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE; " +
                "END $$;");

        executeSafe("cascade fk_video_likes_video",
                "DO $$ BEGIN " +
                "IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_video_likes_video') THEN " +
                "ALTER TABLE video_likes DROP CONSTRAINT fk_video_likes_video; " +
                "END IF; " +
                "ALTER TABLE video_likes ADD CONSTRAINT fk_video_likes_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE; " +
                "END $$;");

        executeSafe("cascade fk_comments_video",
                "DO $$ BEGIN " +
                "IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_comments_video') THEN " +
                "ALTER TABLE video_comments DROP CONSTRAINT fk_comments_video; " +
                "END IF; " +
                "ALTER TABLE video_comments ADD CONSTRAINT fk_comments_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE; " +
                "END $$;");

        executeSafe("cascade fk_watch_history_video",
                "DO $$ BEGIN " +
                "IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_watch_history_video') THEN " +
                "ALTER TABLE watch_history DROP CONSTRAINT fk_watch_history_video; " +
                "END IF; " +
                "ALTER TABLE watch_history ADD CONSTRAINT fk_watch_history_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE; " +
                "END $$;");

        executeSafe("cascade fk_pv_video",
                "DO $$ BEGIN " +
                "IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_pv_video') THEN " +
                "ALTER TABLE playlist_videos DROP CONSTRAINT fk_pv_video; " +
                "END IF; " +
                "ALTER TABLE playlist_videos ADD CONSTRAINT fk_pv_video FOREIGN KEY (video_id) REFERENCES vid_metadata(video_id) ON DELETE CASCADE; " +
                "END $$;");

        logger.info("Database schema migrations verification completed.");
    }

    private void executeSafe(String label, String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            logger.warn("Migration notice [{}]: {}", label, e.getMessage());
        }
    }
}
