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
import sttp.model.StatusCode
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
                response.headers.find(_.name.equalsIgnoreCase("Authorization")) match {
                  case Some(authHeader) =>
                    window.localStorage.setItem("jwtToken", authHeader.value.stripPrefix("Bearer "))
                    window.location.reload()
                    Right(user)
                  case None =>
                    Left("No token received")
                }
              case Left(err) if response.code == StatusCode.Unauthorized =>
                Left(s"Invalid email or password: ${err.getMessage}")
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

  def whoami[UserType: JsonDecoder](): AsyncCallback[Option[UserType]] = {
    for {
      tokOpt <- asyncJwtToken
      user <- AsyncCallback.traverse(tokOpt) { tok =>
        AsyncCallback.fromFuture(
          basicRequest
            .get(uri"/api/whoami")
            .auth
            .bearer(tok)
            .response(asJsonOrFail[Option[UserType]])
            .send(backend)
            .map(_.body)
        )
      }
    } yield user.flatten.headOption
  }

  extension [A: JsonDecoder](request: Request[A]) {

    def toAsyncCallback: AsyncCallback[Option[A]] = {
      for {
        tokOpt <- asyncJwtToken
        res <- AsyncCallback.traverse(tokOpt) { tok =>
          AsyncCallback.fromFuture(
            request.auth
              .bearer(tok)
              .send(backend)
              .map(_.body)
          )
        }
      } yield res.headOption
    }

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
        .post(uri"/api/requestRegistration")
        .body(asJson(request))
        .response(asString)
        .send(backend)
        .map(_.body)
    )
  }

}
