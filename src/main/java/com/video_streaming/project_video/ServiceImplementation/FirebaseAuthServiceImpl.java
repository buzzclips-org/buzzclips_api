package com.video_streaming.project_video.ServiceImplementation;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserProvider;
import com.google.firebase.auth.UserRecord;
import com.video_streaming.project_video.DTOs.*;
import com.video_streaming.project_video.Entity.FirebaseRefreshTokenRequest;
import com.video_streaming.project_video.Entity.FirebaseRefreshTokenResponse;
import com.video_streaming.project_video.Entity.FirebaseSignInRequest;
import com.video_streaming.project_video.Entity.FirebaseSignInResponse;
import com.video_streaming.project_video.Entity.User;
import com.video_streaming.project_video.Repository.UserRepository;
import com.video_streaming.project_video.Service.FirebaseAuthService;
import com.video_streaming.project_video.Service.UserService;
import com.video_streaming.project_video.Utils.DefaultFirebaseClientAdapter;
import com.video_streaming.project_video.Utils.FirebaseClientAdapter;
import com.video_streaming.project_video.Utils.FirebaseTokenClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static com.video_streaming.project_video.Enums.SupportVariables.refreshTokenURLSuffix;
import static com.video_streaming.project_video.Enums.SupportVariables.resetUserURLSuffix;
import static com.video_streaming.project_video.Enums.SupportVariables.signInTokenURLSuffix;
import static com.video_streaming.project_video.Enums.SupportVariables.thumbnailURLDefault;

@Service
public class FirebaseAuthServiceImpl implements FirebaseAuthService {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseAuthServiceImpl.class);
    private static final String PROVIDER_GOOGLE = "google.com";
    private static final String PROVIDER_PASSWORD = "password";

    @Value("${firebase.api.key:}")
    private String FIREBASE_API_KEY;

    @Autowired(required = false)
    private FirebaseAuth firebaseAuth;

    @Autowired(required = false)
    private FirebaseClientAdapter firebaseClientAdapter;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    private final RestTemplate restTemplate = new RestTemplate();

    public FirebaseAuthServiceImpl() {}

    public FirebaseAuthServiceImpl(UserRepository userRepository, String firebaseApiKey) {
        this.userRepository = userRepository;
        this.FIREBASE_API_KEY = firebaseApiKey;
    }

    public FirebaseAuthServiceImpl(UserRepository userRepository, String firebaseApiKey, UserService userService, FirebaseAuth firebaseAuth) {
        this.userRepository = userRepository;
        this.FIREBASE_API_KEY = firebaseApiKey;
        this.userService = userService;
        this.firebaseAuth = firebaseAuth;
    }

    public FirebaseAuthServiceImpl(UserRepository userRepository, String firebaseApiKey, UserService userService, FirebaseClientAdapter firebaseClientAdapter) {
        this.userRepository = userRepository;
        this.FIREBASE_API_KEY = firebaseApiKey;
        this.userService = userService;
        this.firebaseClientAdapter = firebaseClientAdapter;
    }

    private FirebaseClientAdapter getClientAdapter() {
        if (this.firebaseClientAdapter != null) {
            return this.firebaseClientAdapter;
        }
        return new DefaultFirebaseClientAdapter(this.firebaseAuth);
    }

    private String getSignInUrl() {
        return signInTokenURLSuffix + FIREBASE_API_KEY;
    }

    private String getRefreshTokenURL() {
        return refreshTokenURLSuffix + FIREBASE_API_KEY;
    }

    private String getResetUserURL() {
        return resetUserURLSuffix + FIREBASE_API_KEY;
    }

    @Override
    public FirebaseToken verifyToken(String idToken) throws FirebaseAuthException {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("ID token cannot be null or empty");
        }
        return getClientAdapter().verifyIdToken(idToken.trim());
    }

    @Override
    public FirebaseSignInResponse signIn(String identifier, String password) {
        return signInWithEmailAndPassword(identifier, password);
    }

    @Override
    public FirebaseSignInResponse signInWithEmailAndPassword(String email, String password) {
        String resolvedEmail = email;
        if (email != null && !email.trim().isEmpty()) {
            String trimmed = email.trim();
            if (!trimmed.contains("@") && userRepository != null) {
                resolvedEmail = userRepository.findFirstByUserNameIgnoreCase(trimmed)
                        .map(User::getUser_email)
                        .orElse(trimmed);
            }
        }

        FirebaseSignInRequest request = new FirebaseSignInRequest(resolvedEmail, password, true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<FirebaseSignInRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<FirebaseSignInResponse> response = restTemplate
                    .postForEntity(getSignInUrl(), entity, FirebaseSignInResponse.class);

            return response.getBody();
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Firebase sign-in failed: " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public FirebaseRefreshTokenResponse refreshIdToken(String refreshToken) {
        String refreshUrl = getRefreshTokenURL();

        FirebaseRefreshTokenRequest request = new FirebaseRefreshTokenRequest(refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<FirebaseRefreshTokenRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<FirebaseRefreshTokenResponse> response = restTemplate
                    .postForEntity(refreshUrl, entity, FirebaseRefreshTokenResponse.class);

            return response.getBody();
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Token refresh failed: " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public ResponseEntity<String> sendPasswordResetEmail(String email) {
        String url = getResetUserURL();

        Map<String, Object> payload = new HashMap<>();
        payload.put("requestType", "PASSWORD_RESET");
        payload.put("email", email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

        try {
            return restTemplate.postForEntity(url, requestEntity, String.class);
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Failed to send password reset email: " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    @Transactional
    public GoogleLoginResponseDTO signInWithGoogle(GoogleLoginRequestDTO request) {
        if (request == null || request.getIdToken() == null || request.getIdToken().isBlank()) {
            throw new IllegalArgumentException("Firebase ID Token is required for Google Sign-In");
        }

        String rawToken = request.getIdToken().trim();
        try {
            FirebaseTokenClaims decodedToken = getClientAdapter().verifyIdTokenClaims(rawToken);
            String uid = decodedToken.getUid();
            String email = decodedToken.getEmail();
            String name = decodedToken.getName();
            String picture = decodedToken.getPicture();
            boolean emailVerified = decodedToken.isEmailVerified();

            // Fetch authoritative provider details
            List<String> providerIds = getClientAdapter().getProviderIds(uid);

            // Synchronize with database and generate clean handle if new
            GoogleUserSyncResult syncResult = userService.provisionOrSyncGoogleUser(
                    uid,
                    email,
                    name,
                    picture,
                    request.getUsername(),
                    request.getFullName()
            );

            String primaryProvider = providerIds.contains(PROVIDER_GOOGLE) ? PROVIDER_GOOGLE
                    : (!providerIds.isEmpty() ? providerIds.get(0) : PROVIDER_GOOGLE);

            logger.info("Firebase Google login successful for uid: {}, handle: @{}, isNewUser: {}",
                    uid, syncResult.getUser().getUsername(), syncResult.isNewUser());

            return GoogleLoginResponseDTO.builder()
                    .idToken(rawToken)
                    .isNewUser(syncResult.isNewUser())
                    .primaryProvider(primaryProvider)
                    .providers(providerIds)
                    .emailVerified(emailVerified)
                    .user(syncResult.getUser())
                    .build();

        } catch (FirebaseAuthException e) {
            logger.error("Failed to verify Google Firebase ID token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Google Firebase ID token: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSecurityDTO getAccountSecurity(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required to retrieve account security profile");
        }

        try {
            UserRecord userRecord = getClientAdapter().getUser(userId.trim());
            List<ProviderInfoDTO> providerList = extractProviderDetails(userRecord);

            boolean hasGoogle = providerList.stream()
                    .anyMatch(p -> PROVIDER_GOOGLE.equalsIgnoreCase(p.getProviderId()));
            boolean hasPassword = providerList.stream()
                    .anyMatch(p -> PROVIDER_PASSWORD.equalsIgnoreCase(p.getProviderId()));

            // Safety rule: provider can only be unlinked if user has another login method
            boolean canUnlinkGoogle = hasGoogle && (hasPassword || providerList.size() > 1);
            boolean canUnlinkPassword = hasPassword && (hasGoogle || providerList.size() > 1);

            UserDTO userDTO = null;
            try {
                userDTO = userService.getUserById(userId.trim());
            } catch (Exception ex) {
                logger.warn("User profile metadata not found in local DB for uid {}: {}", userId, ex.getMessage());
            }

            return AccountSecurityDTO.builder()
                    .userId(userId.trim())
                    .email(userRecord.getEmail())
                    .emailVerified(userRecord.isEmailVerified())
                    .username(userDTO != null ? userDTO.getUsername() : null)
                    .fullName(userDTO != null ? userDTO.getFullName() : userRecord.getDisplayName())
                    .avatarUrl(userDTO != null ? userDTO.getAvatar_url() : userRecord.getPhotoUrl())
                    .providers(providerList)
                    .hasGoogleProvider(hasGoogle)
                    .hasPasswordProvider(hasPassword)
                    .canUnlinkGoogle(canUnlinkGoogle)
                    .canUnlinkPassword(canUnlinkPassword)
                    .creationTimestamp(userRecord.getUserMetadata() != null ? userRecord.getUserMetadata().getCreationTimestamp() : null)
                    .lastSignInTimestamp(userRecord.getUserMetadata() != null ? userRecord.getUserMetadata().getLastSignInTimestamp() : null)
                    .build();

        } catch (FirebaseAuthException e) {
            logger.error("Failed to retrieve Firebase user record for {}: {}", userId, e.getMessage());
            throw new RuntimeException("Error loading account profile from Firebase: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public String updateAccountEmail(String userId, String newEmail) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required to update email");
        }
        if (newEmail == null || newEmail.isBlank() || !newEmail.contains("@")) {
            throw new IllegalArgumentException("A valid email address is required");
        }

        String cleanEmail = newEmail.trim().toLowerCase();

        // 1. Collision check: ensure another user does not already use this email
        if (userRepository.existsByUserEmailIgnoreCaseAndUserIdNot(cleanEmail, userId.trim())) {
            throw new IllegalArgumentException("Email '" + cleanEmail + "' is already registered to another account");
        }

        try {
            // 2. Update Firebase Authentication
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(userId.trim())
                    .setEmail(cleanEmail)
                    .setEmailVerified(false);
            getClientAdapter().updateUser(updateRequest);

            // 3. Update PostgreSQL database
            Optional<User> userOpt = userRepository.findById(userId.trim());
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                user.setUser_email(cleanEmail);
                userRepository.save(user);
            }

            logger.info("Updated account email for uid {} to {}", userId, cleanEmail);

            // 4. Generate verification link
            try {
                return getClientAdapter().generateEmailVerificationLink(cleanEmail);
            } catch (Exception e) {
                logger.warn("Could not generate verification link for {}: {}", cleanEmail, e.getMessage());
                return null;
            }

        } catch (FirebaseAuthException e) {
            logger.error("Firebase email update failed for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Failed to update email in Firebase: " + e.getMessage(), e);
        }
    }

    @Override
    public void setAccountPassword(String userId, String newPassword) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required to set account password");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters in length");
        }

        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(userId.trim())
                    .setPassword(newPassword);
            getClientAdapter().updateUser(updateRequest);
            logger.info("Successfully configured password authentication for user {}", userId);
        } catch (FirebaseAuthException e) {
            logger.error("Failed to set password in Firebase for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Failed to update password: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public AccountSecurityDTO unlinkProvider(String userId, String providerId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required to unlink provider");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required");
        }

        String cleanProvider = providerId.trim().toLowerCase();

        try {
            UserRecord userRecord = getClientAdapter().getUser(userId.trim());
            UserInfo[] providers = userRecord.getProviderData();

            boolean hasGoogle = Arrays.stream(providers)
                    .anyMatch(p -> PROVIDER_GOOGLE.equalsIgnoreCase(p.getProviderId()));
            boolean hasPassword = Arrays.stream(providers)
                    .anyMatch(p -> PROVIDER_PASSWORD.equalsIgnoreCase(p.getProviderId()));

            // Lockout prevention checks
            if (PROVIDER_GOOGLE.equalsIgnoreCase(cleanProvider)) {
                if (!hasPassword && providers.length <= 1) {
                    throw new IllegalStateException("Cannot unlink Google: it is your only sign-in method. Please set a password first.");
                }
            } else if (PROVIDER_PASSWORD.equalsIgnoreCase(cleanProvider)) {
                if (!hasGoogle && providers.length <= 1) {
                    throw new IllegalStateException("Cannot unlink password: it is your only sign-in method. Please link Google first.");
                }
            }

            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(userId.trim())
                    .setProvidersToUnlink(List.of(cleanProvider));
            getClientAdapter().updateUser(updateRequest);

            logger.info("Successfully unlinked provider '{}' from user {}", cleanProvider, userId);
            return getAccountSecurity(userId.trim());

        } catch (FirebaseAuthException e) {
            logger.error("Failed to unlink provider {} for user {}: {}", cleanProvider, userId, e.getMessage());
            throw new RuntimeException("Failed to unlink provider: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public AccountSecurityDTO linkGoogleProvider(String userId, String idToken) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required to link Google provider");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("Firebase ID token is required to link Google provider");
        }

        try {
            FirebaseTokenClaims decodedToken = getClientAdapter().verifyIdTokenClaims(idToken.trim());
            String googleUid = decodedToken.getUid();
            String googleEmail = decodedToken.getEmail();
            String displayName = decodedToken.getName();
            String photoUrl = decodedToken.getPicture();

            // If token belongs to a different UID, link the Google provider explicitly
            if (!userId.trim().equals(googleUid)) {
                UserProvider googleProvider = UserProvider.builder()
                        .setProviderId(PROVIDER_GOOGLE)
                        .setUid(googleUid)
                        .setEmail(googleEmail)
                        .setDisplayName(displayName)
                        .setPhotoUrl(photoUrl)
                        .build();

                UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(userId.trim())
                        .setProviderToLink(googleProvider);
                getClientAdapter().updateUser(updateRequest);
            }

            // Sync user avatar if avatar is default
            Optional<User> userOpt = userRepository.findById(userId.trim());
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                boolean updated = false;
                if ((user.getAvatar_url() == null || thumbnailURLDefault.equals(user.getAvatar_url())) && photoUrl != null && !photoUrl.isBlank()) {
                    user.setAvatar_url(photoUrl.trim());
                    updated = true;
                }
                if ((user.getUser_email() == null || user.getUser_email().isBlank()) && googleEmail != null && !googleEmail.isBlank()) {
                    user.setUser_email(googleEmail.trim());
                    updated = true;
                }
                if (updated) {
                    userRepository.save(user);
                }
            }

            logger.info("Successfully linked Google provider for user {}", userId);
            return getAccountSecurity(userId.trim());

        } catch (FirebaseAuthException e) {
            logger.error("Failed to link Google provider for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Failed to link Google account: " + e.getMessage(), e);
        }
    }

    @Override
    public String generateEmailVerificationLink(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID is required");
        }

        try {
            UserRecord userRecord = getClientAdapter().getUser(userId.trim());
            if (userRecord.getEmail() == null || userRecord.getEmail().isBlank()) {
                throw new IllegalArgumentException("User does not have an email address configured");
            }
            return getClientAdapter().generateEmailVerificationLink(userRecord.getEmail());
        } catch (FirebaseAuthException e) {
            logger.error("Failed to generate email verification link for user {}: {}", userId, e.getMessage());
            throw new RuntimeException("Failed to generate verification link: " + e.getMessage(), e);
        }
    }

    private List<ProviderInfoDTO> extractProviderDetails(UserRecord userRecord) {
        if (userRecord == null || userRecord.getProviderData() == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(userRecord.getProviderData())
                .map(p -> ProviderInfoDTO.builder()
                        .providerId(p.getProviderId())
                        .uid(p.getUid())
                        .email(p.getEmail())
                        .displayName(p.getDisplayName())
                        .photoUrl(p.getPhotoUrl())
                        .build())
                .toList();
    }

    @Override
    public String getFirebaseApiKey() {
        if (FIREBASE_API_KEY != null && !FIREBASE_API_KEY.isBlank()) {
            return FIREBASE_API_KEY.trim();
        }
        String envKey = System.getenv("FIREBASE_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return "";
    }

    @Override
    public String getFirebaseProjectId() {
        try {
            if (!com.google.firebase.FirebaseApp.getApps().isEmpty()) {
                String pid = com.google.firebase.FirebaseApp.getInstance().getOptions().getProjectId();
                if (pid != null && !pid.isBlank()) {
                    return pid.trim();
                }
            }
        } catch (Exception e) {
            logger.debug("Could not resolve projectId from FirebaseApp: {}", e.getMessage());
        }
        String envPid = System.getenv("FIREBASE_PROJECT_ID");
        if (envPid != null && !envPid.isBlank()) {
            return envPid.trim();
        }
        String gcpProject = System.getenv("GOOGLE_CLOUD_PROJECT");
        if (gcpProject != null && !gcpProject.isBlank()) {
            return gcpProject.trim();
        }
        return "";
    }

    @Override
    public String getFirebaseAuthDomain() {
        String customDomain = System.getenv("FIREBASE_AUTH_DOMAIN");
        if (customDomain != null && !customDomain.isBlank()) {
            return customDomain.trim();
        }
        String pid = getFirebaseProjectId();
        return (pid != null && !pid.isBlank()) ? pid + ".firebaseapp.com" : "";
    }
}