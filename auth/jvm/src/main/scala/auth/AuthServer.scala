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

import pdi.jwt.*
import pdi.jwt.exceptions.JwtExpirationException
import zio.*
import zio.http.*
import zio.http.Cookie.SameSite
import zio.json.*

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

trait AuthServer[UserType: {JsonEncoder, JsonDecoder, Tag}, UserPK: {JsonEncoder, JsonDecoder, Tag}] {

  import AuthServer.*

  private given JsonCodec[Some[UserType]] = JsonCodec.derived[Some[UserType]]
  private given JsonCodec[Session[UserType]] = JsonCodec.derived[Session[UserType]]

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

  def authRoutes: URIO[AuthConfig, Routes[AuthConfig & Session[UserType], AuthError]] =
    for {
      config <- ZIO.service[AuthConfig]
    } yield Routes(
      Method.GET / config.whoAmIUrl -> handler { (_: Request) =>
        ZIO.serviceWith[Session[UserType]] {
          case AuthenticatedSession(Some(user)) => json(user)
          case _                          => Response.unauthorized("No session")
        }
      },
      Method.POST / "api" / "changePassword" -> handler { (req: Request) =>
        for {
          user <- ZIO.serviceWithZIO[Session[UserType]] {
            case AuthenticatedSession(user) => ZIO.succeed(user)
            case _                          => ZIO.fail(NotAuthenticated)
          }
          body <- req.body.asString.mapError(e => AuthError(e.getMessage, e))
          newPass = body // Assuming the body contains the new password
          _ <- changePassword(getPK(user.get), newPass)
        } yield Response.ok // TODO change it to Response.status(Status.NoContent) // Nothing is really required back

      },
      Method.GET / config.logoutUrl -> handler { (req: Request) =>
        val token = req.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
        for {
          _ <- invalidateToken(token)
          _ <- logout()
        } yield Response.ok // TODO change it to Response.status(Status.NoContent) // Nothing is really required back
      }
    )

  def unauthRoutes: URIO[AuthConfig, Routes[AuthConfig, AuthError]] =
    for {
      config <- ZIO.service[AuthConfig]
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
              .provideLayer(ZLayer.succeed(Session(user)))
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
            .whenZIO(userByEmail(validated.email).map(_.isDefined))
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
          email:    String,
          password: String
        )
        given JsonDecoder[LoginRequest] = JsonDecoder.derived

        (for {
          loginRequest <- req.body.as[LoginRequest]
          login        <- login(loginRequest.email, loginRequest.password)
          _ <- login.fold(ZIO.logDebug(s"Bad login for ${loginRequest.email}"))(_ =>
            ZIO.logDebug(s"Good Login for ${loginRequest.email}")
          )
          res <- login.fold(ZIO.succeed {
            Response.unauthorized("Could not log on, sorry")
          }) { user =>
            addTokens(
              Session(user),
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
          u     <- claim.decodedContent[Session[UserType]]
          responseWithTokens <- addTokens(
            u,
            Response.ok
          )
        } yield responseWithTokens
      }
    )

  protected def addTokens(
    session:  Session[UserType],
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
    userName: String,
    password: String
  ): IO[AuthError, Option[UserType]]

  def logout(): ZIO[Session[UserType], AuthError, Unit]

  def changePassword(
    userPK:      UserPK,
    newPassword: String
  ): ZIO[Session[UserType], AuthError, Unit]

  def userByEmail(email: String): IO[AuthError, Option[UserType]]
  def userByPK(pk:       UserPK): IO[AuthError, Option[UserType]]

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

  def bearerSessionProvider: HandlerAspect[AuthConfig, Session[UserType]] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      val refreshUrl = URL.decode("/refresh").toOption.get

      for {
        session <- (request.header(Header.Authorization) match {
          // We got a bearer token, let's decode it
          case Some(Header.Authorization.Bearer(token)) =>
            for {
              _     <- ZIO.fail(InvalidToken("Token is invalid")).whenZIO(isInvalid(token.value.asString))
              claim <- jwtDecode(token.value.asString)
              u     <- claim.decodedContent[Session[UserType]]
            } yield u
          case _ => ZIO.succeed(UnauthenticatedSession())
        }).catchAll {
          case _: ExpiredToken => ZIO.fail(Response.unauthorized("token_expired"))
          case _: InvalidToken => ZIO.fail(Response.unauthorized)
          case e => ZIO.fail(Response.badRequest(e.getMessage))
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
