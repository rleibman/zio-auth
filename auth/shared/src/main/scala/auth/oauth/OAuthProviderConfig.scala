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

/** Generic OAuth 2.0 provider configuration
  *
  * This is the standard OAuth 2.0 configuration that works for most providers.
  *
  * @param clientId
  *   OAuth 2.0 client ID from provider
  * @param clientSecret
  *   OAuth 2.0 client secret from provider
  * @param authorizationUri
  *   Provider's authorization endpoint URL
  * @param tokenUri
  *   Provider's token exchange endpoint URL
  * @param userInfoUri
  *   Provider's user info endpoint URL
  * @param redirectUri
  *   Callback URL (must match provider configuration)
  * @param scopes
  *   OAuth scopes to request
  */
case class OAuthProviderConfig(
  clientId:         String,
  clientSecret:     String,
  authorizationUri: String,
  tokenUri:         String,
  userInfoUri:      String,
  redirectUri:      String,
  scopes:           List[String]
)
