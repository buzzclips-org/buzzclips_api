# 🎨 Frontend Integration Guide: User Profile, Handle System & Bloom Search

This guide provides everything the frontend team needs to consume the updated **BuzzClips Backend API** (`https://buzzclips.app/api`).

---

## 1. Core Architecture Changes

| Feature | **Full Name / Display Name** (`fullName`) | **Username / Handle** (`username` / `@handle`) |
| :--- | :--- | :--- |
| **Purpose** | Human-readable creator name shown on videos, comments, and channel headers. | Unique handle for `@mentions`, profile routing (`/channel/@mkbhd`), and login. |
| **Uniqueness** | Non-unique (multiple creators can share names like "John Doe"). | **Strictly unique** (case-insensitive across DB & Bloom filter). |
| **Formatting** | Up to 100 characters; supports spaces, unicode, international letters. | `^[a-zA-Z0-9_]{3,30}$` (alphanumeric + underscores only, no spaces). |
| **Disallowed** | None (trimmed & sanitized). | Reserved handles (`admin`, `buzzclips`, `api`, `support`, etc.). |
| **Fallback** | Defaults to `username` if empty. | Automatically strips leading `@` (e.g. `@johndoe` $\rightarrow$ `johndoe`). |

---

## 2. API Endpoints Reference

### 1. Check Username Availability (Bloom Filter Fast-Path)
- **Endpoint**: `GET /api/app/check-username?username={handle}`
- **Latency**: Sub-millisecond ($O(1)$ Bloom filter check in memory/Redis).
- **Response**:
```json
{
  "available": true,
  "valid": true
}
```
> [!TIP]
> If `valid: false`, the handle failed regex (`^[a-zA-Z0-9_]{3,30}$`) or is a reserved handle.
> If `available: false`, the username is already taken.

---

### 2. Register New User
- **Endpoint**: `POST /api/app/createUser`
- **Content-Type**: `application/x-www-form-urlencoded`
- **Parameters**:
  - `email` (string, required)
  - `password` (string, required)
  - `username` (string, required - e.g. `mkbhd` or `@mkbhd`)
  - `fullName` (string, optional - e.g. `Marques Brownlee`. Defaults to `username` if omitted)
  - `avatar_url` (string, optional)
- **Response**: `200 OK` with string `"User created successfully!"`

---

### 3. Login (Multi-Identifier Support)
- **Endpoint**: `POST /api/app/login`
- **Content-Type**: `application/x-www-form-urlencoded`
- **Parameters**:
  - `identifier` (string, required - can be an email, plain username, or `@handle`)
  - `password` (string, required)
- **Response**: `FirebaseSignInResponse` + sets `token` HTTP-only cookie.

---

### 4. Firebase Google Sign-In & One-Tap Sync
- **Endpoint**: `POST /api/app/google-login` (also aliased to `POST /api/app/auth/google`)
- **Content-Type**: `application/json` (or `application/x-www-form-urlencoded`)
- **Payload**:
```json
{
  "idToken": "firebase_id_token_from_google_auth",
  "username": "optional_preferred_handle",
  "fullName": "optional_display_name_override"
}
```
- **Behavior**:
  - Validates the Firebase ID token cryptographically.
  - If user is new: Automatically generates a clean, unique, collision-resistant handle (e.g. `@johndoe`), sets avatar from Google profile photo, and registers them in PostgreSQL + Bloom filter. Returns `isNewUser: true`.
  - If user already exists: Synchronizes avatar/display name/email updates. Returns `isNewUser: false`.
  - Sets secure HTTP-only session cookie (`token`).
- **Response**:
```json
{
  "idToken": "ey...",
  "isNewUser": true,
  "primaryProvider": "google.com",
  "providers": ["google.com"],
  "emailVerified": true,
  "user": {
    "userId": "google_firebase_uid_123",
    "username": "johndoe",
    "fullName": "John Doe",
    "user_email": "johndoe@gmail.com",
    "avatar_url": "https://lh3.googleusercontent.com/...",
    "user_role": "USER"
  }
}
```

---

### 5. Get User Profile by ID or Handle

- **Endpoint**: `GET /api/user/getUser/{identifier}`
- **Parameters**: `{identifier}` can be either a Firebase UID (`uid-123`), plain username (`mkbhd`), or `@handle` (`@mkbhd`).
- **Response**:
```json
{
  "userId": "firebase_uid_123",
  "username": "mkbhd",
  "fullName": "Marques Brownlee",
  "user_email": "marques@example.com",
  "avatar_url": "https://s3.amazonaws.com/bucket/avatars/mkbhd.jpg",
  "user_role": "USER",
  "user_name": "mkbhd",
  "full_name": "Marques Brownlee"
}
```

---

### 5. Update User Profile
- **Endpoint**: `POST /api/user/update`
- **Content-Type**: `application/json` (or `multipart/form-data`)
- **Body**:
```json
{
  "userId": "firebase_uid_123",
  "fullName": "Marques K. Brownlee",
  "username": "mkbhd",
  "avatar_url": "https://..."
}
```
> [!NOTE]
> `fullName` can be updated freely at any time. If `username` is changed, the backend runs syntax and collision checks against all other users before saving.

---

### 6. Search Users / Creators
- **Endpoint**: `GET /api/user/search?q={query}&limit={10}`
- **Matches**: Searches both `user_name` (handles) and `full_name` (display names).
- **Response**: `UserDTO[]`

---

### 7. Account Security & Provider Management (Google + Email)

All endpoints below require authentication (Bearer token or `token` cookie):

#### A. Get Account Security & Linked Providers
- **Endpoint**: `GET /api/user/account` (or `GET /api/user/account/providers`)
- **Response**:
```json
{
  "userId": "google_123",
  "email": "creator@buzzclips.app",
  "emailVerified": true,
  "username": "creator",
  "fullName": "Chief Creator",
  "avatarUrl": "https://lh3.googleusercontent.com/...",
  "providers": [
    {
      "providerId": "google.com",
      "uid": "10492839210",
      "email": "creator@buzzclips.app",
      "displayName": "Chief Creator",
      "photoUrl": "https://..."
    },
    {
      "providerId": "password",
      "uid": "creator@buzzclips.app",
      "email": "creator@buzzclips.app"
    }
  ],
  "hasGoogleProvider": true,
  "hasPasswordProvider": true,
  "canUnlinkGoogle": true,
  "canUnlinkPassword": true,
  "creationTimestamp": 1729000000000,
  "lastSignInTimestamp": 1729900000000
}
```

#### B. Update Primary Account Email
- **Endpoint**: `POST /api/user/account/email`
- **Body**: `{ "newEmail": "new_email@example.com" }`
- **Behavior**: Verifies email is not taken by another user, updates Firebase Auth + PostgreSQL, and generates an email verification link.

#### C. Set / Update Password (For Google Users)
- **Endpoint**: `POST /api/user/account/password`
- **Body**: `{ "newPassword": "MySecurePassword123!" }`
- **Behavior**: Enables email/password login for users who originally registered via Google Sign-In.

#### D. Unlink Google Provider
- **Endpoint**: `POST /api/user/account/providers/google/unlink`
- **Safety**: Rejects with HTTP 400 if Google is the user's sole login method to prevent accidental account lockout!

#### E. Link Google Provider
- **Endpoint**: `POST /api/user/account/providers/google/link`
- **Body**: `{ "idToken": "google_firebase_id_token" }`
- **Behavior**: Connects Google authentication to an existing password-authenticated account.

#### F. Generate Verification Email Link
- **Endpoint**: `POST /api/user/account/verify-email`
- **Response**: `{ "success": true, "actionLink": "https://..." }`

---

## 3. TypeScript Interfaces

Copy and paste these into your frontend `types/user.ts`:


```typescript
export interface UserDTO {
  userId: string;
  /** Unique handle for @mentions, profile routes, and URLs (e.g., "johndoe") */
  username: string;
  /** Real / display name shown prominently on videos, channel, comments (e.g., "John Doe") */
  fullName: string;
  user_email?: string;
  avatar_url?: string;
  user_role?: string;
  /** Backward-compatible aliases */
  user_name?: string;
  full_name?: string;
}

export interface UsernameCheckResult {
  available: boolean;
  valid: boolean;
}

export interface CreateUserPayload {
  email: string;
  password: string;
  username: string;
  fullName?: string;
  avatar_url?: string;
}

export interface UpdateUserProfilePayload {
  userId: string;
  fullName?: string;
  username?: string;
  avatar_url?: string;
}

export interface WatchProgressDTO {
  videoId: number;
  videoTitle: string;
  slug: string;
  thumbnailUrl?: string;
  /** Channel display name (Full Name) */
  channelName?: string;
  /** Channel unique handle (@username) */
  channelHandle?: string;
  channelAvatar?: string;
  progressSeconds: number;
  durationSeconds?: number;
  completed: boolean;
  lastWatchedAt: string;
}
```

---

## 4. Copy-Pasteable React Hook: `useUsernameAvailability`

This hook provides instant client-side validation, debouncing, and sub-millisecond Bloom filter verification.

```tsx
import { useState, useEffect, useRef } from 'react';

const USERNAME_REGEX = /^[a-zA-Z0-9_]{3,30}$/;

const RESERVED_HANDLES = new Set([
  'admin', 'administrator', 'root', 'system', 'support', 'help',
  'buzzclips', 'bideo', 'api', 'auth', 'login', 'logout', 'signup',
  'explore', 'trending', 'subscriptions', 'playlists', 'feed',
  'channel', 'video', 'videos', 'embed', 'settings', 'profile',
  'null', 'undefined', 'anonymous', 'moderator'
]);

export function cleanHandle(input: string): string {
  if (!input) return '';
  let s = input.trim();
  if (s.startsWith('@')) s = s.substring(1).trim();
  return s;
}

export type UsernameStatus = 'idle' | 'checking' | 'available' | 'taken' | 'invalid';

export function useUsernameAvailability(initialValue = '', debounceMs = 300) {
  const [username, setUsername] = useState(initialValue);
  const [status, setStatus] = useState<UsernameStatus>('idle');
  const [message, setMessage] = useState('');
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    const handle = cleanHandle(username);

    if (!handle) {
      setStatus('idle');
      setMessage('');
      return;
    }

    // 1. Instant local validation
    if (handle.length < 3 || handle.length > 30) {
      setStatus('invalid');
      setMessage('Handle must be 3-30 characters');
      return;
    }

    if (!USERNAME_REGEX.test(handle)) {
      setStatus('invalid');
      setMessage('Letters, numbers, and underscores only');
      return;
    }

    if (RESERVED_HANDLES.has(handle.toLowerCase())) {
      setStatus('invalid');
      setMessage(`@${handle} is reserved`);
      return;
    }

    // 2. Debounced Bloom Filter verification
    setStatus('checking');
    setMessage('Checking availability...');

    if (timeoutRef.current) clearTimeout(timeoutRef.current);

    timeoutRef.current = setTimeout(async () => {
      try {
        const res = await fetch(`https://buzzclips.app/api/app/check-username?username=${encodeURIComponent(handle)}`);
        const data = await res.json();

        if (!data.valid) {
          setStatus('invalid');
          setMessage('Invalid handle format');
        } else if (data.available) {
          setStatus('available');
          setMessage(`@${handle} is available!`);
        } else {
          setStatus('taken');
          setMessage(`@${handle} is already taken`);
        }
      } catch (err) {
        setStatus('idle');
        setMessage('Could not verify username');
      }
    }, debounceMs);

    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, [username, debounceMs]);

  return {
    username,
    setUsername,
    status,
    message,
    isAvailable: status === 'available',
    isChecking: status === 'checking',
    isValid: status !== 'invalid',
  };
}
```

---

## 5. UI Component Snippets

### A. SignUp Form with Live Availability Indicator
```tsx
import React, { useState } from 'react';
import { useUsernameAvailability, cleanHandle } from './useUsernameAvailability';

export const SignUpForm: React.FC = () => {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const { username, setUsername, status, message, isAvailable } = useUsernameAvailability();

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isAvailable) return;

    const params = new URLSearchParams();
    params.append('email', email);
    params.append('password', password);
    params.append('username', cleanHandle(username));
    params.append('fullName', fullName.trim() || cleanHandle(username));

    const res = await fetch('https://buzzclips.app/api/app/createUser', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params.toString(),
    });

    if (res.ok) {
      alert('Account created! Please log in.');
    } else {
      const err = await res.text();
      alert(`Error: ${err}`);
    }
  };

  return (
    <form onSubmit={handleRegister} className="signup-form">
      {/* 1. Full Display Name */}
      <div className="input-group">
        <label>Full Name / Channel Name</label>
        <input
          type="text"
          placeholder="e.g. Marques Brownlee"
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
        />
        <small>This is your public display name shown on your channel and videos.</small>
      </div>

      {/* 2. Unique Handle */}
      <div className="input-group">
        <label>Unique Handle</label>
        <div className="handle-input-wrapper">
          <span className="prefix">@</span>
          <input
            type="text"
            placeholder="mkbhd"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </div>
        {status !== 'idle' && (
          <span className={`status-badge ${status}`}>
            {status === 'checking' && '⏳ '}
            {status === 'available' && '✅ '}
            {status === 'taken' && '❌ '}
            {status === 'invalid' && '⚠️ '}
            {message}
          </span>
        )}
      </div>

      {/* 3. Email & Password */}
      <div className="input-group">
        <label>Email</label>
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </div>

      <div className="input-group">
        <label>Password</label>
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </div>

      <button type="submit" disabled={!isAvailable}>
        Create Account
      </button>
    </form>
  );
};
```

---

### B. User Handle Badge Component
Render on video cards, channel headers, and comment sections:

```tsx
import React from 'react';
import { UserDTO } from './types';

export const UserHandleBadge: React.FC<{ user?: UserDTO }> = ({ user }) => {
  if (!user) return <span>Anonymous</span>;

  const displayName = user.fullName || user.user_name || 'Creator';
  const handle = user.username ? `@${user.username}` : '';

  return (
    <div className="user-badge" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
      <img
        src={user.avatar_url || 'https://buzzclips.app/default-thumbnail.jpg'}
        alt={displayName}
        style={{ width: 28, height: 28, borderRadius: '50%' }}
      />
      <span style={{ fontWeight: 600 }}>{displayName}</span>
      {handle && (
        <span style={{ color: '#888', fontSize: '0.85em' }}>
          {handle}
        </span>
      )}
    </div>
  );
};
```

---

## 6. Migration Checklist for Frontend Devs

- [x] **Registration Modal**: Add separate inputs for `Full Name` and `Handle` (`@username`).
- [x] **Live Availability**: Hook the handle input to `useUsernameAvailability` to give users instant visual feedback.
- [x] **Login Form**: Change input label from `"Email"` to `"Email, Username, or @handle"`.
- [x] **Profile / Settings Page**: Let creators edit their `fullName` freely, while validating new `username` changes.
- [x] **Video & Comment Headers**: Display `user.fullName` as the primary title and `user.username` (`@handle`) as the secondary sub-badge.
- [x] **Channel Routing**: Route channels via `/channel/@${username}` (the backend resolves both `@username` and Firebase UIDs in `/api/user/getUser/{id}`).

---

## 7. Phase 4: Direct-to-S3 Uploads, ABR Streaming & Scrubbing Spritesheets

### 1. Direct-to-S3 Presigned Upload (Bypasses 40MB Multipart Limit)
Upload multi-gigabyte 4K/1080p video files directly from browser to AWS S3 using secure presigned PUT URLs with unified process lifecycle tracking:

#### Step 1: Request S3 Presigned URL & Process ID
- **Endpoint**: `POST /api/vid/upload/presigned` (requires auth)
- **Request Body**:
```json
{
  "fileName": "japan_travel_4k.mp4",
  "contentType": "video/mp4"
}
```
- **Response**:
```json
{
  "uploadUrl": "https://bideo-tech-uploads.s3.ap-south-1.amazonaws.com/uploads/user_123/uuid_japan_travel_4k.mp4?X-Amz-Algorithm=...",
  "s3Key": "uploads/user_123/uuid_japan_travel_4k.mp4",
  "publicS3Url": "https://bideo-tech-uploads.s3.ap-south-1.amazonaws.com/uploads/user_123/uuid_japan_travel_4k.mp4",
  "processId": "proc_8f14b67cd9e24a1b",
  "expiresInSeconds": 3600
}
```

> [!IMPORTANT]
> **AWS S3 Bucket CORS Requirement**:
> AWS S3 must have a CORS rule permitting `https://buzzclips.app` to execute `PUT` requests directly from the browser. In the **AWS S3 Console** $\rightarrow$ `bideo-tech-uploads` $\rightarrow$ **Permissions** $\rightarrow$ **Cross-origin resource sharing (CORS)**, save:
> ```json
> [
>   {
>     "AllowedHeaders": ["*"],
>     "AllowedMethods": ["GET", "PUT", "POST", "HEAD", "DELETE"],
>     "AllowedOrigins": ["https://buzzclips.app", "https://www.buzzclips.app", "http://localhost:3000"],
>     "ExposeHeaders": ["ETag"],
>     "MaxAgeSeconds": 3600
>   }
> ]
> ```

#### Step 2: PUT Video Directly from Browser to S3 (With Fallback)
```typescript
try {
  await axios.put(uploadUrl, file, {
    headers: { 'Content-Type': file.type || 'video/mp4' },
    onUploadProgress: (progressEvent) => {
      const percent = Math.round((progressEvent.loaded * 100) / (progressEvent.total || 1));
      setUploadProgress(percent);
    }
  });
} catch (error) {
  // If browser S3 direct upload failed (e.g. CORS or network drop), DO NOT call completePresignedUpload!
  // Abort the process and notify the user:
  await axios.post(`/api/vid/upload/process/${processId}/abort`);
  throw new Error('S3 Direct upload failed. Please check S3 CORS or network.');
}
```

#### Step 3: Complete Presigned Upload & Queue Transcoding
- **Endpoint**: `POST /api/vid/upload/complete-presigned` (requires auth)
- **Request Body**:
```json
{
  "s3Key": "uploads/user_123/uuid_japan_travel_4k.mp4",
  "processId": "proc_8f14b67cd9e24a1b",
  "videoTitle": "Tokyo at Night 4K HDR",
  "description": "Cinematic walking tour through Shinjuku.",
  "tags": ["travel", "japan", "4k"],
  "category": "TRAVEL",
  "visibility": "PUBLIC"
}
```
- **Response**:
```json
{
  "videoId": 101,
  "message": "Presigned upload completed and queued for multi-rendition transcoding"
}
```
*(Note: The backend now verifies that the S3 file actually exists on S3 before registering or queueing. If the file was never uploaded to S3, this call will safely return HTTP 400 instead of creating a stuck/orphaned video.)*

#### Step 4: Track Progress via Process ID or Video ID
- **SSE Stream**: `GET /api/vid/upload/process/{processId}/progress` (or `GET /api/vid/{videoId}/transcode-progress`)
- **Status Poll**: `GET /api/vid/upload/process/{processId}/status` (or `GET /api/vid/{videoId}/status`)
- **Abort Process**: `POST /api/vid/upload/process/{processId}/abort`

---

### 2. Real-Time Transcoding Progress via Server-Sent Events (SSE)
Subscribe to live FFmpeg transcoding updates:
- **Endpoint**: `GET /api/vid/{videoId}/transcode-progress`
- **Format**: `text/event-stream`
- **Events**:
```json
{
  "videoId": 101,
  "stage": "ENCODING_ABR_HLS",
  "percent": 50,
  "status": "PROCESSING"
}
```
Stages emitted: `PREPARING (10%)` $\rightarrow$ `ENCODING_BASE (25%)` $\rightarrow$ `ENCODING_ABR_HLS (50%)` $\rightarrow$ `GENERATING_STORYBOARD (75%)` $\rightarrow$ `UPLOADING_S3 (88%)` $\rightarrow$ `COMPLETED (100%)`.

#### React Hook Example:
```typescript
export function useTranscodeProgress(videoId: number | null) {
  const [progress, setProgress] = useState<{ stage: string; percent: number; status: string } | null>(null);

  useEffect(() => {
    if (!videoId) return;
    const eventSource = new EventSource(`/api/vid/${videoId}/transcode-progress`);
    eventSource.addEventListener('progress', (e) => {
      const data = JSON.parse(e.data);
      setProgress(data);
      if (data.status === 'COMPLETED' || data.status === 'FAILED') {
        eventSource.close();
      }
    });
    return () => eventSource.close();
  }, [videoId]);

  return progress;
}
```

---

### 3. Multi-Rendition Adaptive Bitrate (ABR) HLS
The transcoder generates an HLS ladder with `master.m3u8` containing:
- `720p` (1280x720 @ 2500kbps)
- `480p` (854x480 @ 1200kbps)
- `360p` (640x360 @ 600kbps)

Pass `video.m3u8Url` directly into `hls.js` or standard video player; the player automatically shifts bitrate based on user connection speed.

---

### 4. Hover Scrubbing Thumbnails (WebVTT + Spritesheet)
Each completed video includes:
- `storyboard_url`: Single WebP spritesheet (5x5 grid, 160x90 per thumbnail).
- `vtt_url`: WebVTT cue file mapping time segments to `#xywh=` sprite coordinates.

#### Video.js / Plyr Integration:
```javascript
// Video.js vtt-thumbnails plugin:
player.vttThumbnails({
  src: video.vtt_url
});
```

