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
import auth.oauth.{OAuthService, OAuthStateStore}
import zio.*
import zio.json.{JsonDecoder, JsonEncoder}

object MockAuthEnvironment {

  type MockAuthEnvironment = AuthEnvironment[MockUser, MockUserId, MockConnectionId]

  case class MockAuthServer(
    users:         Ref[Map[MockUserId, MockUser]],
    emails:        Ref[Map[String, String]],
    invalidTokens: Ref[Set[String]],
    epochs:        Ref[Map[MockUserId, Long]],
    subjects:      Ref[List[(String, String)]],
  ) extends AuthServer[MockUser, MockUserId, MockConnectionId] {

    override def sessionEpoch(user: MockUser): IO[AuthError, Long] = epochs.get.map(_.getOrElse(user.userId, 0L))

    override def endAllSessions(userPK: MockUserId): IO[AuthError, Unit] =
      epochs.update(map => map.updated(userPK, map.getOrElse(userPK, 0L) + 1))

    /** Test hook: forget a user, as if deleted. */
    def deleteUser(userPK: MockUserId): UIO[Unit] = users.update(_ - userPK)

    /** Test hook: the subjects of the emails sent to `email`, oldest first. */
    def subjectsSentTo(email: String): UIO[List[String]] =
      subjects.get.map(_.collect { case (`email`, subject) => subject })

    override def getPK(user: MockUser): MockUserId = user.userId

    override def login(
      loginName:    String,
      password:     String,
      connectionId: Option[MockConnectionId],
    ): ZIO[Any, AuthError, Option[MockUser]] =
      users.get.map(_.values.find(u => u.email == loginName && u.password == password))

    override def logout(): ZIO[Session[MockUser, MockConnectionId], AuthError, Unit] =
      ZIO.serviceWithZIO[Session[MockUser, MockConnectionId]](u => ZIO.logDebug(s"User $u logged out"))

    override def changePassword(
      userPK:      MockUserId,
      newPassword: String,
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
      password: String,
    ): IO[AuthError, MockUser] = {
      users.modify { map =>
        // One past the largest id (map.size would reuse the last seeded user's id and overwrite it).
        val id = MockUserId(map.keys.map(_.value).maxOption.getOrElse(0L) + 1)
        val newUser = MockUser(
          userId = id,
          name = name,
          email = email,
          password = password,
        )

        (newUser, map + (id -> newUser))
      }
    }

    def extractLink(
      emailBody: String,
    ): String = {
      val regex = "<a[^>]*>(.*?)</a>".r

      val res = regex.findFirstMatchIn(emailBody) match {
        case Some(m) => m.group(1)
        case None    => ""
      }
      res.asInstanceOf[String]
    }

    override def sendEmail(
      subject:   String,
      emailBody: String,
      user:      MockUser,
    ): IO[AuthError, Unit] = {
      val confirmUrl = extractLink(emailBody)
      subjects.update(_ :+ (user.email -> subject)) *> emails.update(_ + (user.email -> confirmUrl)) *> ZIO.logInfo(
        s"Sending email to ${user.email} with subject $subject and body $emailBody",
      )
    }

    override def userByPK(pk: MockUserId): IO[AuthError, Option[MockUser]] = users.get.map(_.get(pk))

    def confirmUrlForEmail(email: String): UIO[Option[String]] = {
      emails.get.map(_.get(email))
    }

  }

  private val config = AuthConfig(secretKey = SecretKey("MOCK_SECRET_KEY"))

  val mock: ULayer[AuthEnvironment[MockUser, MockUserId, MockConnectionId]] =
    ZLayer.make[AuthEnvironment[MockUser, MockUserId, MockConnectionId]](
      ZLayer.succeed(config),
      OAuthService.live(), // No OAuth providers configured for test server
      OAuthStateStore.live(),
      ZLayer.fromZIO(
        for {
          users <- Ref
            .make(
              Map(
                MockUserId(1) -> MockUser(
                  userId = MockUserId(1),
                  password = "goodUser1",
                  name = "Good User 1",
                  email = "goodUser1@example.com",
                ),
                MockUserId(2) -> MockUser(
                  userId = MockUserId(2),
                  password = "goodUser2",
                  name = "Good User 2",
                  email = "goodUser2@example.com",
                ),
                MockUserId(3) -> MockUser(
                  userId = MockUserId(3),
                  password = "goodUser3",
                  name = "Good User 3",
                  email = "goodUser3@example.com",
                ),
                MockUserId(4) -> MockUser(
                  userId = MockUserId(4),
                  password = "goodUser4",
                  name = "Good User 4",
                  email = "goodUser4@example.com",
                ),
                MockUserId(5) -> MockUser(
                  userId = MockUserId(5),
                  password = "goodUser5",
                  name = "Good User 5",
                  email = "goodUser5@example.com",
                ),
                MockUserId(6) -> MockUser(
                  userId = MockUserId(6),
                  password = "goodUser6",
                  name = "Good User 6",
                  email = "goodUser6@example.com",
                ),
                MockUserId(7) -> MockUser(
                  userId = MockUserId(7),
                  password = "goodUser7",
                  name = "Good User 7",
                  email = "goodUser7@example.com",
                ),
                MockUserId(8) -> MockUser(
                  userId = MockUserId(8),
                  password = "goodUser8",
                  name = "Good User 8",
                  email = "goodUser8@example.com",
                ),
              ),
            )
          confirmationEmails <- Ref.make(Map.empty[String, String])
          invalidTokens      <- Ref.make(Set.empty[String])
          epochs             <- Ref.make(Map.empty[MockUserId, Long])
          subjects           <- Ref.make(List.empty[(String, String)])
        } yield MockAuthServer(users, confirmationEmails, invalidTokens, epochs, subjects): AuthServer[
          MockUser,
          MockUserId,
          MockConnectionId,
        ],
      ),
    )

}
