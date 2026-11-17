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

import zio.*
import zio.json.*

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** What a token is for. Every token says, so none can stand in for another: a refresh token isn't accepted as a bearer
  * token, an access token isn't accepted at `/refresh`, and a session token can't confirm a registration (#8).
  */
enum TokenType(val code: String) {

  case Access extends TokenType("access")

  case Refresh extends TokenType("refresh")

  case NewUserCode extends TokenType("new-user")

  case LostPasswordCode extends TokenType("lost-password")

}

object TokenType {

  given JsonCodec[TokenType] =
    JsonCodec.string.transformOrFail(code => values.find(_.code == code).toRight(s"Unknown token type: $code"), _.code)

}

/** What a token carries (#6): its type, the user's key, the session it belongs to and the user's session epoch, never
  * the user. (Named to avoid JWT's registered claims, `sub` and `jti`, which the JWT library takes out of the content.)
  * The user is read again on every use, so a renamed, disabled or deleted user is seen at once, and nothing personal
  * travels in the (signed, not encrypted) token.
  *
  * @param session
  *   one id per sign-in, shared by its access and refresh tokens
  * @param epoch
  *   the user's [[AuthServer.sessionEpoch]] when the token was issued: raising it ends every session issued before (#9)
  * @param token
  *   this token's own random id, so no two tokens are ever the same string (a rotated refresh token issued within the
  *   second of its predecessor would otherwise equal the one just revoked)
  */
final case class TokenClaims[UserPK, ConnectionId](
  typ:     TokenType,
  user:    UserPK,
  conn:    Option[ConnectionId],
  session: String,
  epoch:   Long,
  token:   String,
)

object TokenClaims {

  private val random = SecureRandom()

  /** A random 128-bit id, hex-encoded, from the platform CSPRNG (never ZIO's `Random`, which isn't cryptographic). */
  def randomId: UIO[String] =
    ZIO.succeed {
      val bytes = new Array[Byte](16)
      random.nextBytes(bytes)
      bytes.map("%02x".format(_)).mkString
    }

}

/** PKCE (RFC 7636): a secret verifier kept with the OAuth state, and its SHA-256 challenge sent to the provider, so an
  * authorization code that leaks from the redirect can't be redeemed by anyone else.
  */
object Pkce {

  private val random = SecureRandom()

  private val base64Url = Base64.getUrlEncoder.nn.withoutPadding.nn

  /** 32 random bytes, Base64url: a 43-character verifier. */
  def verifier: UIO[String] =
    ZIO.succeed {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      base64Url.encodeToString(bytes).nn
    }

  /** The `S256` challenge for `verifier`. */
  def challenge(verifier: String): String =
    base64Url.encodeToString(MessageDigest.getInstance("SHA-256").nn.digest(verifier.getBytes("US-ASCII"))).nn

}
