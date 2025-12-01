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

import zio.json.ast.Json
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

/** Normalized OAuth user information from any provider
  *
  * This represents the minimal user data needed from OAuth providers, normalized across different providers (Google,
  * GitHub, Discord, etc.)
  *
  * @param providerId
  *   Provider's unique user identifier (e.g., Google's "sub" claim)
  * @param email
  *   User's email address
  * @param name
  *   User's display name
  * @param avatarUrl
  *   Optional URL to user's avatar/profile picture
  * @param emailVerified
  *   Whether the OAuth provider has verified the email
  * @param rawData
  *   Complete raw response from the provider (for extensibility)
  */
case class OAuthUserInfo(
  providerId:    String,
  email:         String,
  name:          String,
  avatarUrl:     Option[String],
  emailVerified: Boolean,
  rawData:       Json
)

object OAuthUserInfo {

  given JsonCodec[OAuthUserInfo] = JsonCodec.derived[OAuthUserInfo]

}
