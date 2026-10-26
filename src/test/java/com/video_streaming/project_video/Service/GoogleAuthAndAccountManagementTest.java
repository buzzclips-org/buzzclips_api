package com.video_streaming.project_video.Service;

import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;
import com.video_streaming.project_video.DTOs.*;
import com.video_streaming.project_video.Entity.User;
import com.video_streaming.project_video.Repository.UserRepository;
import com.video_streaming.project_video.ServiceImplementation.FirebaseAuthServiceImpl;
import com.video_streaming.project_video.ServiceImplementation.UserServiceImpl;
import com.video_streaming.project_video.Utils.FirebaseClientAdapter;
import com.video_streaming.project_video.Utils.FirebaseTokenClaims;
import com.video_streaming.project_video.Utils.UsernameBloomFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GoogleAuthAndAccountManagementTest {

    private InMemoryUserRepository userRepository;
    private UsernameBloomFilter bloomFilter;
    private UserServiceImpl userService;
    private FirebaseClientAdapter mockFirebaseAdapter;
    private FirebaseAuthServiceImpl firebaseAuthService;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        bloomFilter = new UsernameBloomFilter(null, userRepository);
        userService = new UserServiceImpl(null, userRepository, bloomFilter);
        mockFirebaseAdapter = mock(FirebaseClientAdapter.class);
        firebaseAuthService = new FirebaseAuthServiceImpl(userRepository, "test-api-key", userService, mockFirebaseAdapter);
    }

    @Test
    @DisplayName("Should provision new Google user with clean, derived unique handle and add to Bloom filter")
    void testProvisionNewGoogleUser() {
        GoogleUserSyncResult result = userService.provisionOrSyncGoogleUser(
                "google-uid-001",
                "alex.smith@gmail.com",
                "Alex Smith",
                "https://lh3.googleusercontent.com/avatar.jpg",
                null,
                null
        );

        assertNotNull(result);
        assertTrue(result.isNewUser());
        UserDTO user = result.getUser();
        assertEquals("google-uid-001", user.getUserId());
        assertEquals("alexsmith", user.getUsername());
        assertEquals("Alex Smith", user.getFullName());
        assertEquals("alex.smith@gmail.com", user.getUser_email());
        assertEquals("https://lh3.googleusercontent.com/avatar.jpg", user.getAvatar_url());

        // Verify saved to repository and Bloom filter
        assertTrue(userRepository.findById("google-uid-001").isPresent());
        assertTrue(bloomFilter.mightContain("alexsmith"));
    }

    @Test
    @DisplayName("Should resolve handle collisions by appending numeric suffixes")
    void testHandleCollisionResolution() {
        // Pre-populate 'johndoe' in repository
        User existing = new User("existing-1", "johndoe", "John Doe", "john1@example.com", "USER", "https://avatar.png");
        userRepository.addUser(existing);
        bloomFilter.add("johndoe");

        // Next user with name "John Doe"
        GoogleUserSyncResult result1 = userService.provisionOrSyncGoogleUser(
                "google-uid-002",
                "john.doe2@gmail.com",
                "John Doe",
                null,
                null,
                null
        );

        assertTrue(result1.isNewUser());
        assertEquals("johndoe1", result1.getUser().getUsername());
        assertTrue(userRepository.findById("google-uid-002").isPresent());
    }

    @Test
    @DisplayName("Should respect valid custom preferred username during Google sign-up")
    void testPreferredUsernameRespected() {
        GoogleUserSyncResult result = userService.provisionOrSyncGoogleUser(
                "google-uid-003",
                "tech.reviewer@gmail.com",
                "Tech Reviewer",
                "https://avatar.jpg",
                "tech_god",
                "Tech God Reviewer"
        );

        assertTrue(result.isNewUser());
        assertEquals("tech_god", result.getUser().getUsername());
        assertEquals("Tech God Reviewer", result.getUser().getFullName());
    }

    @Test
    @DisplayName("Should update missing avatar or email for existing user on subsequent Google login")
    void testSyncExistingGoogleUser() {
        User initial = new User("google-uid-004", "mark_stream", "Mark Streamer", null, "USER", "https://github.com/shadcn.png");
        userRepository.addUser(initial);

        GoogleUserSyncResult result = userService.provisionOrSyncGoogleUser(
                "google-uid-004",
                "mark@gmail.com",
                "Mark Streamer",
                "https://lh3.googleusercontent.com/new-avatar.jpg",
                null,
                null
        );

        assertFalse(result.isNewUser());
        assertEquals("mark@gmail.com", result.getUser().getUser_email());
        assertEquals("https://lh3.googleusercontent.com/new-avatar.jpg", result.getUser().getAvatar_url());
    }

    @Test
    @DisplayName("Should successfully authenticate Google user via signInWithGoogle")
    void testSignInWithGoogle() throws Exception {
        FirebaseTokenClaims claims = FirebaseTokenClaims.builder()
                .uid("google-uid-005")
                .email("sundar@google.com")
                .name("Sundar Pichai")
                .picture("https://google.com/sundar.jpg")
                .emailVerified(true)
                .build();

        when(mockFirebaseAdapter.verifyIdTokenClaims("mock-google-id-token")).thenReturn(claims);
        when(mockFirebaseAdapter.getProviderIds("google-uid-005")).thenReturn(List.of("google.com"));

        GoogleLoginRequestDTO request = GoogleLoginRequestDTO.builder()
                .idToken("mock-google-id-token")
                .build();

        GoogleLoginResponseDTO response = firebaseAuthService.signInWithGoogle(request);

        assertNotNull(response);
        assertEquals("mock-google-id-token", response.getIdToken());
        assertTrue(response.isNewUser());
        assertEquals("google.com", response.getPrimaryProvider());
        assertTrue(response.getProviders().contains("google.com"));
        assertTrue(response.isEmailVerified());
        assertNotNull(response.getUser());
        assertEquals("sundarpichai", response.getUser().getUsername());
    }

    @Test
    @DisplayName("Should retrieve account security profile with correct unlinking permissions")
    void testGetAccountSecurity() throws Exception {
        UserRecord mockUserRecord = mock(UserRecord.class);
        when(mockUserRecord.getEmail()).thenReturn("creator@buzzclips.app");
        when(mockUserRecord.isEmailVerified()).thenReturn(true);
        when(mockUserRecord.getDisplayName()).thenReturn("Chief Creator");
        when(mockUserRecord.getPhotoUrl()).thenReturn("https://avatar.png");

        UserInfo mockGoogleInfo = mock(UserInfo.class);
        when(mockGoogleInfo.getProviderId()).thenReturn("google.com");
        when(mockGoogleInfo.getEmail()).thenReturn("creator@buzzclips.app");

        UserInfo mockPasswordInfo = mock(UserInfo.class);
        when(mockPasswordInfo.getProviderId()).thenReturn("password");

        when(mockUserRecord.getProviderData()).thenReturn(new UserInfo[]{mockGoogleInfo, mockPasswordInfo});
        when(mockFirebaseAdapter.getUser("creator-uid-10")).thenReturn(mockUserRecord);

        userRepository.addUser(new User("creator-uid-10", "creator10", "Chief Creator", "creator@buzzclips.app", "USER", "https://avatar.png"));

        AccountSecurityDTO security = firebaseAuthService.getAccountSecurity("creator-uid-10");

        assertNotNull(security);
        assertEquals("creator-uid-10", security.getUserId());
        assertTrue(security.isHasGoogleProvider());
        assertTrue(security.isHasPasswordProvider());
        assertTrue(security.isCanUnlinkGoogle(), "Can unlink Google when password provider also exists");
        assertTrue(security.isCanUnlinkPassword(), "Can unlink password when Google provider also exists");
    }

    @Test
    @DisplayName("Should prevent unlinking Google provider if it is the sole login method")
    void testPreventSoleProviderUnlink() throws Exception {
        UserRecord mockUserRecord = mock(UserRecord.class);
        UserInfo mockGoogleInfo = mock(UserInfo.class);
        when(mockGoogleInfo.getProviderId()).thenReturn("google.com");
        when(mockUserRecord.getProviderData()).thenReturn(new UserInfo[]{mockGoogleInfo});
        when(mockFirebaseAdapter.getUser("sole-google-user")).thenReturn(mockUserRecord);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                firebaseAuthService.unlinkProvider("sole-google-user", "google.com")
        );

        assertTrue(ex.getMessage().contains("only sign-in method"));
    }

    @Test
    @DisplayName("Should update account email in Firebase and DB, preventing collisions")
    void testUpdateAccountEmail() throws Exception {
        userRepository.addUser(new User("user-uid-20", "user20", "User Twenty", "old@example.com", "USER", "https://avatar.png"));
        userRepository.addUser(new User("other-uid-21", "other21", "Other User", "taken@example.com", "USER", "https://avatar.png"));

        // Attempt collision with other user's email
        assertThrows(IllegalArgumentException.class, () ->
                firebaseAuthService.updateAccountEmail("user-uid-20", "taken@example.com")
        );

        when(mockFirebaseAdapter.generateEmailVerificationLink("fresh_email@example.com"))
                .thenReturn("https://auth.buzzclips.app/verify?token=12345");

        String link = firebaseAuthService.updateAccountEmail("user-uid-20", "fresh_email@example.com");

        assertEquals("https://auth.buzzclips.app/verify?token=12345", link);
        assertEquals("fresh_email@example.com", userRepository.findById("user-uid-20").get().getUser_email());
        verify(mockFirebaseAdapter).updateUser(any(UserRecord.UpdateRequest.class));
    }

    @Test
    @DisplayName("Should set password for Google-registered user")
    void testSetAccountPassword() throws Exception {
        firebaseAuthService.setAccountPassword("user-uid-30", "SuperSecret99#");

        ArgumentCaptor<UserRecord.UpdateRequest> captor = ArgumentCaptor.forClass(UserRecord.UpdateRequest.class);
        verify(mockFirebaseAdapter).updateUser(captor.capture());
        assertNotNull(captor.getValue());

        // Validate length constraint
        assertThrows(IllegalArgumentException.class, () ->
                firebaseAuthService.setAccountPassword("user-uid-30", "123")
        );
    }

    /**
     * In-memory stub for UserRepository.
     */
    static class InMemoryUserRepository implements UserRepository {
        private final Map<String, User> users = new HashMap<>();

        public void addUser(User user) {
            users.put(user.getUserId(), user);
        }

        @Override
        public Optional<User> findFirstByUserNameIgnoreCase(String userName) {
            return users.values().stream()
                    .filter(u -> u.getUser_name() != null && u.getUser_name().equalsIgnoreCase(userName))
                    .findFirst();
        }

        @Override
        public Optional<User> findByUserEmailIgnoreCase(String userEmail) {
            return users.values().stream()
                    .filter(u -> u.getUser_email() != null && u.getUser_email().equalsIgnoreCase(userEmail))
                    .findFirst();
        }

        @Override
        public boolean existsByUserNameIgnoreCase(String userName) {
            return users.values().stream()
                    .anyMatch(u -> u.getUser_name() != null && u.getUser_name().equalsIgnoreCase(userName));
        }

        @Override
        public boolean existsByUserNameIgnoreCaseAndUserIdNot(String userName, String userId) {
            return users.values().stream()
                    .anyMatch(u -> u.getUser_name() != null
                            && u.getUser_name().equalsIgnoreCase(userName)
                            && !u.getUserId().equals(userId));
        }

        @Override
        public boolean existsByUserEmailIgnoreCaseAndUserIdNot(String userEmail, String userId) {
            return users.values().stream()
                    .anyMatch(u -> u.getUser_email() != null
                            && u.getUser_email().equalsIgnoreCase(userEmail)
                            && !u.getUserId().equals(userId));
        }

        @Override
        public List<User> searchUsers(String query, Pageable pageable) {
            return users.values().stream()
                    .filter(u -> (u.getUser_name() != null && u.getUser_name().toLowerCase().contains(query.toLowerCase()))
                            || (u.getFullName() != null && u.getFullName().toLowerCase().contains(query.toLowerCase())))
                    .toList();
        }

        @Override
        public List<String> findAllUsernames() {
            return users.values().stream().map(User::getUser_name).filter(Objects::nonNull).toList();
        }

        @Override
        public <S extends User> S save(S entity) {
            users.put(entity.getUserId(), entity);
            return entity;
        }

        @Override
        public Optional<User> findById(String id) {
            return Optional.ofNullable(users.get(id));
        }

        @Override
        public boolean existsById(String id) {
            return users.containsKey(id);
        }

        @Override public List<User> findAll() { return new ArrayList<>(users.values()); }
        @Override public List<User> findAllById(Iterable<String> ids) { return Collections.emptyList(); }
        @Override public long count() { return users.size(); }
        @Override public void deleteById(String id) { users.remove(id); }
        @Override public void delete(User entity) { users.remove(entity.getUserId()); }
        @Override public void deleteAllById(Iterable<? extends String> ids) {}
        @Override public void deleteAll(Iterable<? extends User> entities) {}
        @Override public void deleteAll() { users.clear(); }
        @Override public <S extends User> List<S> saveAll(Iterable<S> entities) { return Collections.emptyList(); }
        @Override public void flush() {}
        @Override public <S extends User> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends User> List<S> saveAllAndFlush(Iterable<S> entities) { return Collections.emptyList(); }
        @Override public void deleteAllInBatch(Iterable<User> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public User getOne(String id) { return users.get(id); }
        @Override public User getById(String id) { return users.get(id); }
        @Override public User getReferenceById(String id) { return users.get(id); }
        @Override public <S extends User> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override public <S extends User> List<S> findAll(Example<S> example) { return Collections.emptyList(); }
        @Override public <S extends User> List<S> findAll(Example<S> example, Sort sort) { return Collections.emptyList(); }
        @Override public <S extends User> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override public <S extends User> long count(Example<S> example) { return 0; }
        @Override public <S extends User> boolean exists(Example<S> example) { return false; }
        @Override public <S extends User, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<User> findAll(Sort sort) { return Collections.emptyList(); }
        @Override public Page<User> findAll(Pageable pageable) { return Page.empty(); }
    }
}
