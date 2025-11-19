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
import zio.json.*

import java.io.File
import java.nio.file.{Files, Paths as JPaths}

object TestServer extends ZIOApp {

  import MockAuthEnvironment.*

  override type Environment = MockAuthEnvironment
  override val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override def bootstrap: ULayer[MockAuthEnvironment] = MockAuthEnvironment.mock

  def testAuthRoutes: ZIO[Any, AuthError, Routes[Session[MockUser, MockConnectionId], AuthError]] =
    ZIO.succeed(
      Routes(
        Method.GET / "api" / "secured" -> handler((_: Request) => Response.json("Got a secured resource!".toJson))
      )
    )

  private def file(
    fileName: String
  ): IO[FileNotFound, File] = {
    JPaths.get(fileName) match {
      case path: java.nio.file.Path if !Files.exists(path) => ZIO.fail(FileNotFound(fileName))
      case path: java.nio.file.Path                        => ZIO.succeed(path.toFile.nn)
      case null => ZIO.fail(FileNotFound(fileName))
    }
  }

  val staticContentDir: String = "/home/rleibman/projects/zio-auth2/debugDist"

  def testUnauthRoutes: ZIO[Any, AuthError, Routes[Any, AuthError]] =
    ZIO.succeed(
      Routes(
        Method.GET / Root -> handler { (_: Request) =>
          // This needs to move outside of the auth server
          Handler.fromFileZIO(file(s"$staticContentDir/index.html")).mapError(AuthError(_))
        }.flatten,
        Method.GET / trailing ->
          // This needs to move outside of the auth server
          handler {
            (
              path: Path,
              _:    Request
            ) =>
              file(s"$staticContentDir/${path.toString}")
                .map(f => Handler.fromFile(f).mapError(AuthError(_)))
                .catchAll { e =>
                  ZIO.succeed(Handler.succeed(Response.notFound(e.getMessage)))
                }
          }.flatten
      )
    )

  val zapp = for {
    authServer       <- ZIO.service[AuthServer[MockUser, MockUserId, MockConnectionId]]
    authRoutes       <- authServer.authRoutes
    authTestRoutes   <- testAuthRoutes
    unauthRoutes     <- authServer.unauthRoutes
    unauthTestRoutes <- testUnauthRoutes
  } yield (((authRoutes ++ authTestRoutes) @@ authServer.bearerSessionProvider ++ (unauthRoutes ++ unauthTestRoutes)) @@ Middleware.debug)
    .tapErrorZIO(e => ZIO.logErrorCause(Cause.fail(e)))
    .handleErrorCause { e =>
      e.squash.match {
        case ExpiredToken(msg, _)   => Response.error(Status.Unauthorized, msg)
        case AuthBadRequest(msg, _) => Response.error(Status.BadRequest, msg)
        case FileNotFound(file)     => Response.notFound(file)
        case EmailAlreadyExists(e)  => Response.error(Status.Conflict, e)
        case NotAuthenticated       => Response.unauthorized
        case e                      => Response.internalServerError(e.getMessage)
      }
    }

  override def run: ZIO[Environment & ZIOAppArgs, AuthError, Unit] =
    for {
      app <- zapp
      server <- Server
        .serve(app)
        .provide(
          Server.live,
          ZLayer.succeed(Server.Config.default.binding("localhost", 8081)),
          ZLayer.succeed(AuthConfig(secretKey = SecretKey("MOCK_SECRET_KEY")))
        )
        .foldCauseZIO(
          cause => ZIO.logErrorCause("err when booting server", cause),
          _ => ZIO.logError("app quit unexpectedly...")
        )
    } yield server

}
