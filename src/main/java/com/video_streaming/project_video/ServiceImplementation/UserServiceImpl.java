package com.video_streaming.project_video.ServiceImplementation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.video_streaming.project_video.DTOMapper.UserDTOMapper;
import com.video_streaming.project_video.DTOs.UserDTO;
import com.video_streaming.project_video.Entity.User;
import com.video_streaming.project_video.Repository.UserRepository;
import com.video_streaming.project_video.Service.UserService;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.google.firebase.auth.UserRecord.CreateRequest;

import static com.video_streaming.project_video.Enums.SupportVariables.thumbnailURLDefault;

@Service
public class UserServiceImpl implements UserService {
    private static final String DUPLICATE_ACCOUNT_ERROR = "EMAIL_EXISTS";
    private static final String USER_ROLE_USER = "USER";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,30}$");
    private static final Set<String> RESERVED_USERNAMES = Set.of(
            "admin", "administrator", "root", "system", "support", "help",
            "buzzclips", "bideo", "api", "auth", "login", "logout", "signup",
            "explore", "trending", "subscriptions", "playlists", "feed",
            "channel", "video", "videos", "embed", "settings", "profile",
            "null", "undefined", "anonymous", "moderator"
    );

    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);
    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;
    private final com.video_streaming.project_video.Utils.UsernameBloomFilter usernameBloomFilter;

    // Backward-compatible 2-argument constructor for tests
    public UserServiceImpl(FirebaseAuth firebaseAuth, UserRepository userRepository) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
        this.usernameBloomFilter = new com.video_streaming.project_video.Utils.UsernameBloomFilter(null, userRepository);
    }

    // Full 3-argument constructor for Spring dependency injection
    @org.springframework.beans.factory.annotation.Autowired
    public UserServiceImpl(
            FirebaseAuth firebaseAuth,
            UserRepository userRepository,
            com.video_streaming.project_video.Utils.UsernameBloomFilter usernameBloomFilter
    ) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
        this.usernameBloomFilter = usernameBloomFilter != null
                ? usernameBloomFilter
                : new com.video_streaming.project_video.Utils.UsernameBloomFilter(null, userRepository);
    }

    public com.video_streaming.project_video.Utils.UsernameBloomFilter getUsernameBloomFilter() {
        return usernameBloomFilter;
    }

    public static String sanitizeUsername(String input) {
        if (input == null) return "";
        String clean = input.trim();
        if (clean.startsWith("@")) {
            clean = clean.substring(1).trim();
        }
        return clean;
    }

    public static void validateUsernameFormat(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (username.length() < 3 || username.length() > 30) {
            throw new IllegalArgumentException("Username must be between 3 and 30 characters in length");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("Username may only contain alphanumeric characters and underscores");
        }
        if (RESERVED_USERNAMES.contains(username.toLowerCase())) {
            throw new IllegalArgumentException("Username '" + username + "' is reserved and cannot be claimed");
        }
    }

    @Override
    @Transactional
    public void create(String emailId, String password, String user_name, String avatar_url) throws Exception {
        create(emailId, password, user_name, user_name, avatar_url);
    }

    @Override
    @Transactional
    public void create(String emailId, String password, String username, String fullName, String avatar_url) throws Exception {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        String cleanUsername = sanitizeUsername(username);
        validateUsernameFormat(cleanUsername);

        if (userRepository.existsByUserNameIgnoreCase(cleanUsername)) {
            throw new IllegalArgumentException("Username '" + cleanUsername + "' is already taken");
        }

        String cleanFullName = (fullName != null && !fullName.trim().isEmpty())
                ? fullName.trim()
                : cleanUsername;
        if (cleanFullName.length() > 100) {
            cleanFullName = cleanFullName.substring(0, 100).trim();
        }

        CreateRequest request = new CreateRequest();
        request.setEmail(emailId);
        request.setPassword(password);
        request.setDisplayName(cleanFullName);
        request.setEmailVerified(Boolean.TRUE);

        try {
            UserRecord userRecord = firebaseAuth.createUser(request);
            UserDTO userDTO = new UserDTO();
            userDTO.setUserId(userRecord.getUid());
            userDTO.setUsername(cleanUsername);
            userDTO.setUser_name(cleanUsername);
            userDTO.setFullName(cleanFullName);
            userDTO.setFull_name(cleanFullName);
            userDTO.setUser_email(userRecord.getEmail());
            userDTO.setAvatar_url(avatar_url != null && !avatar_url.isBlank() ? avatar_url : thumbnailURLDefault);
            updateUserMetadata(userDTO);
            usernameBloomFilter.add(cleanUsername);
        } catch (FirebaseAuthException exception) {
            if (exception.getMessage().contains(DUPLICATE_ACCOUNT_ERROR)) {
                throw new Exception("Account with given email-id already exists");
            }
            throw exception;
        }
    }

    @Transactional
    protected void updateUserMetadata(UserDTO userDTO) {
        UserDTOMapper userDTOMapper = new UserDTOMapper();
        User user = userDTOMapper.convertDTOToEntity(userDTO);
        user.setUser_role(USER_ROLE_USER);
        userRepository.save(user);
        logger.info("User metadata stored for userId: {}", user.getUserId());
    }

    @Override
    @Transactional
    public String updateUserProfile(UserDTO userDTO) {
        if (userDTO == null || userDTO.getUserId() == null || userDTO.getUserId().isBlank()) {
            throw new IllegalArgumentException("User ID is required to update profile");
        }

        Optional<User> userOpt = userRepository.findById(userDTO.getUserId().trim());
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findFirstByUserNameIgnoreCase(sanitizeUsername(userDTO.getUserId()));
        }
        User user = userOpt.orElseThrow(() -> new RuntimeException("User not found with ID or username: " + userDTO.getUserId()));

        // 1. Update Full Name / Channel Display Name
        String newFullName = userDTO.getFullName() != null ? userDTO.getFullName() : userDTO.getFull_name();
        if (newFullName != null && !newFullName.isBlank()) {
            String cleanName = newFullName.trim();
            if (cleanName.length() > 100) {
                cleanName = cleanName.substring(0, 100).trim();
            }
            user.setFull_name(cleanName);
        }

        // 2. Update Username / Handle (with uniqueness and format checks)
        String newUsername = userDTO.getUsername() != null ? userDTO.getUsername() : userDTO.getUser_name();
        if (newUsername != null && !newUsername.isBlank()) {
            String cleanHandle = sanitizeUsername(newUsername);
            if (!cleanHandle.equalsIgnoreCase(user.getUser_name())) {
                validateUsernameFormat(cleanHandle);
                if (userRepository.existsByUserNameIgnoreCaseAndUserIdNot(cleanHandle, user.getUserId())) {
                    throw new IllegalArgumentException("Username '" + cleanHandle + "' is already taken by another account");
                }
                user.setUser_name(cleanHandle);
                usernameBloomFilter.add(cleanHandle);
            }
        }

        // 3. Update Email
        String newEmail = userDTO.getUser_email() != null ? userDTO.getUser_email() : userDTO.getEmail();
        if (newEmail != null && !newEmail.isBlank()) {
            user.setUser_email(newEmail.trim());
        }

        // 4. Update Avatar URL
        String newAvatar = userDTO.getAvatar_url() != null ? userDTO.getAvatar_url() : userDTO.getAvatarUrl();
        if (newAvatar != null && !newAvatar.isBlank()) {
            user.setAvatar_url(newAvatar.trim());
        }

        userRepository.save(user);
        logger.info("User details updated successfully for userId: {}", user.getUserId());
        return "User updated successfully";
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO getUserById(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new RuntimeException("User identifier is required");
        }
        String cleanIdentifier = sanitizeUsername(identifier);
        UserDTOMapper userDTOMapper = new UserDTOMapper();

        // 1. Try finding by primary key (userId)
        Optional<User> userOpt = userRepository.findById(identifier.trim());

        // 2. If not found by primary key, try finding by case-insensitive username handle
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findFirstByUserNameIgnoreCase(cleanIdentifier);
        }

        if (userOpt.isEmpty()) {
            throw new RuntimeException("User not found with ID or username: " + identifier);
        }

        return userDTOMapper.convertEntityToDTO(userOpt.get());
    }

    @Override
    public String resolveEmailFromIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return identifier;
        }
        String trimmed = identifier.trim();
        // If handle formatted with @ prefix (e.g. @johndoe)
        if (trimmed.startsWith("@")) {
            String clean = sanitizeUsername(trimmed);
            return userRepository.findFirstByUserNameIgnoreCase(clean)
                    .map(User::getUser_email)
                    .orElse(identifier);
        }
        // Standard email address contains @ inside
        if (trimmed.contains("@")) {
            return trimmed;
        }
        // Bare username
        return userRepository.findFirstByUserNameIgnoreCase(trimmed)
                .map(User::getUser_email)
                .orElse(trimmed);
    }

    @Override
    public boolean isUsernameValid(String username) {
        if (username == null || username.isBlank()) return false;
        String clean = sanitizeUsername(username);
        return USERNAME_PATTERN.matcher(clean).matches() && !RESERVED_USERNAMES.contains(clean.toLowerCase());
    }

    @Override
    public boolean isUsernameAvailable(String username) {
        if (username == null || username.isBlank()) return false;
        String clean = sanitizeUsername(username);
        if (!isUsernameValid(clean)) {
            return false;
        }

        // Direct ground truth database query to guarantee 100% accuracy and prevent false availability
        boolean taken = userRepository.existsByUserNameIgnoreCase(clean);
        if (taken) {
            if (usernameBloomFilter != null) {
                usernameBloomFilter.add(clean);
            }
            return false;
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDTO> searchUsers(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        String cleanQuery = sanitizeUsername(query);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        UserDTOMapper mapper = new UserDTOMapper();
        List<User> matches = userRepository.searchUsers(cleanQuery, PageRequest.of(0, safeLimit));
        return matches.stream().map(mapper::convertEntityToDTO).toList();
    }

    @Override
    @Transactional
    public com.video_streaming.project_video.DTOs.GoogleUserSyncResult provisionOrSyncGoogleUser(
            String uid,
            String email,
            String displayName,
            String photoUrl,
            String preferredUsername,
            String preferredFullName
    ) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("User UID cannot be empty for Google synchronization");
        }

        UserDTOMapper mapper = new UserDTOMapper();
        Optional<User> existingUserOpt = userRepository.findById(uid.trim());

        if (existingUserOpt.isPresent()) {
            User existing = existingUserOpt.get();
            boolean updated = false;

            if ((existing.getUser_email() == null || existing.getUser_email().isBlank()) && email != null && !email.isBlank()) {
                existing.setUser_email(email.trim());
                updated = true;
            }
            if ((existing.getAvatar_url() == null || thumbnailURLDefault.equals(existing.getAvatar_url())) && photoUrl != null && !photoUrl.isBlank()) {
                existing.setAvatar_url(photoUrl.trim());
                updated = true;
            }
            if ((existing.getFull_name() == null || existing.getFull_name().isBlank()) && displayName != null && !displayName.isBlank()) {
                existing.setFull_name(displayName.trim());
                updated = true;
            }

            if (updated) {
                userRepository.save(existing);
            }
            return new com.video_streaming.project_video.DTOs.GoogleUserSyncResult(mapper.convertEntityToDTO(existing), false);
        }

        // New user provisioning: generate unique handle
        String handleCandidate = null;
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            String sanitized = sanitizeUsername(preferredUsername);
            if (isUsernameValid(sanitized) && isUsernameAvailable(sanitized)) {
                handleCandidate = sanitized;
            }
        }

        if (handleCandidate == null) {
            handleCandidate = generateUniqueHandle(displayName, email, uid);
        }

        String finalFullName = (preferredFullName != null && !preferredFullName.isBlank())
                ? preferredFullName.trim()
                : (displayName != null && !displayName.isBlank() ? displayName.trim() : handleCandidate);
        if (finalFullName.length() > 100) {
            finalFullName = finalFullName.substring(0, 100).trim();
        }

        String finalAvatar = (photoUrl != null && !photoUrl.isBlank()) ? photoUrl.trim() : thumbnailURLDefault;

        User newUser = new User(
                uid.trim(),
                handleCandidate,
                finalFullName,
                email != null ? email.trim() : null,
                USER_ROLE_USER,
                finalAvatar
        );

        userRepository.save(newUser);
        usernameBloomFilter.add(handleCandidate);
        logger.info("Successfully provisioned new Google user: handle=@{}, uid={}", handleCandidate, uid);

        return new com.video_streaming.project_video.DTOs.GoogleUserSyncResult(mapper.convertEntityToDTO(newUser), true);
    }

    private String generateUniqueHandle(String displayName, String email, String uid) {
        String base = "";
        if (displayName != null && !displayName.isBlank()) {
            base = displayName.toLowerCase().replaceAll("[^a-z0-9_]", "");
        }
        if (base.length() < 3 && email != null && email.contains("@")) {
            String emailPrefix = email.substring(0, email.indexOf('@')).toLowerCase().replaceAll("[^a-z0-9_]", "");
            if (emailPrefix.length() >= 3) {
                base = emailPrefix;
            }
        }
        if (base.length() < 3) {
            String cleanUid = uid.toLowerCase().replaceAll("[^a-z0-9_]", "");
            base = "user_" + (cleanUid.length() >= 6 ? cleanUid.substring(0, 6) : "stream");
        }
        if (base.length() > 22) {
            base = base.substring(0, 22);
        }

        // Check availability and collision resolution
        if (isUsernameValid(base) && isUsernameAvailable(base)) {
            return base;
        }

        for (int i = 1; i <= 999; i++) {
            String candidate = base + i;
            if (candidate.length() <= 30 && isUsernameValid(candidate) && isUsernameAvailable(candidate)) {
                return candidate;
            }
        }

        // Fallback with random suffix
        String randomSuffix = String.format("%04d", (int) (Math.random() * 10000));
        String fallback = (base.length() > 25 ? base.substring(0, 25) : base) + "_" + randomSuffix;
        if (fallback.length() > 30) {
            fallback = fallback.substring(0, 30);
        }
        return fallback;
    }
}
