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

import io.netty.handler.codec.http.QueryStringDecoder
import pdi.jwt.*
import pdi.jwt.exceptions.JwtExpirationException
import zio.*
import zio.http.*
import zio.http.template.Html
import zio.json.*

trait AuthServer[UserType: {JsonEncoder, JsonDecoder, Tag}, UserPK] {

  given JsonEncoder[Session[UserType]] = JsonEncoder.derived

  given JsonDecoder[Session[UserType]] = JsonDecoder.derived

  extension (request: Request) {

    def formData: ZIO[Any, Throwable, Map[String, String]] = {
      import scala.language.unsafeNulls

      val contentTypeStr = request.header(Header.ContentType).map(_.renderedValue).getOrElse("text/plain")

      for {
        str <- request.body.asString
        _ <- ZIO
          .fail(
            AuthError(
              s"Trying to retrieve form data from a non-form post (content type = ${request.header(Header.ContentType)})"
            )
          )
          .when(!contentTypeStr.contains(MediaType.application.`x-www-form-urlencoded`.subType))
      } yield str
        .split("&").map(_.split("="))
        .collect { case Array(k: String, v: String) =>
          QueryStringDecoder.decodeComponent(k) -> QueryStringDecoder.decodeComponent(v)
        }
        .toMap
    }

  }

  extension (claim: JwtClaim) {

    def session: ZIO[Any, AuthError, Session[UserType]] =
      ZIO.fromEither(claim.content.fromJson[Session[UserType]]).mapError(AuthError(_))

  }

  final protected def json[A: JsonEncoder](value: A): Response = Response.json(value.toJson)

  def getPK(user: UserType): UserPK

  def authRoutes: URIO[AuthConfig, Routes[AuthConfig & Session[UserType], AuthError]] =
    for {
      config <- ZIO.service[AuthConfig]
    } yield Routes(
      Method.GET / config.whoAmIUrl -> handler { (_: Request) =>
        ZIO.serviceWith[Session[UserType]](session => json(session.user))
      },
      Method.POST / "api" / "changePassword" -> handler { (req: Request) =>
        for {
          session <- ZIO.service[Session[UserType]]
          body <- req.body.asString.mapError(e => AuthError(e.getMessage, e))
          newPass = body // Assuming the body contains the new password
          _ <- changePassword(getPK(session.user), newPass)
        } yield Response.ok
      },
      Method.GET / config.logoutUrl -> handler { (_: Request) =>
        logout().as(Response.ok)
      }
    )

  def unauthRoutes: URIO[AuthConfig, Routes[AuthConfig, AuthError]] =
    for {
      config <- ZIO.service[AuthConfig]
    } yield Routes(
      Method.POST / config.requestPasswordRecoveryUrl -> handler((req: Request) => ???),
      Method.POST / config.confirmPasswordRecoveryUrl -> handler((req: Request) => ???),
      Method.POST / config.requestRegistrationUrl -> handler((req: Request) => ???),
      Method.POST / config.confirmRegistrationUrl -> handler((req: Request) => ???),
      Method.GET / "api" / "clientAuthConfig" -> handler((_: Request) => Response.json(ClientAuthConfig(
        requestPasswordRecoveryUrl = config.requestPasswordRecoveryUrl,
        requestRegistrationUrl = config.requestRegistrationUrl,
        loginUrl = config.loginUrl,
        logoutUrl = config.logoutUrl,
        refreshUrl = config.refreshUrl,
        whoAmIUrl = config.whoAmIUrl,
      ).toJson)),
      Method.GET / config.loginUrl -> handler { (_: Request) =>
        ZIO.succeed(Response.html(Html.raw(
          s"""<html>
             |<head>
             |  <title>Login</title>
             |</head>
             |<body>
             |  <form method="POST" action="${config.loginUrl}">
             |    <input type="text" name="email" placeholder="Email" required>
             |    <input type="password" name="password" placeholder="Password" required>
             |    <input type="submit" value="Login">
             |  </form>
             |</body
             |</html>""".stripMargin)))
      },
      Method.POST / config.loginUrl -> handler { (req: Request) =>
        (for {
          formData <- req.formData
          email <- ZIO.fromOption(formData.get("email")).orElseFail(AuthError("Missing email"))
          password <- ZIO.fromOption(formData.get("password")).orElseFail(AuthError("Missing Password"))
          login <- login(email, password)
          _ <- login.fold(ZIO.logDebug(s"Bad login for $email"))(_ => ZIO.logDebug(s"Good Login for $email"))
          loginFormBadUrl <- ZIO
            .fromEither(URL.decode(config.loginFormBadUrl)).mapError(e => AuthError(e.getMessage, e))
          res <- login.fold(ZIO.succeed(Response(Status.SeeOther, Headers(Header.Location(loginFormBadUrl))))) { user =>
            addTokens(
              Session(user),
              Response.redirect(URL.root)
            )
          }
        } yield res).mapError(AuthError(_))
      },
      Method.POST / config.refreshUrl -> handler((req: Request) => ???)
    )

  protected def addTokens(
                           session: Session[UserType],
                           response: Response
                         ): URIO[AuthConfig, Response] =
    for {
      config <- ZIO.service[AuthConfig]
      accessToken <- jwtEncode(session, config.accessTTL)
      refreshToken <- jwtEncode(session, config.refreshTTL)
    } yield response
      .addHeader(Header.Authorization.Bearer(accessToken))
      .addCookie(
        Cookie.Response(
          name = config.refreshTokenName,
          content = refreshToken,
          maxAge = Option(config.refreshTTL.plus(1.hour)),
          domain = config.sessionDomain,
          path = config.sessionPath,
          isSecure = config.sessionIsSecure,
          isHttpOnly = config.sessionIsHttpOnly,
          sameSite = config.sessionSameSite
        )
      )

  def refreshSession(
                      request: Request,
                      response: Response
                    ): ZIO[AuthConfig, Nothing, Response] =
    (for {
      config <- ZIO.service[AuthConfig]
      // Check to see if the refresh token exists AND is valid, otherwise you can't refresh
      sessionCookieOpt = request.cookies.find(_.name == config.refreshTokenName).map(_.content)
      session <- (for {
        claim <- ZIO.foreach(sessionCookieOpt)(jwtDecode)
        sessionOpt <- ZIO.foreach(claim)(_.session)
        session <- sessionOpt
          .fold(ZIO.logInfo(s"No refresh token at all") *> ZIO.fail(Response.unauthorized))(ZIO.succeed)
        _ <- ZIO.fail(Response.unauthorized).whenZIO(isInvalid(session))
      } yield session).catchAll {
        case e: Throwable =>
          ZIO.fail(Response.badRequest(e.getMessage))
        case response: Response =>
          ZIO.fail(response)
      }
      withTokens <- addTokens(session, response)
    } yield withTokens).catchAll(ZIO.succeed)

  def login(
             userName: String,
             password: String
           ): IO[AuthError, Option[UserType]]

  def logout(): ZIO[Session[UserType], AuthError, Unit]

  def changePassword(
                      userPK: UserPK,
                      newPassword: String
                    ): IO[AuthError, Unit]

  def userByEmail(email: String): IO[AuthError, Option[UserType]]

  def jwtDecode(token: String): ZIO[AuthConfig, AuthError, JwtClaim] =
    (for {
      javaClock <- Clock.javaClock
      config <- ZIO.service[AuthConfig]
      tok <- ZIO.fromTry(Jwt(javaClock).decode(token, config.secretKey.key, Seq(JwtAlgorithm.HS512)))
    } yield tok).mapError {
      case e: JwtExpirationException => ExpiredToken(e.getMessage, e)
      case e: Throwable => InvalidToken(e.getMessage, e)
    }

  def jwtEncode(
                 session: Session[UserType],
                 ttl: Duration
               ): ZIO[AuthConfig, Nothing, String] =
    for {
      config <- ZIO.service[AuthConfig]
      javaClock <- Clock.javaClock
    } yield {
      val claim = JwtClaim(session.toJson)
        .issuedNow(javaClock)
        .expiresIn(ttl.toSeconds)(javaClock)
      Jwt.encode(claim, config.secretKey.key, JwtAlgorithm.HS512)
    }

  def bearerSessionProvider: HandlerAspect[AuthConfig, Session[UserType]] =
    HandlerAspect.interceptIncomingHandler(Handler.fromFunctionZIO[Request] { request =>
      for {
        refreshURL <- ZIO.fromEither(URL.decode("/refresh")).orDie
        sessionOpt <- (request.header(Header.Authorization) match {
          // We got a bearer token, let's decode it
          case Some(Header.Authorization.Bearer(token)) =>
            for {
              claim <- jwtDecode(token.value.asString)
              u <- claim.session
            } yield Some(u)
          case _ => ZIO.none
        }).catchAll {
          //        case e: JwtExpirationException =>
          //          ZIO.logInfo(s"Expired access token: ${e.getMessage}") *> ZIO.fail(Response.seeOther(refreshURL))
          case e: AuthError =>
            ZIO.fail(Response.badRequest(e.getMessage))
        }
        session <- sessionOpt
          .fold(ZIO.logInfo(s"No access token at all") *> ZIO.fail(Response.seeOther(refreshURL)))(ZIO.succeed)
        _ <- ZIO
          .fail(Response.unauthorized)
          .whenZIO(
            isInvalid(session)
              .tapError(error =>
                ZIO.logErrorCause(s"Error checking isInvalid from user: ${error.getMessage}", Cause.fail(error))
              )
              .orElseFail(Response.unauthorized)
          )
      } yield (request, session)
    })

  /** A typical use of this is if you want to invalidate sessions. (works in tandem with invalidateSession)
   *
   * @param session
   * @return
   */
  def isInvalid(session: Session[UserType]): IO[AuthError, Boolean] = ZIO.succeed(true)

  def invalidateSession(session: Session[UserType]): IO[AuthError, Unit] = ZIO.unit

}
