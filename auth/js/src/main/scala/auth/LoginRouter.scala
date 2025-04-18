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

package auth

import japgolly.scalajs.react.extra.router.*
import japgolly.scalajs.react.vdom.html_<^.*

sealed trait LoginPages

object LoginPages {

  case object Index extends LoginPages

  case object Login extends LoginPages

  case object RequestLostPassword extends LoginPages

  case class PasswordRecovery(code: String) extends LoginPages

  case object RequestRegistration extends LoginPages

  case class ConfirmRegistration(code: String) extends LoginPages

}

object LoginRouter {

  private def layout(
    page:       RouterCtl[LoginPages],
    resolution: Resolution[LoginPages]
  ): VdomElement = {
    <.div(^.className := "auth")(
      resolution.render()
    )
  }

  private val config: RouterWithPropsConfig[LoginPages, Unit] = RouterConfigDsl[LoginPages].buildConfig { dsl =>
    {
      import LoginPages.*
      import dsl.*

      (
        trimSlashes |
          staticRoute("#index", LoginPages.Index) ~> render(<.div("Should never get here")) |
          staticRoute("#login", LoginPages.Login) ~> renderR(ctl => LoginPage(ctl)) |
          staticRoute("#requestLostPassword", LoginPages.RequestLostPassword) ~> renderR(ctl =>
            RequestLostPasswordPage(ctl)
          ) |
          dynamicRouteCT(
            ("#passwordRecovery" ~ queryToMap.pmap(map => map.get("code"))(code => Map("code" -> code)))
              .caseClass[PasswordRecovery]
          ) ~> dynRenderR(
            (
              p,
              ctl
            ) => PasswordRecoveryPage(p.code, ctl)
          ) |
          staticRoute("#requestRegistration", RequestRegistration) ~> renderR(ctl => RequestRegistrationPage(ctl)) |
          dynamicRouteCT(
            ("#confirmRegistration" ~ queryToMap.pmap(map => map.get("code"))(code => Map("code" -> code)))
              .caseClass[ConfirmRegistration]
          ) ~> dynRenderR(
            (
              p,
              ctl
            ) => ConfirmRegistrationPage(p.code, ctl)
          )
      )
        .notFound(
          redirectToPage(LoginPages.Login)(using SetRouteVia.HistoryReplace)
        )
    }.renderWith(layout)
  }

  def apply(): Router[LoginPages] = Router(BaseUrl.fromWindowOrigin_/, config)

}
