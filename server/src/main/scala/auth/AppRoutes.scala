package auth

import zio.ZIO
import zio.http.Routes

trait AppRoutes[-R, -SessionType, +E] {

  /** These routes represent the api, the are intended to be used thorough ajax-type calls they require a session
    */
  def api: ZIO[R, E, Routes[R & SessionType, E]] = ZIO.succeed(Routes.empty)

  /** These routes that bring up resources that require authentication (an existing session)
    */
  def auth: ZIO[R, E, Routes[R & SessionType, E]] = ZIO.succeed(Routes.empty)

  /** These do not require a session
    */
  def unauth: ZIO[R, E, Routes[R, E]] = ZIO.succeed(Routes.empty)

}
