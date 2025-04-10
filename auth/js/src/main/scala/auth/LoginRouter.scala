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

object LoginRouter {

  enum LoginPages {

    case Login, requestLostPassword, confirmLostPassword, requestRegistration, confirmRegistration

  }

  private def layout(
    page:       RouterCtl[LoginPages],
    resolution: Resolution[LoginPages]
  ): VdomElement = {
    resolution.render()
  }

  private val config: RouterWithPropsConfig[LoginPages, Unit] = RouterConfigDsl[LoginPages].buildConfig { dsl =>
    {
      import dsl.*

      (
        trimSlashes |
          staticRoute("#login", LoginPages.Login) ~> render(LoginPage()) |
          staticRoute("#requestLostPassword", LoginPages.requestLostPassword) ~> render(RequestLostPasswordPage()) |
          staticRoute("#confirmLostPassword", LoginPages.confirmLostPassword) ~> render(<.div("Hello")) |
          staticRoute("#requestRegistration", LoginPages.requestRegistration) ~> render(RequestRegistrationPage()) |
          staticRoute("#confirmRegistration", LoginPages.confirmRegistration) ~> render(<.div("Hello"))
      )
        .notFound(
          redirectToPage(LoginPages.Login)(SetRouteVia.HistoryReplace)
        )
    }.renderWith(layout)
  }

  def apply(): Router[LoginPages] = Router(BaseUrl.fromWindowOrigin_/, config)

}
