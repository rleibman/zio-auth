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
import zio.http.*

object TestServer extends ZIOApp {

  import MockAuthEnvironment.*

  override type Environment = MockAuthEnvironment
  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override def bootstrap: ULayer[MockAuthEnvironment] = MockAuthEnvironment.mock

  def testAuthRoutes: ZIO[Any, AuthError, Routes[Session[MockUser], AuthError]] =
    ZIO.succeed(
      Routes(
        Method.GET / "api" / "secured" -> handler((_: Request) => Response.text("Got a secured resource!"))
      )
    )

  val zapp = for {
    authServer   <- ZIO.service[AuthServer[MockUser, MockUserId]]
    authRoutes   <- authServer.authRoutes
    test         <- testAuthRoutes
    unauthRoutes <- authServer.unauthRoutes
  } yield ((authRoutes ++ test) @@ authServer.bearerSessionProvider ++ unauthRoutes @@ Middleware.debug)
    .tapErrorZIO(e => ZIO.logErrorCause(Cause.fail(e)))
    .handleErrorCause { e =>
      e.squash.printStackTrace()
      e.squash.match {
        case AuthBadRequest(msg, _) => Response.error(Status.BadRequest, msg)
        case FileNotFound(file)     => Response.notFound(file)
        case EmailAlreadyExists(e)  => Response.error(Status.Conflict, e)
        case NotAuthenticated       => Response.unauthorized
        case e                      => Response.internalServerError(e.getMessage)
      }
    }

  override def run: ZIO[Environment & ZIOAppArgs, AuthError, ExitCode] =
    for {
      app <- zapp
      server <- Server
        .serve(app)
        .provideSome[Environment](
          Server.live,
          ZLayer.succeed(Server.Config.default.binding("localhost", 8081))
        )
        .foldCauseZIO(
          cause => ZIO.logErrorCause("err when booting server", cause).exitCode,
          _ => ZIO.logError("app quit unexpectedly...").exitCode
        )
    } yield server

}
