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

import MockAuthEnvironment.{MockAuthEnvironment, MockAuthServer}
import auth.AuthServer.*
import auth.{*, given}
import zio.*
import zio.http.*
import zio.http.Status.NotFound
import zio.json.*
import zio.test.*

object AuthSpec extends ZIOSpec[MockAuthEnvironment] {

  override def bootstrap: ULayer[MockAuthEnvironment] = MockAuthEnvironment.mock

  private val zapp = TestServer.zapp

  private val mockServer: URIO[MockAuthEnvironment, MockAuthServer] =
    ZIO.service[AuthServer[MockUser, MockUserId, MockConnectionId]].map(_.asInstanceOf[MockAuthServer])

  private def accessToken(response: Response): String =
    response.header(Header.Authorization).fold("")(_.renderedValue.stripPrefix("Bearer "))

  private def refreshCookie(response: Response): String =
    response.headers(Header.SetCookie).map(_.value).find(_.name == "X-Refresh-Token").fold("")(_.content)

  def doLogin(
    email:    String,
    password: String,
  ): ZIO[Scope & MockAuthEnvironment, AuthError, Response] = {
    for {
      app    <- zapp
      config <- ZIO.service[AuthConfig]
      r1 <- app
        .run(
          Request.post(
            path = config.loginUrl,
            body = Body.fromString(s"""{"email":"$email","password":"$password"}"""),
          ),
        ).catchAll {
          case Left(e)   => ZIO.fail(e)
          case Right(r1) => ZIO.succeed(r1)
        }
    } yield r1
  }

  override def spec: Spec[MockAuthEnvironment & TestEnvironment & Scope, Any] =
    suite("AuthSpec")(
      test("good login") {
        for {
          r1     <- doLogin("goodUser1@example.com", "goodUser1")
          config <- ZIO.service[AuthConfig]
        } yield assertTrue(
          r1.status.isSuccess,
          r1.header(Header.Authorization).isDefined,
          r1.header(Header.SetCookie).exists(a => a.value.name == config.refreshTokenName),
        )
      },
      test("bad login") {
        for {
          r1 <- doLogin("goodUser1@example.com", "badpassword")
        } yield assertTrue(r1.status == Status.Unauthorized)
      },
      test("changePassword, no token") {
        for {
          r1  <- doLogin("goodUser2@example.com", "goodUser2")
          app <- zapp
          r2 <- app.run(
            Request
              .post("api/changePassword", Body.fromString("newPasswordGoodUser2")),
          )
        } yield assertTrue(r1.status.isSuccess, r2.status == Status.Unauthorized) // There's no token, unauthorized
      },
      test("changePassword, good token") {
        for {
          r1 <- doLogin("goodUser2@example.com", "goodUser2")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          config <- ZIO.service[AuthConfig]
          app    <- zapp
          r2 <- app.run(
            Request
              .post(
                "api/changePassword",
                Body.fromString("newPasswordGoodUser2"),
              ).addHeader(Header.Authorization.Bearer(token)),
          )
          r3 <- app.run(Request.get(config.logoutUrl).addHeader(Header.Authorization.Bearer(token)))
          r4 <- doLogin("goodUser2@example.com", "goodUser2")
          r5 <- doLogin("goodUser2@example.com", "newPasswordGoodUser2")
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status.isSuccess,
          r4.status == Status.Unauthorized, // Trying the original password should fail
          r5.status.isSuccess,
        )
      },
      test("whoami") {
        for {
          r1 <- doLogin("goodUser2@example.com", "goodUser2")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          config <- ZIO.service[AuthConfig]
          app    <- zapp
          r2 <- app.run(
            Request
              .get(config.whoAmIUrl)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          who <- r2.body.as[MockUser]
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          who.email == "goodUser2@example.com",
        )
      },
      test("logout") {
        for {
          r1 <- doLogin("goodUser3@example.com", "goodUser3")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          config <- ZIO.service[AuthConfig]
          app    <- zapp
          r2     <- app.run(Request.get(config.logoutUrl).addHeader(Header.Authorization.Bearer(token)))
          r3     <- app.run(Request.get(config.whoAmIUrl).addHeader(Header.Authorization.Bearer(token)))
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status == Status.Unauthorized, // After logout, token should no longer be valid
        )
      },
      test("requestPaswordRecovery and passwordRecovery, good user") {
        for {
          app    <- zapp
          config <- ZIO.service[AuthConfig]
          authServer <- ZIO
            .service[AuthServer[MockUser, MockUserId, MockConnectionId]].map(_.asInstanceOf[MockAuthServer])
          r1 <- app.run(
            Request.post(config.requestPasswordRecoveryUrl, Body.fromString("""{"email":"goodUser3@example.com"}""")),
          )
          confirmUrlOpt <- authServer.confirmUrlForEmail("goodUser3@example.com")
          _             <- ZIO.fromOption(confirmUrlOpt).orElseFail(new Exception("No confirmation URL found"))
          url = confirmUrlOpt.flatMap(URL.decode(_).toOption).get
          // Extract code from fragment (hash-based routing: #passwordRecovery?code=...)
          fragment = url.fragment.map(_.decoded).getOrElse("")
          code = fragment.split("code=").lastOption.getOrElse("")
          recoveryRequest = PasswordRecoveryNewPasswordRequest(code, "newPasswordGoodUser3").toJson
          r2 <- app.run(Request.post(config.passwordRecoveryUrl, Body.fromString(recoveryRequest)))
          r4 <- doLogin("goodUser3@example.com", "newPasswordGoodUser3")
        } yield assertTrue(
          r1.status.isSuccess,
          fragment.startsWith(config.passwordRecoveryUrl.stripPrefix("/")), // Fragment doesn't have leading /
          r2.status.isSuccess,
          r4.status.isSuccess,
        )
      },
      test("requestPaswordRecovery and passwordRecovery, bad user") {
        for {
          app    <- zapp
          config <- ZIO.service[AuthConfig]
          authServer <- ZIO
            .service[AuthServer[MockUser, MockUserId, MockConnectionId]].map(_.asInstanceOf[MockAuthServer])
          r1 <- app.run(
            Request.post(config.requestPasswordRecoveryUrl, Body.fromString("""{"email":"badUser@example.com"}""")),
          )
          confirmUrl <- authServer.confirmUrlForEmail("badUser@example.com")
        } yield assertTrue(
          r1.status.isSuccess,
          confirmUrl.isEmpty,
        )
      },
      test("requestRegistration and confirmRegistrationUrl: good user") {
        val requestString = UserRegistrationRequest("New User", "newUser@example.com", "newUserPassword").toJson
        for {
          authServer <- ZIO
            .service[AuthServer[MockUser, MockUserId, MockConnectionId]].map(_.asInstanceOf[MockAuthServer])
          config <- ZIO.service[AuthConfig]
          app    <- zapp
          r1 <- app.run(
            Request.post(config.requestRegistrationUrl, Body.fromString(requestString)),
          )
          confirmUrlOpt <- authServer.confirmUrlForEmail("newUser@example.com")
          _             <- ZIO.fromOption(confirmUrlOpt).orElseFail(new Exception("No confirmation URL found"))
          url = confirmUrlOpt.flatMap(URL.decode(_).toOption).get
          // Extract code from fragment (hash-based routing: #confirmRegistration?code=...)
          fragment = url.fragment.map(_.decoded).getOrElse("")
          code = fragment.split("code=").lastOption.getOrElse("")
          r2 <- app.run(Request.post(config.confirmRegistrationUrl, Body.fromString(code.toJson)))
        } yield assertTrue(
          r1.status.isSuccess,
          fragment.startsWith(config.confirmRegistrationUrl.stripPrefix("/")), // Fragment doesn't have leading /
          r2.status.isSuccess,
        )
      },
      test("requestRegistration and confirmRegistrationUrl, existing email") {
        val requestString = UserRegistrationRequest("New User", "goodUser1@example.com", "newUserPassword").toJson
        for {
          config     <- ZIO.service[AuthConfig]
          app        <- zapp
          authServer <- mockServer
          r1         <- app.run(Request.post(config.requestRegistrationUrl, Body.fromString(requestString)))
          subjects   <- authServer.subjectsSentTo("goodUser1@example.com")
          login      <- doLogin("goodUser1@example.com", "newUserPassword")
        } yield assertTrue(
          // The same answer as for a new address (no account enumeration); the owner is told by email; nothing is created or changed.
          r1.status == Status.NoContent,
          subjects.contains("Registration attempt"),
          login.status == Status.Unauthorized,
        )
      },
      test("requestRegistration and confirmRegistrationUrl, Invalid request") {
        val requestString = UserRegistrationRequest("New User !@#!@", "goodUser1", "").toJson
        for {
          config <- ZIO.service[AuthConfig]
          app    <- zapp
          r1     <- app.run(Request.post(config.requestRegistrationUrl, Body.fromString(requestString)))
        } yield assertTrue(
          r1.status == Status.BadRequest,
        )
      },
      test("clientAuthConfig") {
        for {
          config       <- ZIO.service[AuthConfig]
          app          <- zapp
          r1           <- app.run(Request.get("api/clientAuthConfig"))
          clientConfig <- r1.body.as[ClientAuthConfig]
        } yield assertTrue(
          r1.status.isSuccess,
          clientConfig.loginUrl == config.loginUrl,
          clientConfig.logoutUrl == config.logoutUrl,
          clientConfig.refreshUrl == config.refreshUrl,
          clientConfig.whoAmIUrl == config.whoAmIUrl,
        )
      },
      test("Non existent file") {
        for {
          r1 <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/doesntExist").addHeader(Header.Authorization.Bearer(token)),
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status == NotFound,
        )
      },
      test("secured api, good token") {
        for {
          r1 <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token)),
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
        )
      },
      test("secured api, bad token") {
        for {
          app <- zapp
          r1 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer("invalidToken")),
          )
        } yield assertTrue(
          r1.status == Status.Unauthorized,
        )
      },
      test("secured api, expired token") {
        for {
          r1 <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app    <- zapp
          config <- ZIO.service[AuthConfig]
          _      <- TestClock.adjust(config.accessTTL.plus(5.seconds)) // Simulate token expiration
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token)),
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status == Status.Unauthorized, // Should redirect to refresh URL
        )
      },
      test("refresh token") {
        for {
          config <- ZIO.service[AuthConfig]
          r1     <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token)),
          )
          _ <- TestClock.adjust(config.accessTTL.plus(5.seconds)) // Simulate token expiration
          r3 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token)),
          )
          refreshCookie = r1.header(Header.SetCookie).map(_.value.content)
          r4 <- app.run(
            Request
              .get(config.refreshUrl)
              .addHeader(Header.Authorization.Bearer(token))
              .addCookie(Cookie.Request(config.refreshTokenName, refreshCookie.getOrElse(""))),
          )
          token_expired <- r3.body.asString
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status == Status.Unauthorized,
          token_expired == "token_expired",
          r4.status.isSuccess,
        )
      },
      test("refresh token, expired refresh token") {
        for {
          config <- ZIO.service[AuthConfig]
          r1     <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token)),
          )
          _ <- TestClock.adjust(config.refreshTTL.plus(5.seconds)) // Simulate refresh expiration
          r3 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token)),
          )
          refreshCookie = r1.header(Header.SetCookie).map(_.value.content)
          r4 <- app.run(
            Request
              .get(config.refreshUrl)
              .addHeader(Header.Authorization.Bearer(token))
              .addCookie(Cookie.Request(config.refreshTokenName, refreshCookie.getOrElse(""))),
          )
          token_expired <- r3.body.asString
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status == Status.Unauthorized,
          token_expired == "token_expired",
          r4.status == Status.Unauthorized,
        )
      },
      test("concurrent login") {
        for {
          r1 <- doLogin("goodUser1@example.com", "goodUser1")
          _  <- TestClock.adjust(5.seconds) // Simulate some time passing, otherwise we would get the same token
          r2 <- doLogin("goodUser1@example.com", "goodUser1")
          token1 = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          token2 = r2.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r3  <- app.run(Request.get("api/secured").addHeader(Header.Authorization.Bearer(token1)))
          r4  <- app.run(Request.get("api/secured").addHeader(Header.Authorization.Bearer(token2)))
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status.isSuccess,
          r4.status.isSuccess,
          token1 != token2, // Ensure tokens are unique for each session
        )
      },
      test("#9: a password reset ends every session the user had, and its code works only once") {
        for {
          app        <- zapp
          config     <- ZIO.service[AuthConfig]
          authServer <- mockServer
          r1         <- doLogin("goodUser8@example.com", "goodUser8")
          token = accessToken(r1)
          _ <- app.run(
            Request.post(config.requestPasswordRecoveryUrl, Body.fromString("""{"email":"goodUser8@example.com"}""")),
          )
          code <- authServer.confirmUrlForEmail("goodUser8@example.com").map(_.fold("")(_.split("code=").last))
          reset = PasswordRecoveryNewPasswordRequest(code, "newPasswordGoodUser8").toJson
          r2    <- app.run(Request.post(config.passwordRecoveryUrl, Body.fromString(reset)))
          old   <- app.run(Request.get("api/secured").addHeader(Header.Authorization.Bearer(token)))
          again <- app.run(Request.post(config.passwordRecoveryUrl, Body.fromString(reset)))
          fresh <- doLogin("goodUser8@example.com", "newPasswordGoodUser8")
        } yield assertTrue(
          r2.status.isSuccess,
          old.status == Status.Unauthorized,
          !again.status.isSuccess,
          fresh.status.isSuccess,
        )
      },
      test("a forged token is refused before the revocation store is asked (no lookup for an unsigned token)") {
        for {
          app        <- zapp
          authServer <- mockServer
          before     <- authServer.revocationLookups.get
          forged <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer("eyJhbGciOiJIUzUxMiJ9.eyJ4IjoxfQ.forged")),
          )
          after <- authServer.revocationLookups.get
        } yield assertTrue(forged.status == Status.Unauthorized, after == before)
      },
      test("#6: a token carries the user's key, never their email or name") {
        for {
          r1 <- doLogin("goodUser4@example.com", "goodUser4")
          payload = String(java.util.Base64.getUrlDecoder.nn.decode(accessToken(r1).split('.')(1)).nn, "UTF-8")
          refreshPayload = String(java.util.Base64.getUrlDecoder.nn.decode(refreshCookie(r1).split('.')(1)).nn, "UTF-8")
        } yield assertTrue(
          !payload.contains("goodUser4@example.com"),
          !payload.contains("Good User 4"),
          !refreshPayload.contains("goodUser4@example.com"),
          payload.contains("\"typ\":\"access\""),
          refreshPayload.contains("\"typ\":\"refresh\""),
        )
      },
      test("#8: a refresh token isn't accepted as a bearer token, nor an access token at /refresh") {
        for {
          app      <- zapp
          config   <- ZIO.service[AuthConfig]
          r1       <- doLogin("goodUser4@example.com", "goodUser4")
          asBearer <- app.run(Request.get("api/secured").addHeader(Header.Authorization.Bearer(refreshCookie(r1))))
          asRefresh <- app.run(
            Request.get(config.refreshUrl).addCookie(Cookie.Request(config.refreshTokenName, accessToken(r1))),
          )
        } yield assertTrue(asBearer.status == Status.Unauthorized, asRefresh.status == Status.Unauthorized)
      },
      test("#7: a deleted user's tokens stop working at once: no bearer access, no refresh") {
        for {
          app        <- zapp
          config     <- ZIO.service[AuthConfig]
          authServer <- mockServer
          r1         <- doLogin("goodUser5@example.com", "goodUser5")
          _          <- authServer.deleteUser(MockUserId(5))
          secured    <- app.run(Request.get("api/secured").addHeader(Header.Authorization.Bearer(accessToken(r1))))
          refreshed <- app.run(
            Request.get(config.refreshUrl).addCookie(Cookie.Request(config.refreshTokenName, refreshCookie(r1))),
          )
        } yield assertTrue(secured.status == Status.Unauthorized, refreshed.status == Status.Unauthorized)
      },
      test("#7: refreshing rotates the refresh token: the used one is refused afterwards") {
        for {
          app    <- zapp
          config <- ZIO.service[AuthConfig]
          r1     <- doLogin("goodUser6@example.com", "goodUser6")
          first <- app.run(
            Request.get(config.refreshUrl).addCookie(Cookie.Request(config.refreshTokenName, refreshCookie(r1))),
          )
          second <- app.run(
            Request.get(config.refreshUrl).addCookie(Cookie.Request(config.refreshTokenName, refreshCookie(r1))),
          )
          next <- app.run(
            Request.get(config.refreshUrl).addCookie(Cookie.Request(config.refreshTokenName, refreshCookie(first))),
          )
        } yield assertTrue(first.status.isSuccess, second.status == Status.Unauthorized, next.status.isSuccess)
      },
      test("#9: logging out revokes both the access token and the refresh token") {
        for {
          app    <- zapp
          config <- ZIO.service[AuthConfig]
          r1     <- doLogin("goodUser7@example.com", "goodUser7")
          out <- app.run(
            Request
              .get(config.logoutUrl)
              .addHeader(Header.Authorization.Bearer(accessToken(r1)))
              .addCookie(Cookie.Request(config.refreshTokenName, refreshCookie(r1))),
          )
          secured <- app.run(Request.get("api/secured").addHeader(Header.Authorization.Bearer(accessToken(r1))))
          refreshed <- app.run(
            Request.get(config.refreshUrl).addCookie(Cookie.Request(config.refreshTokenName, refreshCookie(r1))),
          )
        } yield assertTrue(
          out.status.isSuccess,
          secured.status == Status.Unauthorized,
          refreshed.status == Status.Unauthorized,
        )
      },
      test("OAuth: a callback needs the state cookie its login set (login CSRF); with it, the PKCE verifier reaches the code exchange") {
        for {
          verifiers  <- Ref.make(List.empty[Option[String]])
          authServer <- ZIO.service[AuthServer[MockUser, MockUserId, MockConnectionId]]
          config     <- ZIO.service[AuthConfig]
          provider = new auth.oauth.OAuthProvider {
            override def providerName:                   String = "stub"
            override def supportsPkce:                   Boolean = true
            override def generateAuthUrl(state: String): UIO[String] = generateAuthUrl(state, None)
            override def generateAuthUrl(
              state:         String,
              codeChallenge: Option[String],
            ): UIO[String] =
              ZIO.succeed(s"https://provider.example/auth?state=$state&challenge=${codeChallenge.getOrElse("")}")
            override def exchangeCodeForToken(code: String): IO[AuthError, String] = exchangeCodeForToken(code, None)
            override def exchangeCodeForToken(
              code:         String,
              codeVerifier: Option[String],
            ): IO[AuthError, String] = verifiers.update(_ :+ codeVerifier).as("provider-token")
            override def getUserInfo(accessToken: String): IO[AuthError, auth.oauth.OAuthUserInfo] =
              ZIO.fail(AuthError("stop here"))
          }
          service = new auth.oauth.OAuthService {
            override def getProvider(name: String): IO[AuthError, auth.oauth.OAuthProvider] = ZIO.succeed(provider)
            override def generateState():           UIO[String] = TokenClaims.randomId
            override def listProviders:             UIO[List[String]] = ZIO.succeed(List("stub"))
          }
          routes <- authServer.unauthRoutes.provide(
            ZLayer.succeed(config),
            ZLayer.succeed(service),
            auth.oauth.OAuthStateStore.live(),
          )
          app = routes.handleError(_ => Response.status(Status.BadRequest)).provideEnvironment(ZEnvironment(config))
          login <- app.runZIO(Request.get("/oauth/stub/login"))
          location = login.header(Header.Location).map(_.renderedValue).getOrElse("")
          state = "state=([^&]+)".r.findFirstMatchIn(location).map(_.group(1).nn).getOrElse("")
          challenge = "challenge=([^&]*)".r.findFirstMatchIn(location).map(_.group(1).nn).getOrElse("")
          cookie = login.headers(Header.SetCookie).map(_.value).find(_.name == config.oauthStateCookieName)
          callback = Request.get(URL.decode(s"/oauth/stub/callback?code=c&state=$state").toOption.get)
          // Another browser (no cookie): refused before the code is used.
          stolen <- app.runZIO(callback)
          before <- verifiers.get
          // The browser that started it.
          _ <- app.runZIO(callback.addCookie(Cookie.Request(config.oauthStateCookieName, cookie.fold("")(_.content))))
          after <- verifiers.get
        } yield assertTrue(
          cookie.exists(c => c.isHttpOnly && c.path.exists(_.encode == "/oauth")),
          !stolen.status.isSuccess,
          before.isEmpty,
          after.size == 1,
          after.head.exists(verifier => Pkce.challenge(verifier) == challenge),
        )
      },
    )

}
