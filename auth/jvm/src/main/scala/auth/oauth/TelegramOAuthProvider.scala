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
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Telegram Login Widget provider.
  *
  * Telegram does not use standard OAuth 2.0. Instead it uses a Login Widget that POSTs HMAC-signed
  * user data directly to a callback URL. This provider adapts that flow to the OAuthProvider interface:
  *
  *   - `generateAuthUrl` produces a redirect to a Jorlan-hosted page that renders the Telegram Login
  *     Widget. The widget is configured to call back to `/oauth/telegram/callback`.
  *   - `exchangeCodeForToken` is a no-op: the "code" passed by the widget is the raw widget payload
  *     (JSON-encoded query params), which is returned unchanged as the "token".
  *   - `getUserInfo` deserializes and HMAC-verifies the widget payload against the bot token, then
  *     returns the user info. The auth_date field is checked to be within the last 24 hours.
  *
  * Configuration: only `clientSecret` (= bot token) and `redirectUri` are used. The other fields
  * (`clientId`, `authorizationUri`, `tokenUri`, `userInfoUri`) are ignored.
  */
class TelegramOAuthProvider(config: OAuthProviderConfig) extends OAuthProvider {

  override def providerName: String = "telegram"

  override def generateAuthUrl(state: String): UIO[String] =
    ZIO.succeed(s"/telegram-login?state=${java.net.URLEncoder.encode(state, "UTF-8")}")

  /** Pack all Telegram widget params (except `state`) into a JSON string to use as the "code". */
  override def extractCodeFromCallback(queryParams: Map[String, List[String]]): IO[AuthError, String] = {
    val fields = queryParams.collect {
      case (k, v :: _) if k != "state" => k -> v
    }
    if (fields.isEmpty) ZIO.fail(AuthError("Telegram callback missing widget parameters"))
    else ZIO.succeed(fields.toJson)
  }

  /** The "code" is already the JSON payload — pass it through unchanged. */
  override def exchangeCodeForToken(code: String): IO[AuthError, String] =
    ZIO.succeed(code)

  override def getUserInfo(widgetPayloadJson: String): IO[AuthError, OAuthUserInfo] =
    ZIO.fromEither {
      for {
        payload <- widgetPayloadJson.fromJson[TelegramOAuthProvider.WidgetPayload].left.map(e => AuthError(s"Telegram payload parse failed: $e"))
        _       <- TelegramOAuthProvider.verifyHash(payload, config.clientSecret)
        _       <- TelegramOAuthProvider.checkAuthDate(payload.auth_date)
        rawJson <- widgetPayloadJson.fromJson[Json].left.map(e => AuthError(s"Telegram raw JSON parse failed: $e"))
      } yield OAuthUserInfo(
        providerId    = payload.id.toString,
        email         = "",
        name          = List(Some(payload.first_name), payload.last_name).flatten.mkString(" "),
        avatarUrl     = payload.photo_url,
        emailVerified = false,
        rawData       = rawJson,
      )
    }

}

object TelegramOAuthProvider {

  private[oauth] case class WidgetPayload(
    id:         Long,
    first_name: String,
    last_name:  Option[String],
    username:   Option[String],
    photo_url:  Option[String],
    auth_date:  Long,
    hash:       String,
  ) derives JsonDecoder

  /** Verifies the Telegram widget HMAC.
    *
    * The check string is all fields except `hash`, sorted alphabetically, joined as `key=value\n`.
    * The HMAC key is `SHA-256(botToken)`.
    */
  private[oauth] def verifyHash(
    payload:  WidgetPayload,
    botToken: String,
  ): Either[AuthError, Unit] = {
    val checkFields = Map(
      "id"         -> payload.id.toString,
      "first_name" -> payload.first_name,
      "auth_date"  -> payload.auth_date.toString,
    ) ++
      payload.last_name.map("last_name" -> _) ++
      payload.username.map("username" -> _) ++
      payload.photo_url.map("photo_url" -> _)

    val checkStr = checkFields.toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("\n")

    val keyBytes  = MessageDigest.getInstance("SHA-256").digest(botToken.getBytes("UTF-8"))
    val mac       = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"))
    val computed  = mac.doFinal(checkStr.getBytes("UTF-8")).map("%02x".format(_)).mkString

    if (computed == payload.hash) Right(())
    else Left(AuthError("Telegram widget hash verification failed"))
  }

  private[oauth] def checkAuthDate(authDate: Long): Either[AuthError, Unit] = {
    val now     = Instant.now().getEpochSecond
    val maxAge  = 86400L
    if (now - authDate > maxAge) Left(AuthError(s"Telegram auth_date is too old (${now - authDate}s ago)"))
    else Right(())
  }

}
