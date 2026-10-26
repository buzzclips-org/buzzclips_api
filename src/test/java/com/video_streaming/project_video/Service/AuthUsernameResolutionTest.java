package com.video_streaming.project_video.Service;

import com.video_streaming.project_video.Entity.User;
import com.video_streaming.project_video.Repository.UserRepository;
import com.video_streaming.project_video.ServiceImplementation.FirebaseAuthServiceImpl;
import com.video_streaming.project_video.ServiceImplementation.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AuthUsernameResolutionTest {

    private InMemoryUserRepository userRepository;
    private UserServiceImpl userService;
    private FirebaseAuthServiceImpl firebaseAuthService;

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        userService = new UserServiceImpl(null, userRepository);
        firebaseAuthService = new FirebaseAuthServiceImpl(userRepository, "test-api-key");
    }

    @Test
    @DisplayName("Should return raw email when identifier contains '@'")
    void testResolveEmail_withEmail() {
        String email = "john.doe@example.com";
        String resolved = userService.resolveEmailFromIdentifier(email);

        assertEquals(email, resolved);
    }

    @Test
    @DisplayName("Should resolve username to email from repository (case-insensitive)")
    void testResolveEmail_withUsername() {
        userRepository.addUser(new User("uid-123", "johndoe", "john.doe@example.com", "USER", "avatar.png"));

        String resolved = userService.resolveEmailFromIdentifier("JohnDoe");

        assertEquals("john.doe@example.com", resolved);
    }

    @Test
    @DisplayName("Should return raw username if not found in repository")
    void testResolveEmail_withNonExistentUsername() {
        String resolved = userService.resolveEmailFromIdentifier("unknown_user");

        assertEquals("unknown_user", resolved);
    }

    @Test
    @DisplayName("Should report username availability correctly")
    void testIsUsernameAvailable() {
        userRepository.addUser(new User("uid-123", "taken_user", "taken@example.com", "USER", "avatar.png"));

        assertFalse(userService.isUsernameAvailable("taken_user"));
        assertFalse(userService.isUsernameAvailable("TAKEN_USER"));
        assertTrue(userService.isUsernameAvailable("free_user"));
        assertFalse(userService.isUsernameAvailable(""));
        assertFalse(userService.isUsernameAvailable(null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when creating user with duplicate username")
    void testCreateUser_withDuplicateUsername() {
        userRepository.addUser(new User("uid-123", "duplicate_user", "dup@example.com", "USER", "avatar.png"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                userService.create("new@example.com", "password123", "duplicate_user", null)
        );

        assertTrue(ex.getMessage().contains("already taken"));
    }

    @Test
    @DisplayName("Should resolve @username handle to email")
    void testResolveEmail_withAtHandle() {
        userRepository.addUser(new User("uid-123", "johndoe", "john.doe@example.com", "USER", "avatar.png"));

        String resolved = userService.resolveEmailFromIdentifier("@johndoe");

        assertEquals("john.doe@example.com", resolved);
    }

    @Test
    @DisplayName("Should reject invalid username formats and reserved handles")
    void testUsernameFormatValidation() {
        assertFalse(userService.isUsernameValid("ab")); // too short
        assertFalse(userService.isUsernameValid("user name with spaces")); // spaces
        assertFalse(userService.isUsernameValid("user!@#$")); // special chars
        assertFalse(userService.isUsernameValid("admin")); // reserved
        assertFalse(userService.isUsernameValid("api")); // reserved
        assertFalse(userService.isUsernameValid("buzzclips")); // reserved

        assertTrue(userService.isUsernameValid("valid_user_123"));
        assertTrue(userService.isUsernameValid("@valid_handle"));
    }

    @Test
    @DisplayName("Should support full name and username distinction")
    void testFullNameAndUsernameDistinction() {
        User user = new User("uid-999", "alex_tech", "Alexandre Dumas", "alex@test.com", "USER", "avatar.png");
        userRepository.addUser(user);

        assertEquals("alex_tech", user.getUsername());
        assertEquals("Alexandre Dumas", user.getFullName());

        com.video_streaming.project_video.DTOs.UserDTO dto = userService.getUserById("alex_tech");
        assertEquals("alex_tech", dto.getUsername());
        assertEquals("Alexandre Dumas", dto.getFullName());

        // Also finds by @handle
        com.video_streaming.project_video.DTOs.UserDTO dtoAt = userService.getUserById("@alex_tech");
        assertEquals("alex_tech", dtoAt.getUsername());

        // Search by handle or full name
        List<com.video_streaming.project_video.DTOs.UserDTO> byHandle = userService.searchUsers("alex_tech", 10);
        assertFalse(byHandle.isEmpty());

        List<com.video_streaming.project_video.DTOs.UserDTO> byName = userService.searchUsers("Dumas", 10);
        assertFalse(byName.isEmpty());
    }

    /**
     * In-memory stub for UserRepository to avoid bytecode agent issues across JDK 21/24.
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
            return users.values().stream()
                    .map(User::getUser_name)
                    .filter(Objects::nonNull)
                    .toList();
        }

        @Override
        public void flush() {}

        @Override
        public <S extends User> S saveAndFlush(S entity) { return entity; }

        @Override
        public <S extends User> List<S> saveAllAndFlush(Iterable<S> entities) { return Collections.emptyList(); }

        @Override
        public void deleteAllInBatch(Iterable<User> entities) {}

        @Override
        public void deleteAllByIdInBatch(Iterable<String> strings) {}

        @Override
        public void deleteAllInBatch() {}

        @Override
        public User getOne(String s) { return users.get(s); }

        @Override
        public User getById(String s) { return users.get(s); }

        @Override
        public User getReferenceById(String s) { return users.get(s); }

        @Override
        public <S extends User> Optional<S> findOne(Example<S> example) { return Optional.empty(); }

        @Override
        public <S extends User> List<S> findAll(Example<S> example) { return Collections.emptyList(); }

        @Override
        public <S extends User> List<S> findAll(Example<S> example, Sort sort) { return Collections.emptyList(); }

        @Override
        public <S extends User> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }

        @Override
        public <S extends User> long count(Example<S> example) { return 0; }

        @Override
        public <S extends User> boolean exists(Example<S> example) { return false; }

        @Override
        public <S extends User, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }

        @Override
        public <S extends User> S save(S entity) {
            users.put(entity.getUserId(), entity);
            return entity;
        }

        @Override
        public <S extends User> List<S> saveAll(Iterable<S> entities) {
            entities.forEach(this::save);
            return (List<S>) entities;
        }

        @Override
        public Optional<User> findById(String s) { return Optional.ofNullable(users.get(s)); }

        @Override
        public boolean existsById(String s) { return users.containsKey(s); }

        @Override
        public List<User> findAll() { return new ArrayList<>(users.values()); }

        @Override
        public List<User> findAllById(Iterable<String> strings) { return Collections.emptyList(); }

        @Override
        public long count() { return users.size(); }

        @Override
        public void deleteById(String s) { users.remove(s); }

        @Override
        public void delete(User entity) { users.remove(entity.getUserId()); }

        @Override
        public void deleteAllById(Iterable<? extends String> strings) {}

        @Override
        public void deleteAll(Iterable<? extends User> entities) {}

        @Override
        public void deleteAll() { users.clear(); }

        @Override
        public List<User> findAll(Sort sort) { return findAll(); }

        @Override
        public Page<User> findAll(Pageable pageable) { return Page.empty(); }
    }
}
