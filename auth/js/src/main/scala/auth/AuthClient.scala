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

extension (request: Request[Either[String, String]]) {

  private def encodeConnectionId[ConnectionId: JsonEncoder](connectionId: ConnectionId): String = {
    java.util.Base64.getEncoder.encodeToString(connectionId.toJson.getBytes)
  }

  def connectionId[ConnectionId: JsonEncoder](connectionId: Option[ConnectionId]): Request[Either[String, String]] = {
    connectionId.fold(request)(c => request.header("X-Connection-Id", encodeConnectionId(c)))
  }

}

object AuthClient {

  private def asyncJwtToken: AsyncCallback[Option[String]] =
    AsyncCallback.pure(Option(window.localStorage.getItem("jwtToken")))

  private val backend: WebSocketBackend[Future] = FetchBackend(
    // This is necessary so that cookies are sent with the request, including httpOnly cookies
    fetchOptions = FetchOptions(credentials = Some(RequestCredentials.include), mode = None),
  )

  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  /** The login request's body, encoded (never interpolated: a password with a quote or backslash must still be sent as
    * typed, #10).
    */
  private def loginBody[ConnectionId: JsonEncoder](
    email:        String,
    password:     String,
    connectionId: Option[ConnectionId],
  ): String =
    zio.json.ast.Json
      .Obj(
        "email"        -> zio.json.ast.Json.Str(email),
        "password"     -> zio.json.ast.Json.Str(password),
        "connectionId" -> connectionId.flatMap(_.toJsonAST.toOption).getOrElse(zio.json.ast.Json.Null),
      ).toJson

  def login[UserType: JsonDecoder, ConnectionId: JsonEncoder](
    email:        String,
    password:     String,
    connectionId: Option[ConnectionId],
  ): AsyncCallback[Either[String, UserType]] = {
    AsyncCallback
      .fromFuture(
        basicRequest
          .post(uri"/login")
          .contentType("application/json")
          .body(loginBody(email, password, connectionId))
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
              case Left(_) if response.code == StatusCode.Unauthorized =>
                Left("Invalid email or password, sorry, try again.")
              case Left(_) if response.code.isServerError =>
                Left("The server couldn't sign you in just now. Please try again in a moment.")
              case Left(_) =>
                Left(s"Unexpected answer from the server (${response.code.code}).")
            },
          ),
      )
  }

  /** Ends this session on the server (its access token is sent so it can be revoked), then forgets it here and reloads.
    */
  def logout(): AsyncCallback[Unit] =
    for {
      token <- asyncJwtToken
      _ <- AsyncCallback.fromFuture(
        token.fold(basicRequest.get(uri"/logout"))(t => basicRequest.get(uri"/logout").auth.bearer(t)).send(backend),
      )
      _ <- AsyncCallback.delay(window.localStorage.removeItem("jwtToken"))
      _ <- AsyncCallback.delay(window.location.reload())
    } yield ()

  def requestLostPassword(request: PasswordRecoveryRequest): AsyncCallback[Either[String, Unit]] =
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"/requestPasswordRecovery")
        .body(asJson(request))
        .response(asString)
        .send(backend)
        .map(_.body.map(_ => ())),
    )

  def confirmRegistration(confirmationCode: String): AsyncCallback[Either[String, Unit]] = {
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"/confirmRegistration")
        .body(asJson(confirmationCode))
        .response(asString)
        .send(backend)
        .map(_.body.map(_ => ())),
    )
  }

  def whoami[UserType: JsonDecoder, ConnectionId: JsonEncoder](connectionId: Option[ConnectionId])
    : AsyncCallback[Option[UserType]] = {
    withAuth[UserType](
      basicRequest
        .get(uri"/api/whoami")
        .connectionId(connectionId),
      _ => AsyncCallback.unit, // Do nothing with the error
    ).map(_.toOption)
  }

  def clientAuthConfig(): AsyncCallback[ClientAuthConfig] = {
    AsyncCallback.fromFuture(
      basicRequest
        .get(uri"/api/clientAuthConfig")
        .response(asJsonOrFail[ClientAuthConfig])
        .send(backend)
        .map(_.body),
    )
  }

  def requestRegistration(
    request: UserRegistrationRequest,
  ): AsyncCallback[Either[String, String]] = {
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"/requestRegistration")
        .body(asJson(request))
        .response(asString)
        .send(backend)
        .map(_.body),
    )
  }

  def passwordRecovery(
    request: PasswordRecoveryNewPasswordRequest,
  ): AsyncCallback[Either[String, String]] = {
    AsyncCallback.fromFuture(
      basicRequest
        .post(uri"passwordRecovery")
        .body(asJson(request))
        .response(asString)
        .send(backend)
        .map(_.body),
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
      },
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
          .send(backend),
      )
    }

    def tryRefresh(): AsyncCallback[Either[String, A]] = {
      for {
        refreshResponse <- AsyncCallback.fromFuture(
          basicRequest
            .get(uri"/refresh")
            .response(asString)
            .send(backend),
        )
        retried <- refreshResponse.code match {
          case c if c.isSuccess =>
            refreshResponse.header(HeaderNames.Authorization) match {
              case Some(authHeader) =>
                val newToken = authHeader.stripPrefix("Bearer ")
                AsyncCallback.delay {
                  window.localStorage.setItem("jwtToken", newToken)
                } >>
                  doCall(newToken).map(_.body)
              case None =>
                val msg = "Server said refresh was ok, but didn't return a token"
                onAuthError(msg) >> AsyncCallback.pure(Left(msg))
            }
          case c =>
            val msg = s"Refresh failed with status: ${c.code}"
            onAuthError(msg) >> AsyncCallback.pure(Left(msg))
        }
      } yield retried
    }

    for {
      tokOpt      <- asyncJwtToken
      responseOpt <- AsyncCallback.traverseOption(tokOpt)(doCall)
      withRefresh <- responseOpt match {
        case Some(response)
            if response.code == StatusCode.Unauthorized && response.body.left.exists(_.contains("token_expired")) =>
          // Token expired, try to refresh
          tryRefresh()
        case None =>
          // No token in localStorage - might be fresh OAuth login with only refresh cookie
          // Try to refresh first before giving up
          tryRefresh()
        case Some(other) if !other.code.isSuccess =>
          AsyncCallback.pure {
            // Clear the token
            window.localStorage.removeItem("jwtToken")
          } >>
            AsyncCallback.pure(other.body) // Success, or some other "normal" error, pass it along
        case Some(other) =>
          AsyncCallback.pure(other.body) // Success, or some other "normal" error, pass it along
      }
    } yield withRefresh
  }

}
