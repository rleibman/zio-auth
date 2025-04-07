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

import zio.{Random, UIO}

import java.math.BigInteger
import java.time.LocalDateTime

enum UserCodePurpose(override val toString: String) {

  case NewUser extends UserCodePurpose(toString = "NewUser")
  case LostPassword extends UserCodePurpose(toString = "LostPassword")

}

opaque type UserCodeString = String

object UserCodeString {

  given CanEqual[UserCodeString, UserCodeString] = CanEqual.derived

  def random: UIO[UserCodeString] = Random.nextBytes(16).map(r => new BigInteger(r.toArray).toString(32))

  def apply(str: String): UserCodeString = str

  extension (str: UserCodeString) {

    def str: String = str

  }

}

case class UserCode[UserPK](
  tok:        UserCodeString,
  purpose:    UserCodePurpose,
  expireTime: LocalDateTime,
  userPK:     UserPK
) {

  override def toString: String = tok.str

}
