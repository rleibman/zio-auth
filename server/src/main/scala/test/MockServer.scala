package test

import auth.*
import zio.*
import zio.http.*

object AllRoutes extends AppRoutes[AuthEnvironment, Session, AuthError] {

  /** These routes represent the api, the are intended to be used thorough ajax-type calls they require a session
    */
  override def api: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment & Session, AuthError]] =
    for {
      a <- AuthRoutes.api
      t <- TestRoutes.api
    } yield a ++ t

  /** These routes that bring up resources that require authentication (an existing session)
    */
  override def auth: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment & Session, AuthError]] =
    for {
      a <- AuthRoutes.auth
      t <- TestRoutes.auth
    } yield a ++ t

  /** These do not require a session
    */
  override def unauth: ZIO[AuthEnvironment, AuthError, Routes[AuthEnvironment, AuthError]] =
    for {
      a <- AuthRoutes.unauth
      t <- TestRoutes.unauth
    } yield a ++ t

}

object AuthServer extends ZIOApp {

  override type Environment = AuthEnvironment
  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]
  override def bootstrap: ZLayer[ZIOAppArgs, Any, AuthEnvironment] = Mock.mock

  lazy val zapp: ZIO[Environment, AuthError, Routes[Environment, Nothing]] =
    for {
      _ <- ZIO.log("Initializing Routes")
//    sessionTransport <- ZIO.service[SessionTransport[DMScreenSession]]
      unauth <- AllRoutes.unauth
      auth   <- AllRoutes.auth
      api    <- AllRoutes.api
    } yield ???
//  unauth ++
//      auth ++
//      api

  override def run: ZIO[Environment & ZIOAppArgs & Scope, AuthError, ExitCode] = {
    // Configure thread count using CLI
    for {
      app <- zapp
      server <- {
        val serverConfig = ZLayer.succeed(
          Server.Config.default
            .binding("localhost", 8888)
        )

        Server
          .serve(app)
          .zipLeft(ZIO.logDebug(s"Server Started"))
          .tapErrorCause(ZIO.logErrorCause(s"Server has unexpectedly stopped", _))
          .provideSome[Environment](serverConfig, Server.live)
          .foldCauseZIO(
            cause => ZIO.logErrorCause("err when booting server", cause).exitCode,
            _ => ZIO.logError("app quit unexpectedly...").exitCode
          )
      }
    } yield server
  }

}
