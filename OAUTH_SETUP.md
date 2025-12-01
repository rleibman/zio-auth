# OAuth Setup and Local Testing Guide

This guide explains how to set up and test Google OAuth authentication in DMScreen, including local development.

## Overview

DMScreen uses [zio-auth](https://github.com/rleibman/zio-auth) v3.0.0+ for authentication, which provides:
- Email/password authentication with email verification
- OAuth 2.0 support (Google, extensible to other providers)
- JWT-based session management
- Auto-account linking (OAuth accounts automatically link to existing email accounts)

## Google OAuth for Local Development

**Good news**: Google OAuth supports `localhost` redirects for development. You don't need a public URL or tunneling service for local testing!

## Step 1: Configure Google Cloud Console

### Create OAuth 2.0 Credentials

1. **Navigate to Google Cloud Console**: https://console.cloud.google.com/

2. **Create or Select a Project**:
   - Click the project dropdown at the top
   - Create a new project or select an existing one
   - Project name suggestion: "DMScreen Development"

3. **Configure OAuth Consent Screen**:
   - Navigate to: **APIs & Services** → **OAuth consent screen**
   - User Type: Select **"External"** (for testing with any Google account)
   - Click **"Create"**

   **Fill in required fields**:
   - App name: `DMScreen Local Dev` (or your preference)
   - User support email: Your email address
   - Developer contact information: Your email address
   - Click **"Save and Continue"**

   **Scopes** (Step 2):
   - Click **"Add or Remove Scopes"**
   - Add these scopes:
     - `openid`
     - `email`
     - `profile`
   - Click **"Update"** → **"Save and Continue"**

   **Test users** (Step 3):
   - Add your Google email address as a test user
   - Click **"Save and Continue"**

   **Summary** (Step 4):
   - Review and click **"Back to Dashboard"**

4. **Create OAuth Client ID**:
   - Navigate to: **APIs & Services** → **Credentials**
   - Click **"+ Create Credentials"** → **"OAuth client ID"**

   **Configure the client**:
   - Application type: **Web application**
   - Name: `DMScreen Local Dev`

   **Authorized redirect URIs**:
   - Click **"+ Add URI"**
   - Enter: `http://localhost:8078/oauth/google/callback`
   - **Important**: No trailing slash, exact port number, use `http://` (not `https://`)

   - Click **"Create"**

5. **Save Your Credentials**:
   - A dialog appears with your **Client ID** and **Client Secret**
   - **Copy both values** - you'll need them in the next step
   - You can always retrieve them later from the Credentials page

## Step 2: Configure DMScreen Environment Variables

### Update Your .env File

Add the following to your `.env` file (in the project root):

```bash
# Google OAuth Configuration
DMSCREEN_OAUTH_GOOGLE_CLIENT_ID="123456789-abcdefghijklmnop.apps.googleusercontent.com"
DMSCREEN_OAUTH_GOOGLE_CLIENT_SECRET="GOCSPX-abc123def456ghi789"
DMSCREEN_OAUTH_GOOGLE_REDIRECT_URI="http://localhost:8078/oauth/google/callback"
```

**Replace the placeholder values** with your actual Client ID and Client Secret from Step 1.

### Verify application.conf

The configuration should already be set up in `server/src/main/resources/application.conf`:

```hocon
dmscreen {
  oauth {
    google {
      clientId = ${DMSCREEN_OAUTH_GOOGLE_CLIENT_ID}
      clientSecret = ${DMSCREEN_OAUTH_GOOGLE_CLIENT_SECRET}
      authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth"
      tokenUri = "https://oauth2.googleapis.com/token"
      userInfoUri = "https://www.googleapis.com/oauth2/v2/userinfo"
      redirectUri = ${DMSCREEN_OAUTH_GOOGLE_REDIRECT_URI}
      scopes = ["openid", "email", "profile"]
    }
  }
}
```

## Step 3: Database Migration

The OAuth support requires database schema changes (added in migration V016):

```sql
ALTER TABLE `dmscreenUser`
  ADD COLUMN `oauthProvider` VARCHAR(50) NULL DEFAULT NULL,
  ADD COLUMN `oauthProviderId` VARCHAR(255) NULL DEFAULT NULL,
  ADD COLUMN `oauthProviderData` JSON NULL DEFAULT NULL;

CREATE UNIQUE INDEX `idx_oauth_provider_id`
  ON `dmscreenUser` (`oauthProvider`, `oauthProviderId`);
```

**This migration runs automatically** when you start the server (via Flyway).

If you need to run migrations manually:
```bash
# Migrations are in: server/src/main/sql/V016__oauth_support.sql
# They run automatically on server startup
```

## Step 4: Build and Start the Application

### Terminal 1: Build Frontend

```bash
cd /home/rleibman/projects/dmscreen

# Start SBT
sbt

# Build frontend (debug mode with source maps)
debugDist

# Wait for build to complete
# Output: [success] Total time: ...
```

### Terminal 2: Start Backend Server

```bash
cd /home/rleibman/projects/dmscreen

# Start SBT
sbt

# Switch to server project and start with hot reload
project server
~reStart

# Server will start on http://localhost:8078
# Wait for: "Server Started on 8078"
```

## Step 5: Test the OAuth Flow

### Access the Application

1. Open your browser and navigate to: **http://localhost:8078**

2. You should see the login page with:
   - Email/password form
   - **"Continue with Google"** button

### Test OAuth Login

1. **Click "Continue with Google"** button

2. **Browser redirects** to: `http://localhost:8078/oauth/google/login`

3. **Server redirects** to Google's authorization page: `https://accounts.google.com/o/oauth2/v2/auth?...`

4. **Google shows consent screen**:
   - App name: "DMScreen Local Dev"
   - Permissions requested: email, profile, openid
   - Click **"Continue"** or **"Allow"**

5. **Google redirects back** to: `http://localhost:8078/oauth/google/callback?code=...&state=...`

6. **Server processes callback**:
   - Validates CSRF state token
   - Exchanges authorization code for access token
   - Fetches user info from Google
   - Finds existing user by email OR creates new user
   - Creates JWT session
   - Redirects to main application

7. **You should now be logged in** and see the DMScreen main interface

### What Happens Behind the Scenes

**First-time OAuth login**:
- User doesn't exist → new user created with OAuth data
- User auto-activated (email is verified by Google)
- JWT tokens issued (access token + refresh token)

**OAuth login with existing email account**:
- User exists with same email → OAuth data linked to existing account
- Existing user can now log in via email/password OR OAuth

**Returning OAuth user**:
- User found by OAuth provider + provider ID
- JWT tokens issued
- User logged in

## Step 6: Verify in Database

Check that the OAuth user was created:

```sql
-- Connect to your database
mysql -h localhost -u your_user -p dmscreen

-- View OAuth users
SELECT
  id,
  email,
  name,
  active,
  oauthProvider,
  oauthProviderId,
  created
FROM dmscreenUser
WHERE oauthProvider IS NOT NULL;
```

You should see your Google account with:
- `oauthProvider`: `"google"`
- `oauthProviderId`: Your Google user ID (stable identifier)
- `oauthProviderData`: JSON with full Google user info
- `active`: `1` (auto-activated)

## Troubleshooting

### Error: "redirect_uri_mismatch"

**Problem**: The redirect URI doesn't match Google Console configuration

**Solution**:
1. Check Google Console → Credentials → Your OAuth Client
2. Verify "Authorized redirect URIs" contains EXACTLY:
   - `http://localhost:8078/oauth/google/callback`
3. No trailing slash, correct port, use `http://` not `https://`
4. After changing, wait a few minutes for Google to propagate changes

### Error: "Access blocked: This app's request is invalid"

**Problem**: OAuth consent screen not properly configured

**Solution**:
1. Go to Google Console → APIs & Services → OAuth consent screen
2. Ensure status is not "Draft" or "Needs verification"
3. Add required scopes: `openid`, `email`, `profile`
4. Add your email as a test user
5. Publish the consent screen (for testing, "External" is fine)

### Error: "invalid_client" or "unauthorized_client"

**Problem**: Client ID or Client Secret is incorrect

**Solution**:
1. Verify your `.env` file has correct values from Google Console
2. Check for extra spaces or quotes in environment variables
3. Restart the server after changing `.env`
4. Verify environment variables are loaded:
   ```bash
   # In sbt shell
   show server/envVars
   ```

### HTTPS/SSL Errors

**Problem**: Browser trying to use HTTPS for localhost

**Solution**:
- Google OAuth supports HTTP for `localhost` only
- Ensure you're accessing `http://localhost:8078` (not `https://`)
- Clear browser cache if it's forcing HTTPS redirect

### State Token Validation Failed

**Problem**: CSRF state token expired or doesn't match

**Solution**:
- State tokens expire after 10 minutes
- Don't bookmark or reuse OAuth callback URLs
- Start fresh from the login page
- If persistent, check server logs for state token issues

### User Created But Not Activated

**Problem**: OAuth user exists but `active = 0`

**Solution**:
- OAuth users should be auto-activated
- Check `DMScreenAuthServer.scala:139` - should set `active = oauthInfo.emailVerified`
- Google always returns `emailVerified = true`
- Manually activate in database if needed:
  ```sql
  UPDATE dmscreenUser SET active = 1 WHERE email = 'your@email.com';
  ```

### Server Logs Show Errors

Check the server console for detailed error messages:

```bash
# Common log entries to look for:
# [info] Generating OAuth state token...
# [info] Redirecting to Google authorization URL
# [error] Failed to exchange code for token: ...
# [error] Failed to fetch user info: ...
# [error] Database error creating user: ...
```

Enable debug logging in `server/src/main/resources/logback.xml`:
```xml
<logger name="auth.oauth" level="DEBUG"/>
<logger name="dmscreen.DMScreenAuthServer" level="DEBUG"/>
```

## Testing with Multiple Google Accounts

### Test Account Switching

1. **First account**: Log in with Google account A
2. **Logout**: Use logout button in DMScreen
3. **Second account**: Log in with Google account B
4. **Google remembers**: Google will auto-select account A
5. **Switch accounts**: Click "Use another account" on Google consent screen

### Use Incognito/Private Mode

For clean testing without cached sessions:
```bash
# Chrome/Edge: Ctrl+Shift+N
# Firefox: Ctrl+Shift+P
# Safari: Cmd+Shift+N
```

### Clear OAuth Consent

To re-test the consent flow:
1. Go to: https://myaccount.google.com/permissions
2. Find "DMScreen Local Dev"
3. Click "Remove Access"
4. Next login will show consent screen again

## Testing from Mobile or External Devices

If you need to test from a mobile device or share with others, use **ngrok**:

### Install ngrok

Download from: https://ngrok.com/download

```bash
# Install ngrok
# Follow platform-specific instructions

# Start ngrok tunnel
ngrok http 8078

# Output shows:
# Forwarding: https://abc123def456.ngrok.io -> http://localhost:8078
```

### Update Google Console

1. Add ngrok URL to "Authorized redirect URIs":
   - `https://abc123def456.ngrok.io/oauth/google/callback`
   - Keep the localhost URI for local testing

### Update .env

```bash
# Use ngrok URL for redirect
DMSCREEN_OAUTH_GOOGLE_REDIRECT_URI="https://abc123def456.ngrok.io/oauth/google/callback"
```

### Access via ngrok

- Desktop: `https://abc123def456.ngrok.io`
- Mobile: Same URL on your phone
- Share: Anyone can access this URL (while ngrok is running)

**Note**: Free ngrok URLs change each time you restart ngrok. For persistent URLs, upgrade to ngrok paid plan.

## Production Deployment

For production deployment, see: **docs/DEPLOYMENT.md** (TODO: create this)

Key differences from local development:
- Use HTTPS (required by Google for non-localhost)
- Update redirect URI to production domain
- Set OAuth consent screen to "Published" status
- Consider adding additional OAuth providers (GitHub, Discord, etc.)
- Configure proper error monitoring

## Security Considerations

### OAuth Security Features

DMScreen's OAuth implementation includes:

1. **CSRF Protection**: State tokens with 10-minute expiration
2. **Token Storage**:
   - Access tokens in localStorage (client-side)
   - Refresh tokens in httpOnly cookies (not accessible to JavaScript)
3. **Auto-Cleanup**: Expired state tokens removed every 5 minutes
4. **Secure Callbacks**: State validation prevents replay attacks

### Best Practices

**Development**:
- ✅ Use localhost redirect URIs
- ✅ Keep Client Secret in `.env` (gitignored)
- ✅ Use "External" user type for testing
- ✅ Add only test users to OAuth consent screen

**Production**:
- ✅ Use HTTPS for all redirect URIs
- ✅ Store Client Secret in secure environment variables
- ✅ Publish OAuth consent screen after verification
- ✅ Monitor OAuth error logs
- ✅ Implement rate limiting on OAuth endpoints
- ✅ Rotate Client Secret periodically

**Never**:
- ❌ Commit Client Secret to git
- ❌ Use HTTP redirect URIs in production
- ❌ Share Client Secret in public channels
- ❌ Disable state token validation
- ❌ Store refresh tokens in localStorage

## Adding More OAuth Providers

DMScreen's OAuth architecture supports multiple providers. To add GitHub, Discord, or other providers:

### 1. Create Provider Implementation

Add to `zio-auth` library:

```scala
// auth/jvm/src/main/scala/auth/oauth/GitHubOAuthProvider.scala
class GitHubOAuthProvider(config: OAuthProviderConfig) extends OAuthProvider {
  override def providerName: String = "github"

  override def getUserInfo(accessToken: String): IO[AuthError, OAuthUserInfo] = {
    // Fetch and normalize GitHub user info
    // ...
  }
}
```

### 2. Update OAuthService

Extend `OAuthService.live` to accept the new provider config.

### 3. Configure in DMScreen

Add provider config to `application.conf` and update `EnvironmentBuilder.scala`.

### 4. Add UI Button

Add another `OAuthButton` to the login page:

```scala
OAuthButton(
  provider = "github",
  icon = Some(githubIcon),
  label = "Continue with GitHub",
  className = Some("oauth-button-github")
)
```

See `zio-auth/CLAUDE.md` for detailed instructions on adding new OAuth providers.

## Resources

- **zio-auth documentation**: `/home/rleibman/projects/zio-auth/CLAUDE.md`
- **DMScreen CLAUDE.md**: `/home/rleibman/projects/dmscreen/CLAUDE.md`
- **Google OAuth 2.0 docs**: https://developers.google.com/identity/protocols/oauth2
- **OAuth 2.0 specification**: https://oauth.net/2/
- **ngrok documentation**: https://ngrok.com/docs

## Support

If you encounter issues not covered in this guide:

1. Check server logs for detailed error messages
2. Verify Google Console configuration matches exactly
3. Review `zio-auth` library documentation
4. Check DMScreen GitHub issues: https://github.com/rleibman/dmscreen/issues
