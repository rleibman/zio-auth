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

/** Discord OAuth 2.0 provider.
  *
  * Uses Discord's standard OAuth2 flow with the `identify` and `email` scopes. Redirects through
  * `/oauth/discord/login` → Discord consent → `/oauth/discord/callback`.
  */
class DiscordOAuthProvider(config: OAuthProviderConfig) extends OAuthProvider {

  override def providerName: String = "discord"

  override def generateAuthUrl(state: String): UIO[String] = {
    val params = Map(
      "client_id"     -> config.clientId,
      "redirect_uri"  -> config.redirectUri,
      "response_type" -> "code",
      "scope"         -> config.scopes.mkString(" "),
      "state"         -> state,
    )
    val queryString = params.map { case (k, v) => s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("&")
    ZIO.succeed(s"${config.authorizationUri}?$queryString")
  }

  override def exchangeCodeForToken(code: String): IO[AuthError, String] = {
    case class TokenResponse(
      access_token: String,
      token_type:   String,
      scope:        String,
    )

    given JsonDecoder[TokenResponse] = JsonDecoder.derived[TokenResponse]

    val request = basicRequest
      .post(uri"${config.tokenUri}")
      .body(
        Map(
          "client_id"     -> config.clientId,
          "client_secret" -> config.clientSecret,
          "grant_type"    -> "authorization_code",
          "code"          -> code,
          "redirect_uri"  -> config.redirectUri,
        ),
      )
      .response(asJson[TokenResponse])

    ZIO
      .scoped {
        HttpClientZioBackend().flatMap(_.send(request)).flatMap { response =>
          response.body match {
            case Right(t) => ZIO.succeed(t.access_token)
            case Left(e)  => ZIO.fail(AuthError(s"Discord token exchange failed: ${e.getMessage}"))
          }
        }
      }.mapError {
        case e: AuthError => e
        case e: Throwable => AuthError(s"Discord token exchange failed: ${e.getMessage}", e)
      }
  }

  override def getUserInfo(accessToken: String): IO[AuthError, OAuthUserInfo] = {
    case class DiscordUser(
      id:            String,
      username:      String,
      discriminator: String,
      email:         Option[String],
      verified:      Option[Boolean],
      avatar:        Option[String],
    )

    given JsonDecoder[DiscordUser] = JsonDecoder.derived[DiscordUser]

    val request = basicRequest
      .get(uri"${config.userInfoUri}")
      .auth.bearer(accessToken)
      .response(asString)

    ZIO
      .scoped {
        HttpClientZioBackend().flatMap(_.send(request)).flatMap { response =>
          response.body match {
            case Right(jsonStr) =>
              for {
                rawJson <- ZIO.fromEither(jsonStr.fromJson[Json]).mapError(e => AuthError(s"Discord user JSON parse failed: $e"))
                user    <- ZIO.fromEither(jsonStr.fromJson[DiscordUser]).mapError(e => AuthError(s"Discord user decode failed: $e"))
                displayName = if (user.discriminator == "0") user.username else s"${user.username}#${user.discriminator}"
                avatarUrl   = user.avatar.map(hash => s"https://cdn.discordapp.com/avatars/${user.id}/$hash.png")
              } yield OAuthUserInfo(
                providerId    = user.id,
                email         = user.email.getOrElse(""),
                name          = displayName,
                avatarUrl     = avatarUrl,
                emailVerified = user.verified.getOrElse(false),
                rawData       = rawJson,
              )
            case Left(e) =>
              ZIO.fail(AuthError(s"Discord user info failed (${response.code}): $e"))
          }
        }
      }.mapError {
        case e: AuthError => e
        case e: Throwable => AuthError(s"Discord get user info failed: ${e.getMessage}", e)
      }
  }

}

object DiscordOAuthProvider {

  val AuthorizationUri = "https://discord.com/oauth2/authorize"
  val TokenUri         = "https://discord.com/api/oauth2/token"
  val UserInfoUri      = "https://discord.com/api/users/@me"
  val DefaultScopes    = List("identify", "email")

}
