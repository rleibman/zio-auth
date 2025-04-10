package auth

import test.Session
import zio.http.Routes
import zio.{UIO, ZIO}

type AuthEnvironment = SessionConfig & UserRepository[UIO] & TokenHolder[UIO] & SessionTransport[Session]

object AuthRoutes extends AppRoutes[AuthEnvironment, Session, AuthError] {

  /** These routes represent the api, the are intended to be used thorough ajax-type calls they require a session
    */
  override def api: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment & Session, AuthError]] =
    ZIO.succeed(Routes.empty)

  /** These routes that bring up resources that require authentication (an existing session)
    */
  override def auth: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment & Session, AuthError]] =
    ZIO.succeed(Routes.empty)
    //index.html (and the /)

  /** These do not require a session
    */
  override def unauth: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment, AuthError]] = ZIO.succeed(Routes.empty)
    //Anything in the unauth directory


}
