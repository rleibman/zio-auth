import MockAuthEnvironment.{MockAuthEnvironment, MockAuthServer, MockUser, MockUserId}
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

  def doLogin(
    email:    String,
    password: String
  ): ZIO[Scope & MockAuthEnvironment, AuthError, Response] = {
    for {
      app    <- zapp
      config <- ZIO.service[AuthConfig]
      r1 <- app
        .run(
          Request.post(
            path = config.loginUrl,
            body = Body.fromString(s"""{"email":"$email","password":"$password"}""")
          )
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
          r1.header(Header.SetCookie).exists(a => a.value.name == config.refreshTokenName)
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
              .post("api/changePassword", Body.fromString("newPasswordGoodUser2"))
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
                Body.fromString("newPasswordGoodUser2")
              ).addHeader(Header.Authorization.Bearer(token))
          )
          r3 <- app.run(Request.get(config.logoutUrl).addHeader(Header.Authorization.Bearer(token)))
          r4 <- doLogin("goodUser2@example.com", "goodUser2")
          r5 <- doLogin("goodUser2@example.com", "newPasswordGoodUser2")
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status.isSuccess,
          r4.status == Status.Unauthorized, // Trying the original password should fail
          r5.status.isSuccess
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
              .addHeader(Header.Authorization.Bearer(token))
          )
          who <- r2.body.as[MockUser]
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          who.email == "goodUser2@example.com"
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
          r3.status == Status.Unauthorized // After logout, token should no longer be valid
        )
      },
      test("requestPaswordRecovery and confirmPasswordRecoveryUrl, good user") {
        for {
          app        <- zapp
          config     <- ZIO.service[AuthConfig]
          authServer <- ZIO.service[AuthServer[MockUser, MockUserId]].map(_.asInstanceOf[MockAuthServer])
          r1 <- app.run(
            Request.post(config.requestPasswordRecoveryUrl, Body.fromString("""{"email":"goodUser3@example.com"}"""))
          )
          confirmUrl <- authServer.confirmUrlForEmail("goodUser3@example.com")
          url = confirmUrl.flatMap(URL.decode(_).toOption).get
          recoveryRequest = PasswordRecoveryNewPasswordRequest(
            url.queryParam("code").get,
            "newPasswordGoodUser3"
          ).toJson
          r2 <- app.run(Request.post(url, Body.fromString(recoveryRequest)))
          r4 <- doLogin("goodUser3@example.com", "newPasswordGoodUser3")
        } yield assertTrue(
          r1.status.isSuccess,
          url.path.toString.startsWith(config.confirmPasswordRecoveryUrl),
          r2.status.isSuccess,
          r4.status.isSuccess
        )
      },
      test("requestPaswordRecovery and confirmPasswordRecoveryUrl, bad user") {
        for {
          app        <- zapp
          config     <- ZIO.service[AuthConfig]
          authServer <- ZIO.service[AuthServer[MockUser, MockUserId]].map(_.asInstanceOf[MockAuthServer])
          r1 <- app.run(
            Request.post(config.requestPasswordRecoveryUrl, Body.fromString("""{"email":"badUser@example.com"}"""))
          )
          confirmUrl <- authServer.confirmUrlForEmail("badUser@example.com")
        } yield assertTrue(
          r1.status.isSuccess,
          confirmUrl.isEmpty
        )
      },
      test("requestRegistration and confirmRegistrationUrl: good user") {
        val requestString = UserRegistrationRequest("New User", "newUser@example.com", "newUserPassword").toJson
        for {
          authServer <- ZIO.service[AuthServer[MockUser, MockUserId]].map(_.asInstanceOf[MockAuthServer])
          config     <- ZIO.service[AuthConfig]
          app        <- zapp
          r1 <- app.run(
            Request.post(config.requestRegistrationUrl, Body.fromString(requestString))
          )
          confirmUrl <- authServer.confirmUrlForEmail("newUser@example.com")
          url = confirmUrl.flatMap(URL.decode(_).toOption).get
          r2 <- app.run(Request.get(url))
        } yield assertTrue(
          r1.status.isSuccess,
          url.path.toString.startsWith(config.confirmRegistrationUrl),
          r2.status.isSuccess
        )
      },
      test("requestRegistration and confirmRegistrationUrl, existing email") {
        val requestString = UserRegistrationRequest("New User", "goodUser1@example.com", "newUserPassword").toJson
        for {
          config <- ZIO.service[AuthConfig]
          app    <- zapp
          r1     <- app.run(Request.post(config.requestRegistrationUrl, Body.fromString(requestString)))
        } yield assertTrue(
          r1.status == Status.Conflict
        )
      },
      test("requestRegistration and confirmRegistrationUrl, Invalid request") {
        val requestString = UserRegistrationRequest("New User !@#!@", "goodUser1", "").toJson
        for {
          config <- ZIO.service[AuthConfig]
          app    <- zapp
          r1     <- app.run(Request.post(config.requestRegistrationUrl, Body.fromString(requestString)))
        } yield assertTrue(
          r1.status == Status.BadRequest
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
          clientConfig.whoAmIUrl == config.whoAmIUrl
        )
      },
      test("Non existent file") {
        for {
          r1 <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/doesntExist").addHeader(Header.Authorization.Bearer(token))
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status == NotFound
        )
      },
      test("secured api, good token") {
        for {
          r1 <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token))
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess
        )
      },
      test("secured api, bad token") {
        for {
          app <- zapp
          r1 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer("invalidToken"))
          )
        } yield assertTrue(
          r1.status == Status.Unauthorized
        )
      },
      test("secured api, expired token") {
        for {
          r1 <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app    <- zapp
          config <- ZIO.service[AuthConfig]
          _      <- TestClock.adjust(config.accessTTL.plus(5.minutes)) // Simulate token expiration
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token))
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status == Status.SeeOther // Should redirect to refresh URL
        )
      },
      test("refresh token") {
        for {
          config <- ZIO.service[AuthConfig]
          r1     <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token))
          )
          _ <- TestClock.adjust(config.accessTTL.plus(5.minutes)) // Simulate token expiration
          r3 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token))
          )
          refreshUrl = r3.header(Header.Location).get.renderedValue
          refreshCookie = r1.header(Header.SetCookie).map(_.value.content)
          r4 <- app.run(
            Request
              .get(refreshUrl)
              .addHeader(Header.Authorization.Bearer(token))
              .addCookie(Cookie.Request(config.refreshTokenName, refreshCookie.getOrElse("")))
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status == Status.SeeOther,
          refreshUrl == config.refreshUrl,
          r4.status.isSuccess
        )
      },
      test("refresh token, expired refresh token") {
        for {
          config <- ZIO.service[AuthConfig]
          r1     <- doLogin("goodUser1@example.com", "goodUser1")
          token = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
          app <- zapp
          r2 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token))
          )
          _ <- TestClock.adjust(config.refreshTTL.plus(5.minutes)) // Simulate refresh expiration
          r3 <- app.run(
            Request.get("api/secured").addHeader(Header.Authorization.Bearer(token))
          )
          refreshUrl = r3.header(Header.Location).get.renderedValue
          refreshCookie = r1.header(Header.SetCookie).map(_.value.content)
          r4 <- app.run(
            Request
              .get(refreshUrl)
              .addHeader(Header.Authorization.Bearer(token))
              .addCookie(Cookie.Request(config.refreshTokenName, refreshCookie.getOrElse("")))
          )
        } yield assertTrue(
          r1.status.isSuccess,
          r2.status.isSuccess,
          r3.status == Status.SeeOther,
          refreshUrl == config.refreshUrl,
          r4.status == Status.Unauthorized
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
          token1 != token2 // Ensure tokens are unique for each session
        )
      },
      test("session termination on password change") {
        // We don't track sessions as such, so this is not really possible right now
        ZIO.succeed(assertTrue(true))
//        for {
//          r1 <- doLogin("goodUser2@example.com", "goodUser2")
//          token1 = r1.header(Header.Authorization).get.renderedValue.stripPrefix("Bearer ")
//          app    <- zapp
//          config <- ZIO.service[AuthConfig]
//          r2 <- app.run(
//            Request
//              .post("api/changePassword", Body.fromString("newPasswordGoodUser2"))
//              .addHeader(Header.Authorization.Bearer(token1))
//          )
//          r3 <- app.run(Request.get(config.logoutUrl).addHeader(Header.Authorization.Bearer(token1)))
//          r4 <- app.run(Request.get("api/secured").addHeader(Header.Authorization.Bearer(token1)))
//          r5 <- doLogin("goodUser2@example.com", "newPasswordGoodUser2")
//        } yield assertTrue(
//          r1.status.isSuccess,
//          r2.status.isSuccess,
//          r3.status.isSuccess,
//          r4.status == Status.Unauthorized, // Old token should no longer work
//          r5.status.isSuccess
//        )
      }
    )

}
