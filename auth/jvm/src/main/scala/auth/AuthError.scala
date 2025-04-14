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

object AuthError {

  import scala.language.unsafeNulls

  def apply(message: String): AuthError = new AuthError(message, null)
  def apply(cause: Throwable): AuthError = {
    cause match {
      case e: AuthError => e
      case _ => new AuthError(cause.getMessage, cause)
    }
  }

  def apply(
    message: String,
    cause:   Throwable
  ): AuthError = new AuthError(message, cause)

}

sealed class AuthError(
  message: String,
  cause:   Throwable
) extends Error(message, cause)

case class ExpiredToken(
  message: String,
  cause:   Throwable
) extends AuthError(message, cause)

import scala.language.unsafeNulls

case class AuthBadRequest(message: String, cause: Option[Throwable] = None) extends AuthError(message, cause.orNull)
case class InvalidToken(message: String, cause: Option[Throwable] = None) extends AuthError(message, cause.orNull)
case class EmailAlreadyExists(message: String) extends AuthError(message, null)
case class FileNotFound(message: String) extends AuthError(message, null)
case object NotAuthenticated extends AuthError("", null)
