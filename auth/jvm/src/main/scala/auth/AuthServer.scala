/*
 * Copyright (c) 2025 Roberto Leibman
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package auth

import auth.oauth.{OAuthService, OAuthStateStore, OAuthUserInfo}
import pdi.jwt.*
import pdi.jwt.exceptions.JwtExpirationException
import zio.*
import zio.http.*
import zio.http.Cookie.SameSite
import zio.json.*
import zio.json.ast.Json

import java.time.LocalDateTime

object AuthServer {

  extension (body: Body) {

    def as[A: JsonDecoder]: IO[AuthError, A] =
      body.asString
        .flatMap(s =>
          ZIO
            .fromEither(s.fromJson[A]).mapError(e => AuthError(s"Could not parse request body: $e"))
        ).mapError(AuthError(_))

  }

}

trait AuthServer[
  UserType:     {JsonEncoder, JsonDecoder, Tag},
  UserPK:       {JsonEncoder, JsonDecoder, Tag},
  ConnectionId: {JsonEncoder, JsonDecoder, Tag}
] {

  import AuthServer.*

  private given JsonCodec[Some[UserType]] = JsonCodec.derived[Some[UserType]]
  private given JsonCodec[Session[UserType, ConnectionId]] = JsonCodec.derived[Session[UserType, ConnectionId]]

  private given JsonCodec[UserCodePurpose] =
    JsonCodec.string.transformOrFail(
      s => UserCodePurpose.values.find(_.toString == s).toRight(s"Unknown UserCodePurpose: $s"),
      _.toString
    )

  private given JsonCodec[UserCode[UserPK]] = JsonCodec.derived[UserCode[UserPK]]

  extension (claim: JwtClaim) {

    def decodedContent[A: JsonDecoder]: ZIO[Any, AuthError, A] =
      ZIO.fromEither(claim.content.fromJson[A]).mapError(AuthError(_))

  }

  final protected def json[A: JsonEncoder](value: A): Response = Response.json(value.toJson)

  def getPK(user: UserType): UserPK

  def authRoutes: URIO[AuthConfig, Routes[AuthConfig & Session[UserType, ConnectionId], AuthError]] =
    for {
      config <- ZIO.service[AuthConfig]
    } yield Routes(
      Method.GET / config.whoAmIUrl -> handler { (_: Request) =>
        ZIO.serviceWith[Session[UserType, ConnectionId]] {
          case AuthenticatedSession(Some(user), _) => json(user)
          case _                                   => Response.unauthorized("No session")
        }
      },
      Method.GET / config.logoutUrl -> handler { (req: Request) =>
        val token = req.header(Header.Authorization).map(_.renderedValue.stripPrefix("Bearer "))
        for {
          config <- ZIO.service[AuthConfig]
          _      <- ZIO.foreachDiscard(token)(invalidateToken)
          _      <- logout()
          _      <- ZIO.logInfo("User logged out, clearing refresh cookie")
        } yield Response.ok
          // Clear the refresh cookie by setting it to expire immediately
          .addCookie(
            Cookie.Response(
              name = config.refreshTokenName,
              content = "",
              maxAge = Some(Duration.Zero),
              domain = None,
              path = Some(Path.decode("/refresh")),
              isSecure = true,
              isHttpOnly = true,
              sameSite = Some(SameSite.Strict)
            )
          )
      }
    )

  def unauthRoutes: URIO[AuthConfig & OAuthService & OAuthStateStore, Routes[AuthConfig, AuthError]] =
    for {
      config      <- ZIO.service[AuthConfig]
      oauthRoutes <- oauthUnauthRoutes
    } yield Routes(
      Method.POST / config.requestPasswordRecoveryUrl -> handler { (req: Request) =>
        for {
          parsed  <- req.body.as[PasswordRecoveryRequest]
          userOpt <- userByEmail(parsed.email)
          // If the user doesn't exist, we don't want to tell the user, we return Response.ok regardless
          _ <- userOpt.fold(
            ZIO.logInfo(
              s"Attempted to recover password for ${parsed.email}, but the user with that email does not exist"
            )
          ) { user =>
            val userCode = UserCode(UserCodePurpose.LostPassword, getPK(user)) // Create a code for the user to confirm registration
            for {
              userValidationToken <- jwtEncode(userCode, config.codeExpirationHours) // Encode the user code into a jwt
              confirmUrl = s"${config.passwordRecoveryUrl}?code=$userValidationToken"
              _ <- sendEmail(
                "Password Recovery Request",
                getEmailBodyHtml(user, UserCodePurpose.LostPassword, confirmUrl),
                user
              )
            } yield ()
          }
        } yield Response.status(Status.NoContent) // Nothing is really required back
      },
      Method.POST / config.passwordRecoveryUrl -> handler((req: Request) =>
        for {
          parsed      <- req.body.as[PasswordRecoveryNewPasswordRequest]
          claim       <- jwtDecode(parsed.confirmationCode)
          decodedUser <- claim.decodedContent[UserCode[UserPK]]
          userOpt     <- userByPK(decodedUser.userPK)
          _ <- userOpt.fold(ZIO.fail(AuthError("User Not Found")))(user =>
            changePassword(decodedUser.userPK, parsed.password)
              .provideLayer(ZLayer.succeed(Session(user, None)))
          )
        } yield Response.ok // TODO change it to Response.status(Status.NoContent) // Nothing is really required back
      ),
      Method.POST / config.requestRegistrationUrl -> handler((req: Request) =>
        for {
          parsed <- req.body.as[UserRegistrationRequest]
          validated <- ZIO
            .fromEither(
              UserRegistrationRequest
                .validateRequest(parsed.name, parsed.email, parsed.password, parsed.password).toEither
            ).mapError(e => AuthBadRequest(e.toNonEmptyList.toList.mkString(", ")))
          // Create the user, but set it to inactive and save it
          _ <- ZIO
            .fail(EmailAlreadyExists(s"Email already exists ${validated.email}"))
            .whenZIO(userByEmail(validated.email).map(u => u.isDefined))
          user <- createUser(
            validated.name,
            validated.email,
            validated.password
          )
          userCode = UserCode(UserCodePurpose.NewUser, getPK(user)) // Create a code for the user to confirm registration
          userValidationToken <- jwtEncode(userCode, config.codeExpirationHours) // Encode the user code into a jwt
          confirmUrl = s"${config.confirmRegistrationUrl}?code=$userValidationToken"
          _ <- sendEmail(
            "User creation confirmation",
            getEmailBodyHtml(user, UserCodePurpose.NewUser, confirmUrl),
            user
          )
        } yield Response.status(Status.NoContent) // Nothing is really required back
      ).mapError(AuthError(_)),
      Method.POST / config.confirmRegistrationUrl -> handler { (req: Request) =>
        for {
          confirmationCode <- req.body.as[String]
          claim            <- jwtDecode(confirmationCode)
          decodedUser      <- claim.decodedContent[UserCode[UserPK]]
          _                <- activateUser(decodedUser.userPK)
        } yield Response.status(Status.NoContent) // Nothing is really required back
      },
      Method.GET / "api" / "clientAuthConfig" -> handler((_: Request) =>
        Response.json(
          ClientAuthConfig(
            requestPasswordRecoveryUrl = config.requestPasswordRecoveryUrl,
            requestRegistrationUrl = config.requestRegistrationUrl,
            loginUrl = config.loginUrl,
            logoutUrl = config.logoutUrl,
            refreshUrl = config.refreshUrl,
            whoAmIUrl = config.whoAmIUrl
          ).toJson
        )
      ),
      Method.POST / config.loginUrl -> handler { (req: Request) =>
        case class LoginRequest(
          email:        String,
          password:     String,
          connectionId: Option[ConnectionId]
        )
        given JsonDecoder[LoginRequest] = JsonDecoder.derived

        (for {
          loginRequest <- req.body.as[LoginRequest]
          login        <- login(loginRequest.email, loginRequest.password, loginRequest.connectionId)
          _ <- login.fold(ZIO.logDebug(s"Bad login for ${loginRequest.email}"))(_ =>
            ZIO.logDebug(s"Good Login for ${loginRequest.email}")
          )
          res <- login.fold(ZIO.succeed {
            Response.unauthorized("Could not log on, sorry")
          }) { user =>
            addTokens(
              Session(user, loginRequest.connectionId),
              Response.json(user.toJson)
            )
          }
        } yield res).mapError(AuthError(_))
      },
      Method.GET / config.refreshUrl -> handler { (req: Request) =>
        // Check the refresh token from the cookie
        // If it's valid, create a new access token and refresh token, pass them back
        val refreshCookie = req.cookie(config.refreshTokenName).map(_.content)
        for {
          _     <- ZIO.fail(InvalidToken("Valid refresh cookie not found")).when(refreshCookie.isEmpty)
          claim <- jwtDecode(refreshCookie.get)
          u     <- claim.decodedContent[Session[UserType, ConnectionId]]
          responseWithTokens <- addTokens(
            u,
            Response.ok
          )
        } yield responseWithTokens
      }
    ) ++ oauthRoutes

  /** OAuth routes for login and callback
    *
    * These routes are only active if OAuthService is provided in the environment. If OAuthService is not available,
    * returns empty routes (OAuth disabled).
    *
    * Routes:
    *   - GET /oauth/:provider/login - Initiates OAuth flow, redirects to provider
    *   - GET /oauth/:provider/callback - Handles OAuth callback, creates/links user, returns JWT
    *
    * Note: This method requires OAuthService in the environment. If not provided, it will fail at runtime. Use
    * ZLayer.succeed(OAuthService.live(None)) to disable OAuth.
    */
  private def oauthUnauthRoutes: URIO[AuthConfig & OAuthService & OAuthStateStore, Routes[AuthConfig, AuthError]] =
    ZIO.serviceWithZIO[OAuthService] { oauthService =>
      ZIO.serviceWith[OAuthStateStore] { stateStore =>
        Routes(
          // OAuth login initiation
          Method.GET / "oauth" / string("provider") / "login" -> handler {
            (
              provider: String,
              _:        Request
            ) =>
              (for {
                oauthProvider <- oauthService.getProvider(provider)
                state         <- oauthService.generateState()
                _             <- stateStore.put(state, LocalDateTime.now(), provider)
                authUrl       <- oauthProvider.generateAuthUrl(state)
                url <- ZIO
                  .fromOption(URL.decode(authUrl).toOption)
                  .orElseFail(AuthError(s"Invalid authorization URL: $authUrl"))
              } yield Response.seeOther(url)).mapError(AuthError(_))
          },
          // OAuth callback
          Method.GET / "oauth" / string("provider") / "callback" -> handler {
            (
              provider: String,
              req:      Request
            ) =>
              (for {
                _ <- ZIO.logInfo(s"OAuth callback received for provider: $provider")
                _ <- ZIO.logInfo(s"Request URL: ${req.url}")

                // Extract code and state from query parameters
                code <- ZIO
                  .fromOption(req.url.queryParams.queryParam("code"))
                  .orElseFail(AuthError("Missing 'code' parameter"))
                state <- ZIO
                  .fromOption(req.url.queryParams.queryParam("state"))
                  .orElseFail(AuthError("Missing 'state' parameter"))

                _ <- ZIO.logInfo(s"OAuth code and state extracted successfully")

                // Validate state (CSRF protection)
                stateData <- stateStore.remove(state).flatMap {
                  ZIO.fromOption(_).orElseFail(AuthError("Invalid or expired state token"))
                }
                (timestamp, stateProvider) = stateData
                _ <- ZIO
                  .fail(AuthError("State token expired"))
                  .when(timestamp.plusMinutes(10).isBefore(LocalDateTime.now()))
                _ <- ZIO
                  .fail(AuthError(s"State provider mismatch: expected $stateProvider, got $provider"))
                  .when(stateProvider != provider)

                _ <- ZIO.logInfo(s"State validated successfully")

                // Exchange code for access token
                oauthProvider <- oauthService.getProvider(provider)
                accessToken   <- oauthProvider.exchangeCodeForToken(code)

                _ <- ZIO.logInfo(s"Access token obtained from provider")

                // Get user info from provider
                userInfo <- oauthProvider.getUserInfo(accessToken)

                _ <- ZIO.logInfo(s"User info obtained: email=${userInfo.email}, providerId=${userInfo.providerId}")

                // Find or create user
                existingOAuthUser <- userByOAuthProvider(provider, userInfo.providerId)
                user <- existingOAuthUser match {
                  case Some(user) =>
                    // User already linked with this OAuth provider
                    ZIO.logInfo(s"Existing OAuth user found: $user") *> ZIO.succeed(user)

                  case None =>
                    ZIO.logInfo(s"No existing OAuth user, checking email") *>
                      // Check if user with same email exists (auto-linking)
                      userByEmail(userInfo.email, false).flatMap {
                        case Some(emailUser) =>
                          // Link OAuth to existing account
                          ZIO.logInfo(s"Linking OAuth to existing user: $emailUser") *>
                            linkOAuthToUser(emailUser, provider, userInfo.providerId, userInfo.rawData)

                        case None =>
                          // Create new user from OAuth
                          ZIO.logInfo(s"Creating new OAuth user") *>
                            createOAuthUser(userInfo, provider, None)
                      }
                }

                _ <- ZIO.logInfo(s"User authenticated: $user")

                // Create session and return JWT tokens
                config <- ZIO.service[AuthConfig]
                responseWithTokens <- addTokens(
                  Session(user, None),
                  Response.seeOther(URL.root) // Redirect to app
                )

                _ <- ZIO.logInfo(s"Tokens added, redirecting to: ${URL.root}")
                _ <- ZIO.logInfo(s"Response headers: ${responseWithTokens.headers}")
              } yield responseWithTokens)
                .mapError(AuthError(_))
                .tapError(e => ZIO.logError(s"OAuth callback error: ${e.getMessage}"))
          }
        )
      }
    }

  protected def addTokens(
    session:  Session[UserType, ConnectionId],
    response: Response
  ): URIO[AuthConfig, Response] =
    for {
      config       <- ZIO.service[AuthConfig]
      accessToken  <- jwtEncode(session, config.accessTTL)
      refreshToken <- jwtEncode(session, config.refreshTTL)
    } yield response
      .addHeader(Header.Authorization.Bearer(accessToken))
      .addCookie(
        Cookie.Response(
          name = config.refreshTokenName,
          content = refreshToken,
          maxAge = Option(config.refreshTTL.plus(1.hour)),
          domain = None,
          path = Option(Path.decode("/refresh")),
          isSecure = true,
          isHttpOnly = true,
          sameSite = Some(SameSite.Strict)
        )
      )

  def login(
    userName:     String,
    password:     String,
    connectionId: Option[ConnectionId]
  ): IO[AuthError, Option[UserType]]

  def logout(): ZIO[Session[UserType, ConnectionId], AuthError, Unit]

  def changePassword(
    userPK:      UserPK,
    newPassword: String
  ): ZIO[Session[UserType, ConnectionId], AuthError, Unit]

  def userByEmail(email: String, allowInactive: Boolean): IO[AuthError, Option[UserType]]
  def userByPK(pk:       UserPK): IO[AuthError, Option[UserType]]

  // OAuth Methods (with default implementations that indicate OAuth is not configured)

  /** Find a user by OAuth provider and provider user ID
    *
    * Default implementation returns None (OAuth not implemented). Override to support OAuth authentication.
    *
    * @param provider
    *   OAuth provider name (e.g., "google", "github")
    * @param providerId
    *   The unique user ID from the OAuth provider
    * @return
    *   The user if found, None otherwise
    */
  def userByOAuthProvider(
    provider:   String,
    providerId: String
  ): IO[AuthError, Option[UserType]] = ZIO.none

  /** Create a new user from OAuth provider information
    *
    * Default implementation fails with AuthError (OAuth not implemented). Override to support OAuth user creation.
    *
    * @param oauthInfo
    *   Normalized OAuth user information
    * @param provider
    *   OAuth provider name (e.g., "google", "github")
    * @param connectionId
    *   Optional connection ID for the session
    * @return
    *   The created user
    */
  def createOAuthUser(
    oauthInfo:    OAuthUserInfo,
    provider:     String,
    connectionId: Option[ConnectionId]
  ): IO[AuthError, UserType] =
    ZIO.fail(
      AuthError(
        "OAuth user creation not implemented. Override createOAuthUser in your AuthServer implementation."
      )
    )

  /** Link an OAuth provider to an existing user account
    *
    * This is called when a user logs in with OAuth and an account with the same email already exists (auto-linking
    * strategy).
    *
    * Default implementation fails with AuthError (OAuth not implemented). Override to support linking OAuth to existing
    * accounts.
    *
    * @param user
    *   The existing user to link to
    * @param provider
    *   OAuth provider name (e.g., "google", "github")
    * @param providerId
    *   The unique user ID from the OAuth provider
    * @param providerData
    *   Raw OAuth data from the provider
    * @return
    *   The updated user with OAuth linked
    */
  def linkOAuthToUser(
    user:         UserType,
    provider:     String,
    providerId:   String,
    providerData: Json
  ): IO[AuthError, UserType] =
    ZIO.fail(
      AuthError(
        "OAuth account linking not implemented. Override linkOAuthToUser in your AuthServer implementation."
      )
    )

  def jwtDecode(token: String): ZIO[AuthConfig, AuthError, JwtClaim] =
    (for {
      javaClock <- Clock.javaClock
      config    <- ZIO.service[AuthConfig]
      tok       <- ZIO.fromTry(Jwt(javaClock).decode(token, config.secretKey.key, Seq(JwtAlgorithm.HS512)))
    } yield tok).mapError {
      case e: JwtExpirationException => ExpiredToken(e.getMessage, e)
      case e: Throwable              => InvalidToken(e.getMessage, Some(e))
    }

  def jwtEncode[ToEncode: JsonEncoder](
    toEncode: ToEncode,
    ttl:      Duration
  ): ZIO[AuthConfig, Nothing, String] =
    for {
      config    <- ZIO.service[AuthConfig]
      javaClock <- Clock.javaClock
    } yield {
      val claim = JwtClaim(toEncode.toJson)
        .issuedNow(using javaClock)
        .expiresIn(ttl.toSeconds)(using javaClock)
      Jwt.encode(claim, config.secretKey.key, JwtAlgorithm.HS512)
    }

  def bearerSessionProvider: HandlerAspect[AuthConfig, Session[UserType, ConnectionId]] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      val refreshUrl = URL.decode("/refresh").toOption.get

      val connectionId: Option[ConnectionId] =
        request.headers
          .find(_.headerName.equalsIgnoreCase("X-Connection-Id"))
          .flatMap { header =>
            (new String(java.util.Base64.getDecoder.decode(header.renderedValue.getBytes)))
              .fromJson[ConnectionId].toOption
          }

      for {
        session <- (request.header(Header.Authorization) match {
          // We got a bearer token, let's decode it
          case Some(Header.Authorization.Bearer(token)) =>
            for {
              _     <- ZIO.fail(InvalidToken("Token is invalid")).whenZIO(isInvalid(token.value.asString))
              claim <- jwtDecode(token.value.asString)
              u     <- claim.decodedContent[Session[UserType, ConnectionId]]
            } yield (u match {
              case authenticated: AuthenticatedSession[UserType, ConnectionId] =>
                authenticated.copy(connectionId = connectionId)
              case unauthenticated: UnauthenticatedSession[UserType, ConnectionId] =>
                unauthenticated.copy(connectionId = connectionId)
            }).asInstanceOf[Session[UserType, ConnectionId]]

          case _ => ZIO.succeed(UnauthenticatedSession(connectionId = connectionId))
        }).catchAll {
          case _: ExpiredToken => ZIO.fail(Response.unauthorized("token_expired"))
          case _: InvalidToken => ZIO.fail(Response.unauthorized)
          case e => ZIO.logErrorCause(Cause.fail(e)) *> ZIO.fail(Response.badRequest(e.getMessage))
        }
      } yield (request, session)
    })

  /** A typical use of this is if you want to invalidate sessions. (works in tandem with invalidateSession)
    */
  def isInvalid(tok: String): IO[AuthError, Boolean] = ZIO.succeed(false)

  def invalidateToken(token: String): IO[AuthError, Unit] = ZIO.unit

  def createUser(
    name:     String,
    email:    String,
    password: String
  ): IO[AuthError, UserType]

  def activateUser(userPK: UserPK): IO[AuthError, Unit] = ZIO.unit // By default users don't need activation.

  def sendEmail(
    subject: String,
    body:    String,
    user:    UserType
  ): IO[AuthError, Unit]

  def getEmailBodyHtml(
    user:    UserType,
    purpose: UserCodePurpose,
    url:     String
  ): String = {
    purpose match {
      case UserCodePurpose.NewUser =>
        s"""Please confirm your registration by clicking on this link:\n<a href="http://localhost:8081/#$url">http://localhost:8081/#$url</a>"""
      case UserCodePurpose.LostPassword =>
        s"""Please change you password by clicking on this link:\n<a href="http://localhost:8081/#$url">http://localhost:8081/#$url</a>"""
    }
  }

}
