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

case class UserSearch(
  text:      String,
  pageIndex: Int = 0,
  pageSize:  Int = 0
)

trait UserRepository[F[_]] {

  def firstLogin: F[Option[LocalDateTime]]

  def login(
    email:    String,
    password: String
  ): F[Option[User]]

  def userByEmail(email: String): F[Option[User]]

  def changePassword(
    user:     User,
    password: String
  ): F[Boolean]

  def upsert(e: User):   F[User]
  def get(pk:   UserId): F[Option[User]]
  def delete(
    pk:         UserId,
    softDelete: Boolean = false
  ):                                             F[Boolean]
  def search(search: Option[UserSearch] = None): F[Seq[User]]
  def count(search:  Option[UserSearch] = None): F[Long]

}
