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

import cats.data.*
import cats.implicits.given
import cats.syntax.all.given
import zio.json.JsonCodec

object PasswordRecoveryNewPasswordRequest {

  def validateRequest(
    confirmationCode: String,
    password:         String,
    repeatPassword:   String
  ): ValidatedNec[String, PasswordRecoveryNewPasswordRequest] = {
    val passwordVal =
      if (password.length >= 8) password.validNec else "Password must be at least 8 characters long".invalidNec
    val repeatPasswordVal =
      if (repeatPassword == password) repeatPassword.validNec else "Passwords do not match".invalidNec

    (
      passwordVal,
      repeatPasswordVal
    ).mapN(
      (
        password,
        _
      ) => PasswordRecoveryNewPasswordRequest(password = password, confirmationCode = confirmationCode)
    )
  }

}

case class PasswordRecoveryNewPasswordRequest(
  confirmationCode: String,
  password:         String = ""
)

given JsonCodec[PasswordRecoveryNewPasswordRequest] = JsonCodec.derived[PasswordRecoveryNewPasswordRequest]
