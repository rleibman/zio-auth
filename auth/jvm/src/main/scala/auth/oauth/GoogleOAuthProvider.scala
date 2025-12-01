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

package auth.oauth

import auth.AuthError
import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.ziojson.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Google OAuth 2.0 provider implementation
  *
  * Implements the standard Google OAuth flow using OpenID Connect.
  *
  * @param config
  *   Google OAuth configuration
  */
class GoogleOAuthProvider(config: OAuthProviderConfig) extends OAuthProvider {

  override def providerName: String = "google"

  override def generateAuthUrl(state: String): UIO[String] = {
    val params = Map(
      "client_id"     -> config.clientId,
      "redirect_uri"  -> config.redirectUri,
      "response_type" -> "code",
      "scope"         -> config.scopes.mkString(" "),
      "state"         -> state,
      "access_type"   -> "offline",
      "prompt"        -> "consent"
    )

    val queryString = params.map { case (k, v) => s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("&")
    ZIO.succeed(s"${config.authorizationUri}?$queryString")
  }

  override def exchangeCodeForToken(code: String): IO[AuthError, String] = {
    case class TokenResponse(
      access_token:  String,
      token_type:    String,
      expires_in:    Int,
      refresh_token: Option[String],
      scope:         String
    )

    given JsonDecoder[TokenResponse] = JsonDecoder.derived[TokenResponse]

    val backend = HttpClientZioBackend()

    val request = basicRequest
      .post(uri"${config.tokenUri}")
      .body(
        Map(
          "code"          -> code,
          "client_id"     -> config.clientId,
          "client_secret" -> config.clientSecret,
          "redirect_uri"  -> config.redirectUri,
          "grant_type"    -> "authorization_code"
        )
      )
      .response(asJson[TokenResponse])

    backend
      .flatMap(_.send(request))
      .flatMap { response =>
        response.body match {
          case Right(tokenResponse) => ZIO.succeed(tokenResponse.access_token)
          case Left(error) =>
            ZIO.fail(AuthError(s"Failed to exchange code for token: ${error.getMessage}"))
        }
      }
      .mapError {
        case e: AuthError => e
        case e: Throwable => AuthError(s"Token exchange failed: ${e.getMessage}", e)
      }
  }

  override def getUserInfo(accessToken: String): IO[AuthError, OAuthUserInfo] = {
    case class GoogleUserInfo(
      sub:            String,
      name:           String,
      email:          String,
      email_verified: Boolean,
      picture:        Option[String]
    )

    given JsonDecoder[GoogleUserInfo] = JsonDecoder.derived[GoogleUserInfo]

    val backend = HttpClientZioBackend()

    val request = basicRequest
      .get(uri"${config.userInfoUri}")
      .auth
      .bearer(accessToken)
      .response(asString)

    backend
      .flatMap(_.send(request))
      .flatMap { response =>
        response.body match {
          case Right(jsonStr) =>
            ZIO
              .fromEither(jsonStr.fromJson[Json])
              .mapError(e => AuthError(s"Failed to parse user info JSON: $e"))
              .flatMap { rawJson =>
                ZIO
                  .fromEither(jsonStr.fromJson[GoogleUserInfo])
                  .mapError(e => AuthError(s"Failed to decode Google user info: $e"))
                  .map { googleInfo =>
                    OAuthUserInfo(
                      providerId = googleInfo.sub,
                      email = googleInfo.email,
                      name = googleInfo.name,
                      avatarUrl = googleInfo.picture,
                      emailVerified = googleInfo.email_verified,
                      rawData = rawJson
                    )
                  }
              }
          case Left(error) =>
            ZIO.fail(AuthError(s"Failed to get user info: $error"))
        }
      }
      .mapError {
        case e: AuthError => e
        case e: Throwable => AuthError(s"Get user info failed: ${e.getMessage}", e)
      }
  }

}
