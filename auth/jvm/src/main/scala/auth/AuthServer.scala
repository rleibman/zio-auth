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

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant

object AuthServer {

  extension (body: Body) {

    def as[A: JsonDecoder]: IO[AuthError, A] =
      body.asString
        .flatMap(s =>
          ZIO
            .fromEither(s.fromJson[A]).mapError(e => AuthError(s"Could not parse request body: $e")),
        ).mapError(AuthError(_))

  }

  /** How long an OAuth sign-in may take between the redirect to the provider and its callback. */
  val oauthStateLifetime: Duration = 10.minutes

}

/** The server side of zio-auth: its routes, and the hooks an application implements over its own users.
  *
  * Sessions (since 4.0): a token carries the user's key, never the user ([[TokenClaims]]); every use reads the user
  * again ([[userByPK]]) and checks that it may still have a session ([[mayHaveSession]]), that the token hasn't been
  * revoked ([[isInvalid]]) and that the user's [[sessionEpoch]] hasn't moved on. Access and refresh tokens can't stand
  * in for each other; refreshing rotates the refresh token; logging out revokes both; a password reset ends every
  * session ([[endAllSessions]]) and its code works once.
  */
trait AuthServer[
  UserType:     {JsonEncoder, JsonDecoder, Tag},
  UserPK:       {JsonEncoder, JsonDecoder, Tag},
  ConnectionId: {JsonEncoder, JsonDecoder, Tag},
] {

  import AuthServer.*

  private given JsonCodec[TokenClaims[UserPK, ConnectionId]] = JsonCodec.derived[TokenClaims[UserPK, ConnectionId]]

  extension (claim: JwtClaim) {

    def decodedContent[A: JsonDecoder]: ZIO[Any, AuthError, A] =
      ZIO.fromEither(claim.content.fromJson[A]).mapError(_ => InvalidToken("Token content not recognized"))

  }

  final protected def json[A: JsonEncoder](value: A): Response = Response.json(value.toJson)

  def getPK(user: UserType): UserPK

  /** The bearer token of a request, if it has one. */
  private def bearer(request: Request): Option[String] =
    request.header(Header.Authorization).collect { case Header.Authorization.Bearer(token) => token.value.asString }

  private def clearRefreshCookie(config: AuthConfig): Cookie.Response =
    Cookie.Response(
      name = config.refreshTokenName,
      content = "",
      maxAge = Some(Duration.Zero),
      domain = None,
      path = Some(Path.decode(config.refreshUrl)),
      isSecure = config.secureCookie,
      isHttpOnly = true,
      sameSite = Some(SameSite.Strict),
    )

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
      // Ends this session: its access token and, when the browser sends it, its refresh token (#9). Other sessions of the user go on.
      Method.GET / config.logoutUrl -> handler { (req: Request) =>
        val tokens = bearer(req).toList ++ req.cookie(config.refreshTokenName).map(_.content).filter(_.nonEmpty).toList
        for {
          _ <- ZIO.foreachDiscard(tokens)(invalidateToken)
          _ <- logout()
        } yield Response.ok.addCookie(clearRefreshCookie(config))
      },
    )

  def unauthRoutes: URIO[AuthConfig & OAuthService & OAuthStateStore, Routes[AuthConfig, AuthError]] =
    for {
      config      <- ZIO.service[AuthConfig]
      oauthRoutes <- oauthUnauthRoutes
    } yield Routes(
      // Answers the same whether or not the address has an account; only the logs (at debug) tell them apart, and they don't name the address.
      Method.POST / config.requestPasswordRecoveryUrl -> handler { (req: Request) =>
        for {
          parsed  <- req.body.as[PasswordRecoveryRequest]
          userOpt <- userByEmail(parsed.email)
          _ <- userOpt.fold(ZIO.logDebug("Password recovery requested for an address with no account")) { user =>
            for {
              code <- issueCode(user, TokenType.LostPasswordCode, config.codeExpirationHours)
              confirmUrl = s"${config.passwordRecoveryUrl}?code=$code"
              _ <- sendEmail(
                "Password Recovery Request",
                getEmailBodyHtml(user, UserCodePurpose.LostPassword, confirmUrl),
                user,
              )
            } yield ()
          }
        } yield Response.status(Status.NoContent)
      },
      // The code works once (#9), and the new password ends every session the user had: recovering an account is when that matters most.
      Method.POST / config.passwordRecoveryUrl -> handler((req: Request) =>
        for {
          parsed <- req.body.as[PasswordRecoveryNewPasswordRequest]
          user   <- redeemCode(parsed.confirmationCode, TokenType.LostPasswordCode)
          _ <- changePassword(getPK(user), parsed.password)
            .provideLayer(ZLayer.succeed(Session(user, None)))
          _ <- endAllSessions(getPK(user))
        } yield Response.ok,
      ),
      // Unless the application asks otherwise (`revealExistingEmailOnRegistration`), a taken address gets the same answer as a new one: its owner
      // is told by email instead, so registration can't be used to find out who has an account.
      Method.POST / config.requestRegistrationUrl -> handler((req: Request) =>
        for {
          parsed <- req.body.as[UserRegistrationRequest]
          validated <- ZIO
            .fromEither(
              UserRegistrationRequest
                .validateRequest(parsed.name, parsed.email, parsed.password, parsed.password).toEither,
            ).mapError(e => AuthBadRequest(e.toNonEmptyList.toList.mkString(", ")))
          existing <- userByEmail(validated.email)
          _ <- existing match {
            case Some(_) if config.revealExistingEmailOnRegistration =>
              ZIO.fail(EmailAlreadyExists("That email address is already registered"))
            case Some(user) =>
              sendEmail("Registration attempt", getAccountExistsEmailBodyHtml(user), user)
            case None =>
              for {
                user <- createUser(validated.name, validated.email, validated.password)
                code <- issueCode(user, TokenType.NewUserCode, config.codeExpirationHours)
                confirmUrl = s"${config.confirmRegistrationUrl}?code=$code"
                _ <- sendEmail(
                  "User creation confirmation",
                  getEmailBodyHtml(user, UserCodePurpose.NewUser, confirmUrl),
                  user,
                )
              } yield ()
          }
        } yield Response.status(Status.NoContent),
      ).mapError(AuthError(_)),
      Method.POST / config.confirmRegistrationUrl -> handler { (req: Request) =>
        for {
          confirmationCode <- req.body.as[String]
          user             <- redeemCode(confirmationCode, TokenType.NewUserCode)
          _                <- activateUser(getPK(user))
        } yield Response.status(Status.NoContent)
      },
      Method.GET / "api" / "clientAuthConfig" -> handler((_: Request) =>
        Response.json(
          ClientAuthConfig(
            requestPasswordRecoveryUrl = config.requestPasswordRecoveryUrl,
            requestRegistrationUrl = config.requestRegistrationUrl,
            loginUrl = config.loginUrl,
            logoutUrl = config.logoutUrl,
            refreshUrl = config.refreshUrl,
            whoAmIUrl = config.whoAmIUrl,
          ).toJson,
        ),
      ),
      Method.POST / config.loginUrl -> handler { (req: Request) =>
        case class LoginRequest(
          email:        String,
          password:     String,
          connectionId: Option[ConnectionId],
        )
        given JsonDecoder[LoginRequest] = JsonDecoder.derived

        (for {
          loginRequest <- req.body.as[LoginRequest]
          login        <- login(loginRequest.email, loginRequest.password, loginRequest.connectionId)
          res <- login.filter(mayHaveSession) match {
            case None => ZIO.logDebug("A login failed") *> ZIO.succeed(Response.unauthorized("Could not log on, sorry"))
            case Some(user) => addTokens(Session(user, loginRequest.connectionId), Response.json(user.toJson))
          }
        } yield res).mapError(AuthError(_))
      },
      // A refresh token works once: it is revoked as it is used, and a new pair issued (rotation), so a stolen copy fails once the owner refreshes.
      Method.GET / config.refreshUrl -> handler { (req: Request) =>
        for {
          token <- ZIO
            .fromOption(req.cookie(config.refreshTokenName).map(_.content).filter(_.nonEmpty))
            .orElseFail(InvalidToken("Valid refresh cookie not found"))
          claims <- verify(token, TokenType.Refresh)
          user   <- liveUser(claims)
          _      <- invalidateToken(token)
          fresh  <- addTokens(Session(user, claims.conn), Response.ok, Some(claims.session))
        } yield fresh
      },
    ) ++ oauthRoutes

  /** OAuth routes for login and callback
    *
    *   - GET /oauth/:provider/login - starts the flow: a fresh state, bound to this browser by a short-lived cookie,
    *     and (for providers that support it) a PKCE verifier kept with the state; redirects to the provider.
    *   - GET /oauth/:provider/callback - accepts only a state this browser started (login CSRF), redeems the code with
    *     the verifier, then finds, links or creates the user and starts a session.
    *
    * Logs name the provider and the step, never the callback URL (its code and state), the user's email or the tokens.
    */
  private def oauthUnauthRoutes: URIO[AuthConfig & OAuthService & OAuthStateStore, Routes[AuthConfig, AuthError]] =
    ZIO.serviceWithZIO[OAuthService] { oauthService =>
      ZIO.serviceWith[OAuthStateStore] { stateStore =>
        Routes(
          Method.GET / "oauth" / string("provider") / "login" -> handler {
            (
              provider: String,
              _:        Request,
            ) =>
              (for {
                config        <- ZIO.service[AuthConfig]
                oauthProvider <- oauthService.getProvider(provider)
                state         <- oauthService.generateState()
                verifier      <- Pkce.verifier.map(Option.when(oauthProvider.supportsPkce)(_))
                now           <- Clock.instant
                _             <- stateStore.put(state, OAuthStateStore.Entry(now, provider, verifier))
                authUrl       <- oauthProvider.generateAuthUrl(state, verifier.map(Pkce.challenge))
                url <- ZIO
                  .fromOption(URL.decode(authUrl).toOption)
                  .orElseFail(AuthError(s"Invalid authorization URL for $provider"))
              } yield Response
                .seeOther(url)
                .addCookie(
                  Cookie.Response(
                    name = config.oauthStateCookieName,
                    content = state,
                    maxAge = Some(oauthStateLifetime),
                    domain = None,
                    path = Some(Path.decode("/oauth")),
                    isSecure = config.secureCookie,
                    isHttpOnly = true,
                    // Lax: the provider's redirect back is a cross-site top-level navigation, which must carry it.
                    sameSite = Some(SameSite.Lax),
                  ),
                )).mapError(AuthError(_))
          },
          Method.GET / "oauth" / string("provider") / "callback" -> handler {
            (
              provider: String,
              req:      Request,
            ) =>
              (for {
                config <- ZIO.service[AuthConfig]
                state <- ZIO
                  .fromOption(req.url.queryParams.queryParam("state"))
                  .orElseFail(AuthError("Missing 'state' parameter"))
                // Login CSRF: only the browser that started this sign-in holds the state's cookie.
                cookie = req.cookie(config.oauthStateCookieName).map(_.content)
                _ <- ZIO
                  .fail(AuthError("The sign-in wasn't started in this browser"))
                  .unless(cookie.exists(c => MessageDigest.isEqual(c.getBytes(UTF_8), state.getBytes(UTF_8))))
                entry <- stateStore.remove(state).someOrFail(AuthError("Invalid or expired state token"))
                now   <- Clock.instant
                _ <- ZIO
                  .fail(AuthError("State token expired"))
                  .when(entry.createdAt.plus(oauthStateLifetime).isBefore(now))
                _ <- ZIO
                  .fail(AuthError("State provider mismatch"))
                  .when(entry.provider != provider)
                oauthProvider <- oauthService.getProvider(provider)
                allParams = req.url.queryParams.map.view.mapValues(_.toList).toMap
                code        <- oauthProvider.extractCodeFromCallback(allParams)
                accessToken <- oauthProvider.exchangeCodeForToken(code, entry.codeVerifier)
                userInfo    <- oauthProvider.getUserInfo(accessToken)
                existing    <- userByOAuthProvider(provider, userInfo.providerId)
                user <- existing match {
                  case Some(user) => ZIO.succeed(user)
                  case None =>
                    userByEmail(userInfo.email).flatMap {
                      case Some(emailUser) =>
                        linkOAuthToUser(emailUser, provider, userInfo.providerId, userInfo.rawData)
                      case None => createOAuthUser(userInfo, provider, None)
                    }
                }
                _                  <- ZIO.fail(AuthError("This account can't sign in")).unless(mayHaveSession(user))
                responseWithTokens <- addTokens(Session(user, None), Response.seeOther(URL.root))
              } yield responseWithTokens.addCookie(Cookie.clear(config.oauthStateCookieName)))
                .mapError(AuthError(_))
                .tapError(e => ZIO.logWarning(s"$provider sign-in failed: ${e.getMessage}"))
          },
        )
      }
    }

  /** A new session for `session`'s user: an access token (bearer header) and a refresh token (HttpOnly cookie), sharing
    * one session id (kept on a refresh, fresh on a sign-in), both carrying only the user's key and current
    * [[sessionEpoch]].
    */
  protected def addTokens(
    session:   Session[UserType, ConnectionId],
    response:  Response,
    sessionId: Option[String] = None,
  ): ZIO[AuthConfig, AuthError, Response] =
    session.user match {
      case None => ZIO.fail(NotAuthenticated)
      case Some(user) =>
        for {
          config <- ZIO.service[AuthConfig]
          sid    <- sessionId.fold(TokenClaims.randomId)(ZIO.succeed(_))
          epoch  <- sessionEpoch(user)
          ids    <- TokenClaims.randomId <*> TokenClaims.randomId
          base = TokenClaims[UserPK, ConnectionId](
            TokenType.Access,
            getPK(user),
            session.connectionId,
            sid,
            epoch,
            ids._1,
          )
          accessToken  <- jwtEncode(base, config.accessTTL)
          refreshToken <- jwtEncode(base.copy(typ = TokenType.Refresh, token = ids._2), config.refreshTTL)
        } yield response
          .addHeader(Header.Authorization.Bearer(accessToken))
          .addCookie(
            Cookie.Response(
              name = config.refreshTokenName,
              content = refreshToken,
              maxAge = Option(config.refreshTTL.plus(1.hour)),
              domain = None,
              path = Option(Path.decode(config.refreshUrl)),
              isSecure = config.secureCookie,
              isHttpOnly = true,
              sameSite = Some(SameSite.Strict),
            ),
          )
    }

  /** A one-use code (registration confirmation, password recovery) for `user`. */
  private def issueCode(
    user: UserType,
    typ:  TokenType,
    ttl:  Duration,
  ): ZIO[AuthConfig, AuthError, String] =
    for {
      sid   <- TokenClaims.randomId
      epoch <- sessionEpoch(user)
      code  <- jwtEncode(TokenClaims[UserPK, ConnectionId](typ, getPK(user), None, sid, epoch, sid), ttl)
    } yield code

  /** The user a code of type `typ` was issued to, revoking it as it is used (#9). */
  private def redeemCode(
    code: String,
    typ:  TokenType,
  ): ZIO[AuthConfig, AuthError, UserType] =
    for {
      claims <- verify(code, typ)
      user   <- userByPK(claims.user).someOrFail(InvalidToken("Code is for no account"))
      // A recovery code also stops working once the password changes (the epoch moves on), however it was changed.
      _ <- ZIO.fail(InvalidToken("Code no longer valid")).whenZIO(sessionEpoch(user).map(_ != claims.epoch))
      _ <- invalidateToken(code)
    } yield user

  /** The claims of `token` if it is a valid token of type `typ`, not revoked. */
  private def verify(
    token: String,
    typ:   TokenType,
  ): ZIO[AuthConfig, AuthError, TokenClaims[UserPK, ConnectionId]] =
    for {
      _      <- ZIO.fail(InvalidToken("Token is invalid")).whenZIO(isInvalid(token))
      claim  <- jwtDecode(token)
      claims <- claim.decodedContent[TokenClaims[UserPK, ConnectionId]]
      _      <- ZIO.fail(InvalidToken("Wrong kind of token")).when(claims.typ != typ)
    } yield claims

  /** The user `claims` speak for, read again now: refused if gone, no longer allowed a session, or signed in before its
    * epoch moved on (#7, #9).
    */
  private def liveUser(claims: TokenClaims[UserPK, ConnectionId]): IO[AuthError, UserType] =
    for {
      user  <- userByPK(claims.user).someOrFail(InvalidToken("Session is for no account"))
      _     <- ZIO.fail(InvalidToken("Account can't have a session")).unless(mayHaveSession(user))
      epoch <- sessionEpoch(user)
      _     <- ZIO.fail(InvalidToken("Session ended")).when(epoch != claims.epoch)
    } yield user

  def login(
    userName:     String,
    password:     String,
    connectionId: Option[ConnectionId],
  ): IO[AuthError, Option[UserType]]

  def logout(): ZIO[Session[UserType, ConnectionId], AuthError, Unit]

  def changePassword(
    userPK:      UserPK,
    newPassword: String,
  ): ZIO[Session[UserType, ConnectionId], AuthError, Unit]

  def userByEmail(email: String): IO[AuthError, Option[UserType]]
  def userByPK(pk:       UserPK): IO[AuthError, Option[UserType]]

  /** Whether `user` may have a session at all (e.g. not disabled or deleted). Checked at login and on every use of a
    * token. Default: yes.
    */
  def mayHaveSession(user: UserType): Boolean = true

  /** The user's session epoch: tokens record it when issued and are refused once it differs. Raise it (in
    * [[endAllSessions]], or whenever the password changes) to end every session the user has. Default: always 0, which
    * ends nothing.
    */
  def sessionEpoch(user: UserType): IO[AuthError, Long] = ZIO.succeed(0L)

  /** Ends every session of `userPK`, called after a password reset. Implement it by raising [[sessionEpoch]]. Default:
    * nothing.
    */
  def endAllSessions(userPK: UserPK): IO[AuthError, Unit] = ZIO.unit

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
    providerId: String,
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
    connectionId: Option[ConnectionId],
  ): IO[AuthError, UserType] =
    ZIO.fail(
      AuthError(
        "OAuth user creation not implemented. Override createOAuthUser in your AuthServer implementation.",
      ),
    )

  /** Link an OAuth provider to an existing user account
    *
    * Called when a user signs in with OAuth and an account with the same email exists. Check that the provider verified
    * the email before linking: otherwise anyone who can make a provider account with someone's address takes over their
    * account.
    *
    * Default implementation fails with AuthError (OAuth not implemented).
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
    providerData: Json,
  ): IO[AuthError, UserType] =
    ZIO.fail(
      AuthError(
        "OAuth account linking not implemented. Override linkOAuthToUser in your AuthServer implementation.",
      ),
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
    ttl:      Duration,
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

  /** The session an access token speaks for, checked as for a bearer token (the user read again, its epoch,
    * revocation).
    *
    * This is useful for WebSocket connections where the authorization header cannot be set. Instead, the token can be
    * passed as part of the subscription arguments and validated here.
    */
  def sessionLayerFromToken(
    token:        String,
    connectionId: Option[ConnectionId] = None,
  ): ZIO[AuthConfig, AuthError, ZLayer[Any, Nothing, Session[UserType, ConnectionId]]] =
    for {
      claims <- verify(token, TokenType.Access)
      user   <- liveUser(claims)
    } yield ZLayer.succeed(Session(user, connectionId))

  def bearerSessionProvider: HandlerAspect[AuthConfig, Session[UserType, ConnectionId]] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      val connectionId: Option[ConnectionId] =
        request.headers
          .find(_.headerName.equalsIgnoreCase("X-Connection-Id"))
          .flatMap { header =>
            scala.util
              .Try(String(java.util.Base64.getDecoder.nn.decode(header.renderedValue.getBytes).nn, UTF_8))
              .toOption
              .flatMap(_.fromJson[ConnectionId].toOption)
          }

      for {
        session <- (bearer(request) match {
          case Some(token) =>
            for {
              claims <- verify(token, TokenType.Access)
              user   <- liveUser(claims)
            } yield Session(user, connectionId)
          case None => ZIO.succeed(UnauthenticatedSession[UserType, ConnectionId](connectionId = connectionId))
        }).catchAll {
          case _: ExpiredToken => ZIO.fail(Response.unauthorized("token_expired"))
          case _: InvalidToken => ZIO.fail(Response.unauthorized)
          case NotAuthenticated => ZIO.fail(Response.unauthorized)
          case e                => ZIO.logErrorCause(Cause.fail(e)) *> ZIO.fail(Response.internalServerError)
        }
      } yield (request, session)
    })

  /** Whether `tok` has been revoked: a logged-out session's tokens, a used refresh token, a redeemed code. Implement it
    * with [[invalidateToken]] to make revocation work (a store of revoked tokens, or of their hashes, until they would
    * have expired). Default: nothing is ever revoked.
    */
  def isInvalid(tok: String): IO[AuthError, Boolean] = ZIO.succeed(false)

  /** Revokes `token` (see [[isInvalid]]). Default: nothing. */
  def invalidateToken(token: String): IO[AuthError, Unit] = ZIO.unit

  def createUser(
    name:     String,
    email:    String,
    password: String,
  ): IO[AuthError, UserType]

  def activateUser(userPK: UserPK): IO[AuthError, Unit] = ZIO.unit // By default users don't need activation.

  def sendEmail(
    subject: String,
    body:    String,
    user:    UserType,
  ): IO[AuthError, Unit]

  def getEmailBodyHtml(
    user:    UserType,
    purpose: UserCodePurpose,
    url:     String,
  ): String = {
    purpose match {
      case UserCodePurpose.NewUser =>
        s"""Please confirm your registration by clicking on this link:\n<a href="http://localhost:8081/#$url">http://localhost:8081/#$url</a>"""
      case UserCodePurpose.LostPassword =>
        s"""Please change you password by clicking on this link:\n<a href="http://localhost:8081/#$url">http://localhost:8081/#$url</a>"""
    }
  }

  /** The email sent when someone tries to register an address that already has an account: its owner learns of it, the
    * requester learns nothing.
    */
  def getAccountExistsEmailBodyHtml(user: UserType): String =
    """Someone tried to register a new account with this email address, which already has one. If it was you, sign in instead (or use
      |"Forgot password?"). If it wasn't, you can ignore this email.""".stripMargin

}
