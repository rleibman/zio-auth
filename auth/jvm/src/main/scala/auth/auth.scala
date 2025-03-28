/*
 * Copyright (c) 2024 Roberto Leibman
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

/*
 * Copyright 2020 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.typesafe.config.{Config as TypesafeConfig, ConfigFactory}
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.{DefaultFullHttpRequest, HttpRequest}
import pdi.jwt.*
import pdi.jwt.exceptions.JwtExpirationException
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import zio.http.*
import zio.http.Cookie.SameSite
import zio.json.*
import zio.json.ast.Json

import java.io.{File, IOException}
import java.net.InetAddress
import java.time.Instant

opaque type SecretKey = String

object SecretKey {

  def apply(key: String): SecretKey = key

}

extension (s: SecretKey) {

  def key: String = s

}

import scala.language.unsafeNulls

class AuthError(
  val msg:   String,
  val cause: Option[Throwable] = None
) extends Exception(msg, cause.orNull) {

  def this(message: String) = this(message, None)

  def this(cause: Throwable) = this("", Some(cause))

}

case class SessionConfig(
  secretKey: SecretKey,

  // Stuff used for cookie
  refreshTokenName:  String = "_refreshToken",
  accessTokenName:   String = "_sessiondata",
  sessionDomain:     Option[String] = None,
  sessionPath:       Option[Path] = None,
  sessionIsSecure:   Boolean = true,
  sessionIsHttpOnly: Boolean = true,
  sessionSameSite:   Option[SameSite] = Some(SameSite.Strict),
  accessTTL:         Duration = 30.minutes,
  refreshTTL:        Duration = 30.days
)

// We're taking a hybrid approach to authentication, using both cookies and bearer tokens. Please see attach auth.md
// for a more detailed explanation of the approach.
trait SessionTransport[SessionType] {

  // Handler aspect that runs on every api call, it checks for the presence of a bearer token
  // and either injects the session into the calls, or redirects as needed
  def apiSessionProvider: HandlerAspect[Any, SessionType]

  // Handler aspect that runs on every resource call, it checks for the presence of a request cookie
  // and either injects the session into the calls, or redirects as needed
  def resourceSessionProvider: HandlerAspect[Any, SessionType]

  def invalidateSession(
    session:  SessionType,
    response: Response
  ): UIO[Response]

  def cleanUp: UIO[Unit]

  def refreshSession(
    request:  Request,
    response: Response
  ): UIO[Response]

  def addTokens(
    session:  SessionType,
    response: Response
  ): UIO[Response]

}
