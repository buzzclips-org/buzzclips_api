package com.video_streaming.project_video.Repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.video_streaming.project_video.Entity.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    @Query("SELECT u FROM User u WHERE LOWER(TRIM(u.user_name)) = LOWER(:userName) OR LOWER(TRIM(u.user_name)) = LOWER(CONCAT('@', :userName))")
    Optional<User> findFirstByUserNameIgnoreCase(@Param("userName") String userName);

    @Query("SELECT u FROM User u WHERE LOWER(TRIM(u.user_email)) = LOWER(:userEmail)")
    Optional<User> findByUserEmailIgnoreCase(@Param("userEmail") String userEmail);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(TRIM(u.user_name)) = LOWER(:userName) OR LOWER(TRIM(u.user_name)) = LOWER(CONCAT('@', :userName))")
    boolean existsByUserNameIgnoreCase(@Param("userName") String userName);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE (LOWER(TRIM(u.user_name)) = LOWER(:userName) OR LOWER(TRIM(u.user_name)) = LOWER(CONCAT('@', :userName))) AND u.userId != :userId")
    boolean existsByUserNameIgnoreCaseAndUserIdNot(@Param("userName") String userName, @Param("userId") String userId);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.user_email) = LOWER(:userEmail) AND u.userId != :userId")
    boolean existsByUserEmailIgnoreCaseAndUserIdNot(@Param("userEmail") String userEmail, @Param("userId") String userId);

    @Query("SELECT u FROM User u WHERE LOWER(u.user_name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(u.full_name) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<User> searchUsers(@Param("query") String query, Pageable pageable);

    @Query("SELECT u.user_name FROM User u WHERE u.user_name IS NOT NULL")
    List<String> findAllUsernames();
}