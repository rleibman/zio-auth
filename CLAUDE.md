# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the zio-auth library.

## Project Overview

zio-auth is a reusable authentication library for Scala applications built with:
- **Backend**: ZIO-http with JWT-based session management
- **Frontend**: Scala.js with scalajs-react for UI components
- **Cross-compilation**: Shared code between JVM and JS platforms
- **Authentication**: Email/password with email verification flows + OAuth 2.0 support

## Architecture

### Multi-Module SBT Project

The library is organized as a cross-platform build:

- **auth** (cross-compiled JVM + JS) - Core authentication library
  - `auth/jvm/src/main/scala/auth/` - Server-side authentication
  - `auth/js/src/main/scala/auth/` - Client-side React components
  - `auth/shared/src/main/scala/auth/` - Shared types and validation

- **server** - Example/test server implementation

### Core Authentication Flow

1. **Session Management**: JWT-based with access + refresh tokens
   - Access token: Short-lived (60 min default), sent as Bearer header
   - Refresh token: Long-lived (14 days default), httpOnly cookie
   - ConnectionId: Optional client tracking (e.g., WebSocket connection)

2. **User Registration**:
   - POST `/requestRegistration` - Create inactive user, send confirmation email
   - POST `/confirmRegistration` - Activate user via email code

3. **Password Recovery**:
   - POST `/requestPasswordRecovery` - Send recovery email
   - POST `/passwordRecovery` - Reset password via email code

4. **Login/Logout**:
   - POST `/login` - Authenticate, return user + tokens
   - GET `/logout` - Invalidate session
   - GET `/api/whoami` - Get current user from session

5. **Token Refresh**:
   - GET `/refresh` - Renew access token using refresh cookie

### OAuth 2.0 Authentication (v3.0.0+)

**Added in version 3.0.0-SNAPSHOT**: zio-auth now includes built-in OAuth 2.0 support with a generic multi-provider architecture.

**OAuth Flow**:
1. `GET /oauth/{provider}/login` - Initiate OAuth flow, redirect to provider
2. Provider authorization page (user consents)
3. `GET /oauth/{provider}/callback?code=xxx&state=xxx` - Handle callback, exchange code for token
4. Fetch user info from provider
5. Find existing user or create new user (auto-link by email)
6. Return JWT session (same as email/password login)

**Supported Providers** (out of the box):
- Google OAuth 2.0
- Extensible architecture for adding more (GitHub, Discord, etc.)

**Key Features**:
- **CSRF Protection**: State tokens with expiration and cleanup
- **Auto-Account Linking**: OAuth accounts auto-link to existing email/password accounts by email
- **Generic Provider Architecture**: Easy to add new OAuth providers via `OAuthProvider` trait
- **Normalized User Info**: `OAuthUserInfo` provides consistent user data across all providers
- **Reusable UI Component**: `OAuthButton` for frontend integration
- **Optional**: OAuth is optional - if not configured, OAuth routes are not added

**OAuth Routes** (automatically added to unauthRoutes if OAuthService is configured):
- `GET /oauth/{provider}/login` - Initiate OAuth flow
- `GET /oauth/{provider}/callback` - Handle OAuth callback

**OAuth Database Schema**:
Your user table needs these optional fields to support OAuth:
```sql
ALTER TABLE users
  ADD COLUMN oauth_provider VARCHAR(50) NULL,
  ADD COLUMN oauth_provider_id VARCHAR(255) NULL,
  ADD COLUMN oauth_provider_data JSON NULL;

CREATE UNIQUE INDEX idx_oauth_provider_id
  ON users (oauth_provider, oauth_provider_id);
```

### Generic Trait-Based Design

The `AuthServer[UserType, UserPK, ConnectionId]` trait is designed to be implemented by consuming applications:

```scala
trait AuthServer[UserType, UserPK, ConnectionId] {
  // User model operations
  def getPK(user: UserType): UserPK
  def userByEmail(email: String): IO[AuthError, Option[UserType]]
  def userByPK(pk: UserPK): IO[AuthError, Option[UserType]]

  // Authentication
  def login(userName: String, password: String, connectionId: Option[ConnectionId]):
    IO[AuthError, Option[UserType]]
  def logout(): ZIO[Session[UserType, ConnectionId], AuthError, Unit]

  // User management
  def createUser(name: String, email: String, password: String): IO[AuthError, UserType]
  def activateUser(userPK: UserPK): IO[AuthError, Unit]
  def changePassword(userPK: UserPK, newPassword: String):
    ZIO[Session[UserType, ConnectionId], AuthError, Unit]

  // Email operations
  def sendEmail(subject: String, body: String, user: UserType): IO[AuthError, Unit]
  def getEmailBodyHtml(user: UserType, purpose: UserCodePurpose, url: String): String

  // Token management (optional hooks)
  def isInvalid(tok: String): IO[AuthError, Boolean] = ZIO.succeed(false)
  def invalidateToken(token: String): IO[AuthError, Unit] = ZIO.unit

  // OAuth methods (v3.0.0+, with default implementations)
  def userByOAuthProvider(
    provider: String,
    providerId: String
  ): IO[AuthError, Option[UserType]] = ZIO.none

  def createOAuthUser(
    oauthInfo: OAuthUserInfo,
    provider: String,
    connectionId: Option[ConnectionId]
  ): IO[AuthError, UserType] = ZIO.fail(AuthError("OAuth not implemented"))

  def linkOAuthToUser(
    user: UserType,
    provider: String,
    providerId: String,
    providerData: Json
  ): IO[AuthError, UserType] = ZIO.fail(AuthError("OAuth not implemented"))
}
```

**Integration Pattern**:
1. Implement `AuthServer` trait for your user model
2. Implement OAuth methods if using OAuth (or leave defaults if not)
3. Create ZLayer providing `AuthServer[YourUser, YourUserPK, YourConnectionId]`
4. Create `OAuthService` layer if using OAuth (optional)
5. Add `authServer.authRoutes` to your authenticated routes
6. Add `authServer.unauthRoutes` to your public routes (includes OAuth routes if configured)
7. Use `authServer.bearerSessionProvider` as middleware for protected routes
8. Use `LoginRouter` or custom login page with `OAuthButton` on frontend

### Session Types

```scala
sealed abstract class Session[UserType, ConnectionId] {
  def user: Option[UserType]
  def connectionId: Option[ConnectionId]
}

case class AuthenticatedSession[UserType, ConnectionId](
  user: Some[UserType],
  connectionId: Option[ConnectionId] = None
) extends Session[UserType, ConnectionId]

case class UnauthenticatedSession[UserType, ConnectionId](
  connectionId: Option[ConnectionId] = None
) extends Session[UserType, ConnectionId]
```

Sessions are injected as ZLayer via the `bearerSessionProvider` middleware. Protected routes can access the session:

```scala
handler { (req: Request) =>
  for {
    session <- ZIO.service[Session[User, ConnectionId]]
    user    <- ZIO.fromOption(session.user)
                 .orElseFail(AuthError.NotAuthenticated())
    // ... use user ...
  } yield response
}
```

### Frontend Components

Pre-built React components (all in `auth/js/src/main/scala/auth/`):

- **LoginPage** - Email/password login form
- **RequestRegistrationPage** - New user signup form
- **ConfirmRegistrationPage** - Email confirmation handler (reads code from URL)
- **RequestLostPasswordPage** - Password recovery request form
- **PasswordRecoveryPage** - Password reset form (reads code from URL)
- **LoginRouter** - Hash-based router for all auth pages
- **OAuthButton** - Reusable OAuth provider button (v3.0.0+)

**LoginRouter routes**:
- `#login` → LoginPage
- `#requestRegistration` → RequestRegistrationPage
- `#confirmRegistration?code=xxx` → ConfirmRegistrationPage
- `#requestLostPassword` → RequestLostPasswordPage
- `#passwordRecovery?code=xxx` → PasswordRecoveryPage

**AuthClient** - Client-side API wrapper for making authenticated requests:

```scala
// Login
AuthClient.login[User, ConnectionId](email, password, connectionId)

// Get current user
AuthClient.whoami[User, ConnectionId](connectionId)

// Logout
AuthClient.logout()

// Request password recovery
AuthClient.requestPasswordRecovery(email)

// ... and more
```

**OAuthButton** (v3.0.0+) - Reusable OAuth button component:

```scala
import auth.OAuthButton

// Basic usage
OAuthButton(
  provider = "google",
  label = "Continue with Google"
)

// With custom icon
OAuthButton(
  provider = "google",
  icon = Some(myGoogleIconVdomNode),
  label = "Continue with Google",
  className = Some("my-custom-oauth-button-class")
)
```

The button is a simple anchor tag that links to `/oauth/{provider}/login`, triggering the OAuth flow. Style it using CSS classes.

### Configuration

#### Server-Side Configuration

**AuthConfig** (provided as ZLayer):

```scala
case class AuthConfig(
  secretKey: SecretKey,                      // JWT signing key (min 128 chars)
  accessTTL: Duration,                        // Access token lifetime (default: 60 minutes)
  refreshTTL: Duration,                       // Refresh token lifetime (default: 14 days)
  refreshTokenName: String,                   // Cookie name for refresh token (default: "X-Refresh-Token")
  codeExpirationHours: Duration,              // Email verification code expiration (default: 2 days)

  // Endpoint URLs (customizable)
  loginUrl: String,                           // default: "login"
  logoutUrl: String,                          // default: "logout"
  refreshUrl: String,                         // default: "/refresh"
  whoAmIUrl: String,                          // default: "api/whoami"
  requestPasswordRecoveryUrl: String,         // default: "requestPasswordRecovery"
  passwordRecoveryUrl: String,                // default: "passwordRecovery"
  requestRegistrationUrl: String,             // default: "requestRegistration"
  confirmRegistrationUrl: String              // default: "confirmRegistration"
)
```

**SecretKey** - Wrapper for JWT signing key:
```scala
case class SecretKey(key: String) {
  // Validates that key is at least 128 characters
  require(key.length >= 128, "Secret key must be at least 128 characters")
}
```

#### Client-Side Configuration

**ClientAuthConfig** (fetched from server):

```scala
case class ClientAuthConfig(
  requestPasswordRecoveryUrl: String = "",
  requestRegistrationUrl: String = "",
  loginUrl: String = "",
  logoutUrl: String = "",
  refreshUrl: String = "",
  whoAmIUrl: String = ""
)
```

Fetched via `GET /api/clientAuthConfig` to keep client/server URLs synchronized.

### Error Handling

All errors extend `AuthError`:

```scala
sealed class AuthError extends Error

object AuthError {
  case class NotAuthenticated(message: String = "Not authenticated") extends AuthError
  case class ExpiredToken(message: String = "Token expired") extends AuthError
  case class InvalidToken(message: String = "Invalid token") extends AuthError
  case class EmailAlreadyExists(email: String) extends AuthError
  case class AuthBadRequest(message: String) extends AuthError
  case class UserNotFound(identifier: String) extends AuthError
  case class InvalidCredentials() extends AuthError
  case class EmailNotSent(reason: String) extends AuthError
  case class InvalidCode(message: String = "Invalid or expired code") extends AuthError
}
```

Errors are returned as HTTP status codes:
- 401 Unauthorized: `NotAuthenticated`, `InvalidCredentials`, `ExpiredToken`
- 400 Bad Request: `AuthBadRequest`, `InvalidCode`, `EmailAlreadyExists`
- 404 Not Found: `UserNotFound`
- 500 Internal Server Error: `EmailNotSent`

### JWT Token Details

**Token Generation**:
- Algorithm: HS512 (HMAC-SHA512)
- Library: `pdi.jwt` (jwt-scala)
- Claims: Entire `Session[UserType, ConnectionId]` serialized to JSON

**Access Token**:
- Lifetime: `accessTTL` (default 60 minutes)
- Transmission: `Authorization: Bearer <token>` header
- Storage: localStorage (key: `"jwtToken"`)
- Expiration: Hard expiration, requires refresh

**Refresh Token**:
- Lifetime: `refreshTTL` (default 14 days)
- Transmission: HTTP-only cookie (name: `refreshTokenName`)
- Storage: Browser cookie (httpOnly, Secure, SameSite=Strict)
- Expiration: Sliding window (renewed on refresh)

**Token Refresh Flow**:
1. Client detects 401 with "token_expired" message
2. Client calls `GET /refresh` (refresh cookie sent automatically)
3. Server validates refresh token
4. Server issues new access token + new refresh token
5. Client updates localStorage and retries original request

**Middleware**:

```scala
def bearerSessionProvider: HandlerAspect[AuthConfig, Session[UserType, ConnectionId]]
```

This middleware:
- Intercepts all requests
- Extracts `Authorization: Bearer` header
- Decodes JWT into `Session[UserType, ConnectionId]`
- Provides session as ZLayer to handlers
- Returns 401 Unauthorized for expired/invalid tokens

### Email Verification Codes

**User Codes** are JWT tokens containing:

```scala
case class UserCode(
  purpose: UserCodePurpose,
  userPK: UserPK
)

enum UserCodePurpose {
  case NewUser         // Account activation
  case PasswordRecovery // Password reset
}
```

**Code Generation**:
```scala
// Server generates JWT with UserCode payload
val code: String = // JWT token expires in codeExpirationHours
val url = s"https://yourapp.com/#confirmRegistration?code=$code"
sendEmail(user, "Confirm your account", emailBodyHtml(user, UserCodePurpose.NewUser, url))
```

**Code Validation**:
```scala
// Server decodes JWT, extracts UserCode, verifies signature and expiration
// Then calls activateUser(userCode.userPK) or changePassword(...)
```

### Testing

The library includes:

- **`MockAuthEnvironment.scala`** - Test implementation of AuthServer
- **`TestServer.scala`** - Example server for manual testing
- **`AuthSpec.scala`** - ZIO Test suite

**Running tests**:
```bash
sbt test
```

**Manual testing with test server**:
```bash
sbt "project server" ~reStart
# Server runs on http://localhost:8081
# Test UI available at http://localhost:8081
```

## Common Development Tasks

### Building the Library

```bash
# Compile JVM + JS
sbt compile

# Run tests
sbt test

# Publish locally
sbt publishLocal
```

### Using the Library in Your App

**Add dependency** (in `build.sbt`):
```scala
libraryDependencies += "auth" %% "auth" % "0.1.0-SNAPSHOT"
```

**Implement AuthServer**:

```scala
case class MyUser(id: Long, email: String, name: String, active: Boolean)
opaque type MyUserId = Long
opaque type MyConnectionId = String

class MyAuthServer(
  repo: MyUserRepository,
  emailService: EmailService,
  config: AuthConfig
) extends AuthServer[MyUser, MyUserId, MyConnectionId] {

  override def getPK(user: MyUser): MyUserId = user.id

  override def login(
    userName: String,
    password: String,
    connectionId: Option[MyConnectionId]
  ): IO[AuthError, Option[MyUser]] = {
    repo.authenticateUser(userName, password)
      .mapError(e => AuthError.InvalidCredentials())
  }

  override def userByEmail(email: String): IO[AuthError, Option[MyUser]] =
    repo.findByEmail(email).mapError(_ => AuthError.UserNotFound(email))

  override def userByPK(pk: MyUserId): IO[AuthError, Option[MyUser]] =
    repo.findById(pk).mapError(_ => AuthError.UserNotFound(pk.toString))

  override def createUser(name: String, email: String, password: String): IO[AuthError, MyUser] =
    repo.createUser(name, email, password, active = false)
      .mapError {
        case _: DuplicateEmailError => AuthError.EmailAlreadyExists(email)
        case e => AuthError.AuthBadRequest(e.getMessage)
      }

  override def activateUser(userPK: MyUserId): IO[AuthError, Unit] =
    repo.activateUser(userPK).mapError(_ => AuthError.UserNotFound(userPK.toString))

  override def changePassword(userPK: MyUserId, newPassword: String):
    ZIO[Session[MyUser, MyConnectionId], AuthError, Unit] =
      repo.setPassword(userPK, newPassword).mapError(_ => AuthError.UserNotFound(userPK.toString))

  override def sendEmail(subject: String, body: String, user: MyUser): IO[AuthError, Unit] =
    emailService.send(user.email, subject, body)
      .mapError(e => AuthError.EmailNotSent(e.getMessage))

  override def getEmailBodyHtml(user: MyUser, purpose: UserCodePurpose, url: String): String =
    s"""
      |<html>
      |<body>
      |  <h1>Hello ${user.name}</h1>
      |  <p>${purpose match {
      |    case UserCodePurpose.NewUser => "Click the link below to activate your account:"
      |    case UserCodePurpose.PasswordRecovery => "Click the link below to reset your password:"
      |  }}</p>
      |  <a href="$url">$url</a>
      |</body>
      |</html>
    """.stripMargin
}
```

**Create ZLayer**:

```scala
val authServerLayer: ZLayer[
  MyUserRepository & EmailService & AuthConfig,
  Nothing,
  AuthServer[MyUser, MyUserId, MyConnectionId]
] = ZLayer.fromZIO {
  for {
    repo   <- ZIO.service[MyUserRepository]
    email  <- ZIO.service[EmailService]
    config <- ZIO.service[AuthConfig]
  } yield new MyAuthServer(repo, email, config)
}
```

**Integrate routes**:

```scala
object MyApp extends ZIOAppDefault {
  def run = Server.serve(app).provide(/* layers */)

  lazy val app: ZIO[AuthServer[MyUser, MyUserId, MyConnectionId] & AuthConfig, Nothing, Routes[...]] =
    for {
      authServer  <- ZIO.service[AuthServer[MyUser, MyUserId, MyConnectionId]]
      authRoutes  <- authServer.authRoutes      // Protected routes
      unauthRoutes <- authServer.unauthRoutes   // Public routes
      myAppRoutes <- myAppLogic                 // Your app routes
    } yield {
      // Apply bearer middleware to protected routes
      (myAppRoutes ++ authRoutes) @@ authServer.bearerSessionProvider ++
        unauthRoutes  // Public routes (login, registration, etc.)
    }
}
```

**Frontend integration**:

```scala
object MyApp {
  val component = ScalaFnComponent
    .withHooks[Unit]
    .useState(None: Option[MyUser])
    .useEffectOnMountBy { (_, userOpt) =>
      AuthClient
        .whoami[MyUser, MyConnectionId](Some(myConnectionId))
        .map(me => userOpt.modState(_ => me))
        .completeWith(_.get)
    }
    .render { (_, userOpt) =>
      userOpt.value.fold(
        // Not logged in: show auth UI
        LoginRouter(Some(myConnectionId))
      ) { user =>
        // Logged in: show main app
        MyMainApp(user)
      }
    }
}
```

## OAuth Integration (v3.0.0+)

**Version 3.0.0-SNAPSHOT introduced native OAuth 2.0 support** with a generic multi-provider architecture.

### Quick Start: Adding OAuth to Your App

1. **Update your User model** to include OAuth fields:

```scala
case class User(
  id: UserId,
  email: String,
  name: String,
  active: Boolean,
  // ... other fields ...
  oauthProvider: Option[String] = None,
  oauthProviderId: Option[String] = None,
  oauthProviderData: Option[Json] = None
)
```

2. **Run database migration** (see OAuth Database Schema section above)

3. **Implement OAuth methods** in your AuthServer:

```scala
class MyAuthServer(...) extends AuthServer[User, UserId, ConnectionId] {
  // ... existing methods ...

  override def userByOAuthProvider(
    provider: String,
    providerId: String
  ): IO[AuthError, Option[User]] =
    repo.findByOAuthProvider(provider, providerId)
      .mapError(e => AuthError(e.getMessage))

  override def createOAuthUser(
    oauthInfo: OAuthUserInfo,
    provider: String,
    connectionId: Option[ConnectionId]
  ): IO[AuthError, User] =
    repo.createUser(
      name = oauthInfo.name,
      email = oauthInfo.email,
      active = oauthInfo.emailVerified,  // Auto-activate verified emails
      oauthProvider = Some(provider),
      oauthProviderId = Some(oauthInfo.providerId),
      oauthProviderData = Some(oauthInfo.rawData)
    ).mapError(e => AuthError(e.getMessage))

  override def linkOAuthToUser(
    user: User,
    provider: String,
    providerId: String,
    providerData: Json
  ): IO[AuthError, User] =
    repo.updateUser(user.copy(
      oauthProvider = Some(provider),
      oauthProviderId = Some(providerId),
      oauthProviderData = Some(providerData)
    )).mapError(e => AuthError(e.getMessage))
}
```

4. **Configure OAuth providers** and create OAuthService layer:

```scala
import auth.oauth.{OAuthProviderConfig, OAuthService}

val oauthServiceLayer: ZLayer[MyConfig, ConfigurationError, OAuthService] =
  ZLayer.fromZIO {
    for {
      config <- ZIO.service[MyConfig]
    } yield {
      val googleConfig = OAuthProviderConfig(
        clientId = config.oauth.google.clientId,
        clientSecret = config.oauth.google.clientSecret,
        authorizationUri = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUri = "https://oauth2.googleapis.com/token",
        userInfoUri = "https://www.googleapis.com/oauth2/v2/userinfo",
        redirectUri = config.oauth.google.redirectUri,
        scopes = List("openid", "email", "profile")
      )

      OAuthService.live(googleConfig = Some(googleConfig))
    }
  }.flatten
```

5. **Add OAuthService to your environment**:

```scala
ZLayer.make[MyAppEnvironment](
  // ... other layers ...
  oauthServiceLayer,
  authServerLayer,
  // ... rest of layers ...
)
```

6. **Use OAuthButton in your frontend**:

```scala
import auth.OAuthButton

<.div(
  ^.className := "login-form",
  // Email/password form...

  // OAuth section
  <.div(^.className := "oauth-divider", <.span("OR")),
  OAuthButton(
    provider = "google",
    icon = Some(googleIconSvg),
    label = "Continue with Google",
    className = Some("oauth-button-google")
  )
)
```

### How OAuth Flow Works

1. User clicks OAuthButton → navigates to `/oauth/google/login`
2. Server generates CSRF state token and redirects to Google
3. User authorizes on Google's page
4. Google redirects to `/oauth/google/callback?code=xxx&state=xxx`
5. Server validates state (CSRF protection)
6. Server exchanges code for access token
7. Server fetches user info from Google
8. Server finds or creates user:
   - If OAuth account exists → login
   - If email exists → auto-link OAuth to existing account
   - If neither → create new user
9. Server creates JWT session (same as email/password login)
10. User is logged in

### Adding More OAuth Providers

To add a new provider (e.g., GitHub):

1. **Create provider implementation**:

```scala
// In zio-auth library
class GitHubOAuthProvider(config: OAuthProviderConfig) extends OAuthProvider {
  override def providerName: String = "github"

  override def getUserInfo(accessToken: String): IO[AuthError, OAuthUserInfo] = {
    // Parse GitHub's user info response into normalized OAuthUserInfo
    // ...
  }
}
```

2. **Update OAuthService.live**:

```scala
def live(
  googleConfig: Option[OAuthProviderConfig] = None,
  githubConfig: Option[OAuthProviderConfig] = None  // New parameter
): ULayer[OAuthService] = ZLayer.succeed {
  new OAuthService {
    private val providers: Map[String, OAuthProvider] = List(
      googleConfig.map(config => "google" -> new GoogleOAuthProvider(config)),
      githubConfig.map(config => "github" -> new GitHubOAuthProvider(config))
    ).flatten.toMap
    // ...
  }
}
```

3. **Configure in your app** and add another OAuthButton in frontend

### Benefits of Integrated OAuth

- **Automatic CSRF protection** via state tokens
- **Auto-account linking** by email
- **Reuses existing JWT infrastructure** (same tokens, same middleware, same refresh flow)
- **Generic provider architecture** for easy extension
- **Normalized user data** across all providers
- **Optional** - if not configured, OAuth routes aren't added

## Key Design Principles

1. **Generic by design** - Works with any user model, user PK type, and connection ID
2. **JWT everywhere** - Stateless sessions, no server-side session storage required
3. **Security defaults** - httpOnly cookies for refresh tokens, SameSite=Strict, Secure flag
4. **Email verification** - Users inactive until email confirmed (prevents fake signups)
5. **Type-safe** - Leverages Scala 3 type system and zio-json derivation
6. **Reusable UI** - Pre-built React components for common auth flows
7. **Extensible** - Easy to add new auth methods (OAuth, SAML, etc.) alongside core flows

## Dependencies

### JVM (Server)

```scala
"dev.zio"                       %% "zio"               % zioVersion
"dev.zio"                       %% "zio-http"          % zioHttpVersion
"dev.zio"                       %% "zio-json"          % zioJsonVersion
"dev.zio"                       %% "zio-config"        % zioConfigVersion
"com.github.jwt-scala"          %% "jwt-circe"         % jwtVersion
"com.github.daddykotex"         %% "courier"           % courierVersion       // SMTP email
"com.softwaremill.sttp.client4" %% "core"              % sttpVersion          // OAuth HTTP client (v3.0.0+)
"com.softwaremill.sttp.client4" %% "zio"               % sttpVersion          // OAuth ZIO backend (v3.0.0+)
"com.softwaremill.sttp.client4" %% "zio-json"          % sttpVersion          // OAuth JSON support (v3.0.0+)
```

### JS (Client)

```scala
"com.github.japgolly.scalajs-react" %%% "core"     % scalaJsReactVersion
"com.github.japgolly.scalajs-react" %%% "extra"    % scalaJsReactVersion
"com.softwaremill.sttp.client4"     %%% "core"     % sttpVersion
"com.softwaremill.sttp.client4"     %%% "zio-json" % sttpVersion
"dev.zio"                           %%% "zio-json" % zioJsonVersion
```

## File Structure

```
zio-auth/
├── auth/
│   ├── jvm/src/main/scala/auth/
│   │   ├── AuthServer.scala         # Main trait to implement
│   │   ├── AuthConfig.scala         # Server configuration
│   │   ├── AuthError.scala          # Error types
│   │   ├── Session.scala            # Session ADT
│   │   ├── UserCode.scala           # Email verification codes
│   │   ├── SecretKey.scala          # JWT secret key wrapper
│   │   └── oauth/                   # OAuth 2.0 support (v3.0.0+)
│   │       ├── OAuthProvider.scala          # Generic provider trait
│   │       ├── OAuthProviderConfig.scala    # Provider configuration
│   │       ├── OAuthService.scala           # Multi-provider registry
│   │       └── GoogleOAuthProvider.scala    # Google OAuth implementation
│   ├── js/src/main/scala/auth/
│   │   ├── AuthClient.scala         # API client for auth endpoints
│   │   ├── LoginPage.scala          # Login form component
│   │   ├── LoginRouter.scala        # Router for all auth pages
│   │   ├── OAuthButton.scala        # OAuth button component (v3.0.0+)
│   │   ├── RequestRegistrationPage.scala
│   │   ├── ConfirmRegistrationPage.scala
│   │   ├── RequestLostPasswordPage.scala
│   │   └── PasswordRecoveryPage.scala
│   └── shared/src/main/scala/auth/
│       ├── ClientAuthConfig.scala   # Endpoint URLs for client
│       ├── PasswordRecoveryRequest.scala
│       ├── PasswordRecoveryNewPasswordRequest.scala
│       ├── UserRegistrationRequest.scala
│       └── oauth/                   # OAuth shared types (v3.0.0+)
│           ├── OAuthUserInfo.scala          # Normalized OAuth user data
│           └── OAuthProviderConfig.scala    # Provider configuration (shared)
└── server/
    └── src/main/scala/             # Test/example server
        ├── MockAuthEnvironment.scala
        └── TestServer.scala
```

## Integration Checklist

When integrating zio-auth into your application:

1. [ ] Add zio-auth dependency to build.sbt
2. [ ] Create User case class with required fields (id, email, name, active)
3. [ ] Create UserRepository with login, createUser, activateUser, etc.
4. [ ] Implement AuthServer trait for your user model
5. [ ] Create ZLayer providing AuthServer implementation
6. [ ] Configure AuthConfig with secret key (min 128 chars) and endpoints
7. [ ] Add authServer.authRoutes to authenticated routes
8. [ ] Add authServer.unauthRoutes to public routes
9. [ ] Apply bearerSessionProvider middleware to protected routes
10. [ ] Integrate LoginRouter in frontend app entry point
11. [ ] Configure SMTP for email sending (registration, password recovery)
12. [ ] Set up environment variables (secret key, database, SMTP)
13. [ ] Test full registration flow (signup → email → activation)
14. [ ] Test password recovery flow (request → email → reset)
15. [ ] Test login/logout/token refresh flows

## Common Pitfalls

1. **Secret key too short**: Must be at least 128 characters. Use a strong random string.
2. **CORS issues**: Ensure refresh cookie works cross-origin if needed (SameSite=None in dev)
3. **Email not sent**: Check SMTP configuration and firewall rules
4. **Token expired loops**: Ensure refresh token has longer TTL than access token
5. **Missing middleware**: Protected routes must use `@@ bearerSessionProvider`
6. **Frontend token storage**: Access token in localStorage, refresh in httpOnly cookie (don't swap!)

## Support

For issues, questions, or contributions:
- Check the test server implementation for working examples
- Review the mock environment for testing patterns
- Ensure your user model has all required fields (id, email, active)
- Verify JWT secret key is properly configured and long enough

## License

(Add your license information here)
