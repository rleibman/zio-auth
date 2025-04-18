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

import japgolly.scalajs.react.{AsyncCallback, Callback}
import org.scalajs.dom.{RequestCredentials, window}
import sttp.client4.*
import sttp.client4.fetch.{FetchBackend, FetchOptions}
import sttp.client4.ziojson.*
import sttp.model.{HeaderNames, StatusCode}
import zio.json.*

import scala.concurrent.Future

object AuthClient {

  private def asyncJwtToken: AsyncCallback[Option[String]] =
    AsyncCallback.pure(Option(window.localStorage.getItem("jwtToken")))

  private val backend: WebSocketBackend[Future] = FetchBackend(
    // This is necessary so that cookies are sent with the request, including httpOnly cookies
    fetchOptions = FetchOptions(credentials = Some(RequestCredentials.include), mode = None)
  )

  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  def login[UserType: JsonDecoder](
    email:    String,
    password: String
  ): AsyncCallback[Either[String, UserType]] = {
    AsyncCallback
      .fromFuture(
        basicRequest
          .post(uri"/login")
          .body(s"""{"email":"$email","password":"$password"}""")
          .response(asJson[UserType])
          .send(backend)
          .map(response =>
            response.body match {
              case Right(user) =>
                response.header(HeaderNames.Authorization) match {
                  case Some(authHeader) =>
                    window.localStorage.setItem("jwtToken", authHeader.stripPrefix("Bearer "))
                    window.location.reload()
                    Right(user)
                  case None =>
                    Left("No token received")
                }
              case Left(err) if response.code == StatusCode.Unauthorized =>
                println(err.getMessage)
                Left(s"Invalid email or password, sorry, try again.")
              case Left(err) =>
                Left(s"Unexpected error: ${response.code}: ${err.getMessage}")
            }
          )
      ).flatTap {
        case Left(err)   => Callback.log(s"Login failed: $err").asAsyncCallback
        case Right(user) => Callback.log(s"Login succeeded: $user").asAsyncCallback
      }
  }

  def logout(): AsyncCallback[Unit] = {
    for {
      _ <- AsyncCallback.fromFuture(
        basicRequest
          .get(uri"/logout")
          .send(backend)
      )
    } yield {
      AsyncCallback.pure {
        window.localStorage.removeItem("jwtToken")
        window.localStorage.clear()
        window.location.reload()
      }
    }
  }

  def requestLostPassword(request: PasswordRecoveryRequest): AsyncCallback[Either[String, Unit]] =
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"/requestPasswordRecovery")
        .body(asJson(request))
        .response(asString)
        .send(backend)
        .map(_.body.map(_ => ()))
    )

  def confirmRegistration(confirmationCode: String) = {
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"/confirmRegistration")
        .body(asJson(confirmationCode))
        .response(asString)
        .send(backend)
        .map(_.body.map(_ => ()))
    )
  }

  def whoami[UserType: JsonDecoder](): AsyncCallback[Option[UserType]] = {
    withAuth[UserType](
      basicRequest.get(uri"/api/whoami"),
      _ => AsyncCallback.unit // Do nothing with the error
    ).map(_.toOption)
  }

  def clientAuthConfig(): AsyncCallback[ClientAuthConfig] = {
    AsyncCallback.fromFuture(
      basicRequest
        .get(uri"/api/clientAuthConfig")
        .response(asJsonOrFail[ClientAuthConfig])
        .send(backend)
        .map(_.body)
    )
  }

  def requestRegistration(
    request: UserRegistrationRequest
  ): AsyncCallback[Either[String, String]] = {
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"/requestRegistration")
        .body(asJson(request))
        .response(asString)
        .send(backend)
        .map(_.body)
    )
  }

  def passwordRecovery(
    request: PasswordRecoveryNewPasswordRequest
  ): AsyncCallback[Either[String, String]] = {
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"passwordRecovery")
        .body(asJson(request))
        .response(asString)
        .send(backend)
        .map(_.body)
    )
  }

  /** This method is used to make an authenticated request to the server. It will first check if a JWT token is present
    * in local storage. If it is, it will use it to make the request. If the token is not present, it will call the
    * refresh endpoint to get a new token. If the refresh token is not present, it will call the onError function.
    */
  // TODO, might consider moving this to an sttp backend instead
  def withAuth[A: JsonDecoder](
    request: Request[Either[String, String]],
    onAuthError: String => AsyncCallback[Any] = msg =>
      AsyncCallback.pure {
        window.alert(msg)
        window.location.reload()
      }
  ): AsyncCallback[Either[String, A]] = {
    def doCall(tok: String): AsyncCallback[Response[Either[String, A]]] = {
      AsyncCallback.fromFuture(
        request
          .response(asJson[A])
          .auth
          .bearer(tok)
          .mapResponse { e =>
            e.left.map(_.getMessage)
          }
          .send(backend)
      )
    }

    for {
      tokOpt      <- asyncJwtToken
      responseOpt <- AsyncCallback.traverseOption(tokOpt)(doCall)
      withRefresh <- responseOpt match {
        case Some(response)
            if response.code == StatusCode.Unauthorized && response.body.left.exists(_.contains("token_expired")) =>
          Callback.log("Refreshing token").asAsyncCallback >>
            // Call refresh endpoint
            (for {
              refreshResponse <- AsyncCallback.fromFuture(
                basicRequest
                  .get(uri"/refresh")
                  .response(asString)
                  .send(backend)
              )
              retried <- refreshResponse.code match {
                case c if c.isSuccess =>
                  refreshResponse.header(HeaderNames.Authorization) match {
                    case Some(authHeader) =>
                      val newToken = authHeader.stripPrefix("Bearer ")
                      window.localStorage.setItem("jwtToken", newToken)
                      doCall(newToken).map(_.body)
                    case None =>
                      val msg = "Server said refresh was ok, but didn't return a token"
                      onAuthError(msg) >> AsyncCallback.pure(Left(msg))
                  }
                case c =>
                  val msg = s"Trying to get Refresh token got $c"
                  onAuthError(msg) >> AsyncCallback.pure(Left(msg))
              }
            } yield retried)
        case None =>
          val msg = "No token set, please log in"
          onAuthError(msg) >> AsyncCallback.pure(Left(msg))
        case Some(other) if !other.code.isSuccess =>
          AsyncCallback.pure {
            // Clear the token
            window.localStorage.removeItem("jwtToken")
            window.localStorage.clear()
          } >>
            AsyncCallback.pure(other.body) // Success, or some other "normal" error, pass it along
        case Some(other) =>
          AsyncCallback.pure(other.body) // Success, or some other "normal" error, pass it along
      }
    } yield withRefresh
  }

}
