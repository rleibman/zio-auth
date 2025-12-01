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

/** Generic OAuth 2.0 provider interface
  *
  * Implement this trait to add support for any OAuth 2.0 provider (Google, GitHub, Discord, etc.)
  *
  * Example provider names: "google", "github", "discord"
  */
trait OAuthProvider {

  /** Provider name (lowercase, used in URLs)
    *
    * Examples: "google", "github", "discord"
    */
  def providerName: String

  /** Generate the authorization URL to redirect the user to the OAuth provider
    *
    * This builds the URL with all necessary query parameters (client_id, redirect_uri, scope, state, etc.)
    *
    * @param state
    *   CSRF protection token
    * @return
    *   Authorization URL to redirect the user to
    */
  def generateAuthUrl(state: String): UIO[String]

  /** Exchange the authorization code for an access token
    *
    * After the user authorizes, the provider redirects back with a code. This method exchanges that code for an access
    * token.
    *
    * @param code
    *   Authorization code from the provider
    * @return
    *   Access token
    */
  def exchangeCodeForToken(code: String): IO[AuthError, String]

  /** Fetch user information using the access token
    *
    * Calls the provider's userinfo endpoint and normalizes the response into OAuthUserInfo format.
    *
    * @param accessToken
    *   Access token from exchangeCodeForToken
    * @return
    *   Normalized user information
    */
  def getUserInfo(accessToken: String): IO[AuthError, OAuthUserInfo]

}
