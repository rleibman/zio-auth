package test

import auth.*
import zio.*
import zio.http.{HandlerAspect, Request, Response, Routes}

import java.time.LocalDateTime


object Mock {

  val mock: ULayer[AuthEnvironment] = ZLayer.make[AuthEnvironment](
    ZLayer.succeed(SessionConfig(secretKey = SecretKey("SecretKey"))),
    ZLayer.succeed(MockUserRepository),
    ZLayer.succeed(MockedTokenHolder),
    ZLayer.succeed(MockSessionTransport)
  )

}

case class MockUser(
  override val id:           UserId,
  override val email:        String,
  override val name:         String,
  override val created:      LocalDateTime,
  override val lastLoggedIn: Option[LocalDateTime] = None,
  override val deleted:      Boolean = false,
  override val active:       Boolean = false
) extends User

object TestRoutes extends AppRoutes[AuthEnvironment, Session, AuthError] {

  /** These routes represent the api, the are intended to be used thorough ajax-type calls they require a session
    */
  override def api: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment & Session, AuthError]] =
    ZIO.succeed(Routes.empty)

  /** These routes that bring up resources that require authentication (an existing session)
    */
  override def auth: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment & Session, AuthError]] =
    ZIO.succeed(Routes.empty)

  /** These do not require a session
    */
  override def unauth: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment, AuthError]] = ZIO.succeed(Routes.empty)

}

case class Session(user: User)

object MockSessionTransport extends SessionTransport[Session] {

  override def apiSessionProvider: HandlerAspect[Any, Session] = ???

  override def resourceSessionProvider: HandlerAspect[Any, Session] = ???

  override def invalidateSession(
    session:  Session,
    response: Response
  ): UIO[Response] = ???

  override def cleanUp: UIO[Unit] = ???

  override def refreshSession(
    request:  Request,
    response: Response
  ): UIO[Response] = ???

  override def addTokens(
    session:  Session,
    response: Response
  ): UIO[Response] = ???

}

object MockedTokenHolder extends TokenHolder[UIO] {

  override def validateToken(
    tok:     TokenString,
    purpose: TokenPurpose
  ): UIO[Option[User]] = ???

  override def createToken(
    user:    User,
    purpose: TokenPurpose,
    ttl:     Option[zio.Duration]
  ): UIO[Token] = ???

  override def peek(
    tok:     TokenString,
    purpose: TokenPurpose
  ): UIO[Option[User]] = ???

}

object MockUserRepository extends UserRepository[UIO] {

  override def firstLogin: UIO[Option[LocalDateTime]] = ???

  override def login(
    email:    String,
    password: String
  ): UIO[Option[User]] = ???

  override def userByEmail(email: String): UIO[Option[User]] = ???

  override def changePassword(
    user:     User,
    password: String
  ): UIO[Boolean] = ???

  override def upsert(e: User): UIO[User] = ???

  override def get(pk: UserId): UIO[Option[User]] = ???

  override def delete(
    pk:         UserId,
    softDelete: Boolean
  ): UIO[Boolean] = ???

  override def search(search: Option[UserSearch]): UIO[Seq[User]] = ???

  override def count(search: Option[UserSearch]): UIO[Long] = ???

}
