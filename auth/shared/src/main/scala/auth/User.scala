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

import java.time.LocalDateTime

opaque type UserId = Long

object UserId {

  given CanEqual[UserId, UserId] = CanEqual.derived

  val empty: UserId = UserId(0)
  val admin: UserId = UserId(1)

  def apply(userId: Long): UserId = userId

  extension (userId: UserId) {

    def value:    Long = userId
    def nonEmpty: Boolean = userId.value != UserId.empty

  }

}

trait User {

  def id: UserId

  def email: String

  def name: String

  def created: LocalDateTime

  def lastLoggedIn: Option[LocalDateTime] = None

  def deleted: Boolean = false

  def active: Boolean = false

}

case class UserCreationRequest(
  user:     User,
  password: String
)

case class UserCreationResponse(error: Option[String])
