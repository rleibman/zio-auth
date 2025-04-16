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
import zio.http.*
import zio.http.Cookie.SameSite

case class AuthConfig(
                       secretKey: SecretKey,

                       // URLs
                       requestPasswordRecoveryUrl: String = "requestPasswordRecovery",
                       confirmPasswordRecoveryUrl: String = "confirmPasswordRecovery",
                       requestRegistrationUrl: String = "requestRegistration",
                       confirmRegistrationUrl: String = "confirmRegistration",
                       loginUrl: String = "login",
                       logoutUrl: String = "logout",
                       refreshUrl: String = "/refresh",
                       whoAmIUrl: String = "api/whoami",

                       // Stuff used for cookie
                       refreshTokenName: String = "X-Refresh-Token",
//                       sessionDomain: Option[String] = None,
//                       sessionPath: Option[Path] = None,
//                       sessionIsSecure: Boolean = true,
//                       sessionIsHttpOnly: Boolean = true,
//                       sessionSameSite: Option[SameSite] = Some(SameSite.Strict),
                       accessTTL: Duration = 1.minutes, //For testing
                       refreshTTL: Duration = 5.minutes,

                       codeExpirationHours: Duration = 2.days
                     )
