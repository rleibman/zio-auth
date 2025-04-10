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
  //
  //  opaque type MockUserId = Long
  //
  //  object MockUserId {
  //
  //    val empty: MockUserId = MockUserId(0)
  //    val admin: MockUserId = MockUserId(1)
  //
  //    def apply(mockUserId: Long): MockUserId = mockUserId
  //
  //    extension (mockUserId: MockUserId) {
  //
  //      def value: Long = mockUserId
  //      def nonEmpty: Boolean = mockUserId.value != MockUserId.empty
  //
  //    }
  //
  //  }

  case class MockUser(
    userId:   MockUserId,
    password: String,
    email:    String
  )

  case class MockAuthServer(ref: Ref[Map[MockUserId, MockUser]]) extends AuthServer[MockUser, MockUserId] {

    override def getPK(user: MockUser): MockUserId = user.userId

    override def login(
      loginName: String,
      password:  String
    ): ZIO[Any, AuthError, Option[MockUser]] =
      ref.get.map(_.values.find(u => u.email == loginName && u.password == password))

    override def logout(): ZIO[Session[MockUser], AuthError, Unit] = ???

    override def changePassword(
      userPK:      MockUserId,
      newPassword: String
    ): ZIO[Any, AuthError, Unit] =
      ref.update(map => map ++ map.get(userPK).map(u => userPK -> u.copy(password = newPassword)).toMap)

    override def userByEmail(email: String): ZIO[Any, AuthError, Option[MockUser]] =
      ref.get.map(_.values.find(_.email == email))

  }

  private val config = AuthConfig(secretKey = SecretKey("MOCK_SECRET_KEY"))

  val mock: ULayer[AuthEnvironment[MockUser, MockUserId]] = ZLayer.make[AuthEnvironment[MockUser, MockUserId]](
    ZLayer.succeed(config),
    ZLayer.fromZIO(
      Ref
        .make(Map(MockUserId(1) -> MockUser(userId = MockUserId(1), password = "aoeu", email = "aoeu@example.com")))
        .map(ref => MockAuthServer(ref): AuthServer[MockUser, MockUserId])
    )
  )

}
