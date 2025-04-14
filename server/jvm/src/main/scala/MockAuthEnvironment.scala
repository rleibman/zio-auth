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

import auth.*
import zio.*
import zio.json.{JsonDecoder, JsonEncoder}

object MockAuthEnvironment {

  type MockAuthEnvironment = AuthEnvironment[MockUser, MockUserId]

  given JsonEncoder[MockUserId] = JsonEncoder.derived

  given JsonDecoder[MockUserId] = JsonDecoder.derived

  given JsonEncoder[MockUser] = JsonEncoder.derived

  given JsonDecoder[MockUser] = JsonDecoder.derived

  case class MockUserId(value: Long)

  case class MockUser(
    userId:   MockUserId,
    name:     String,
    email:    String,
    password: String
  )

  case class MockAuthServer(
    users:         Ref[Map[MockUserId, MockUser]],
    emails:        Ref[Map[String, String]],
    invalidTokens: Ref[Set[String]]
  ) extends AuthServer[MockUser, MockUserId] {

    override def getPK(user: MockUser): MockUserId = user.userId

    override def login(
      loginName: String,
      password:  String
    ): ZIO[Any, AuthError, Option[MockUser]] =
      users.get.map(_.values.find(u => u.email == loginName && u.password == password))

    override def logout(): ZIO[Session[MockUser], AuthError, Unit] =
      ZIO.serviceWithZIO[Session[MockUser]](u => ZIO.logDebug(s"User $u logged out"))

    override def changePassword(
      userPK:      MockUserId,
      newPassword: String
    ): ZIO[Any, AuthError, Unit] =
      users.update { map =>
        val modedUser = map
          .get(userPK).map(u => userPK -> u.copy(password = newPassword))
        map ++ modedUser.toMap
      }

    override def userByEmail(email: String): ZIO[Any, AuthError, Option[MockUser]] =
      users.get.map(_.values.find(_.email == email))

    override def isInvalid(tok: String): IO[AuthError, Boolean] = invalidTokens.get.map(_.contains(tok))

    override def invalidateToken(token: String): IO[AuthError, Unit] = invalidTokens.update(tokens => tokens + token)

    override def createUser(
      name:     String,
      email:    String,
      password: String
    ): IO[AuthError, MockUser] = {
      users.modify { map =>
        val newUser = MockUser(
          userId = MockUserId(map.size),
          name = name,
          email = email,
          password = password
        )

        (newUser, map + (MockUserId(map.size) -> newUser))
      }
    }

    def extractLink(
      emailBody: String
    ): String = {
      val regex = "<a[^>]*>(.*?)</a>".r

      val res = regex.findFirstMatchIn(emailBody) match {
        case Some(m) => m.group(1)
        case None    => ""
      }
      res
    }

    override def sendEmail(
      subject:   String,
      emailBody: String,
      user:      MockUser
    ): IO[AuthError, Unit] = {
      val confirmUrl = extractLink(emailBody)
      emails.update(_ + (user.email -> confirmUrl)) *> ZIO.logInfo(
        s"Sending email to ${user.email} with subject $subject and body $emailBody"
      )
    }

    override def userByPK(pk: MockUserId): IO[AuthError, Option[MockUser]] = users.get.map(_.get(pk))

    def confirmUrlForEmail(email: String): UIO[Option[String]] = {
      emails.get.map(_.get(email))
    }

  }

  private val config = AuthConfig(secretKey = SecretKey("MOCK_SECRET_KEY"))

  val mock: ULayer[AuthEnvironment[MockUser, MockUserId]] = ZLayer.make[AuthEnvironment[MockUser, MockUserId]](
    ZLayer.succeed(config),
    ZLayer.fromZIO(for {
      users <- Ref
        .make(
          Map(
            MockUserId(1) -> MockUser(
              userId = MockUserId(1),
              password = "goodUser1",
              name = "Good User 1",
              email = "goodUser1@example.com"
            ),
            MockUserId(2) -> MockUser(
              userId = MockUserId(2),
              password = "goodUser2",
              name = "Good User 2",
              email = "goodUser2@example.com"
            ),
            MockUserId(3) -> MockUser(
              userId = MockUserId(3),
              password = "goodUser3",
              name = "Good User 3",
              email = "goodUser3@example.com"
            )
          )
        )
      confirmationEmails <- Ref.make(Map.empty[String, String])
      invalidTokens      <- Ref.make(Set.empty[String])
    } yield MockAuthServer(users, confirmationEmails, invalidTokens): AuthServer[MockUser, MockUserId])
  )

}
