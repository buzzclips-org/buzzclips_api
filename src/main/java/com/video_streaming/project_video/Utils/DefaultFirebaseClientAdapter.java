package com.video_streaming.project_video.Utils;

import com.google.firebase.auth.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Component
public class DefaultFirebaseClientAdapter implements FirebaseClientAdapter {

    private final FirebaseAuth firebaseAuth;

    public DefaultFirebaseClientAdapter() {
        this.firebaseAuth = null;
    }

    @Autowired
    public DefaultFirebaseClientAdapter(@Autowired(required = false) FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    private FirebaseAuth getAuth() {
        if (this.firebaseAuth != null) {
            return this.firebaseAuth;
        }
        return FirebaseAuth.getInstance();
    }

    @Override
    public FirebaseTokenClaims verifyIdTokenClaims(String idToken) throws FirebaseAuthException {
        FirebaseToken token = getAuth().verifyIdToken(idToken);
        return FirebaseTokenClaims.builder()
                .uid(token.getUid())
                .email(token.getEmail())
                .name(token.getName())
                .picture(token.getPicture())
                .emailVerified(token.isEmailVerified())
                .build();
    }

    @Override
    public FirebaseToken verifyIdToken(String idToken) throws FirebaseAuthException {
        return getAuth().verifyIdToken(idToken);
    }

    @Override
    public UserRecord getUser(String uid) throws FirebaseAuthException {
        return getAuth().getUser(uid);
    }

    @Override
    public void updateUser(UserRecord.UpdateRequest request) throws FirebaseAuthException {
        getAuth().updateUser(request);
    }

    @Override
    public String generateEmailVerificationLink(String email) throws FirebaseAuthException {
        return getAuth().generateEmailVerificationLink(email);
    }

    @Override
    public List<String> getProviderIds(String uid) throws FirebaseAuthException {
        UserRecord record = getUser(uid);
        if (record == null || record.getProviderData() == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(record.getProviderData())
                .map(UserInfo::getProviderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
