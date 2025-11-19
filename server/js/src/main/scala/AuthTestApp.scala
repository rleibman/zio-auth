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
import japgolly.scalajs.react.component.ScalaFn.Component
import japgolly.scalajs.react.vdom.html_<^.*
import japgolly.scalajs.react.{CtorType, *}
import org.scalajs.dom
import sttp.client4.basicRequest

import scala.scalajs.js.annotation.JSExport

object AuthTestApp {

  @JSExport
  def main(args: Array[String]): Unit = {
    val component: Component[Unit, CtorType.Nullary] = ScalaFnComponent
      .withHooks[Unit]
      .useState(None: Option[MockUser])
      .useEffectOnMountBy {
        (
          _,
          user
        ) =>
          AuthClient
            .whoami[MockUser, MockConnectionId](Some(MockConnectionId("hello"))).map(j =>
              user.modState(_ => j)
            ).completeWith(_.get)
      }
      .render($ =>
        $.hook1.value.fold(
          <.div(LoginRouter(Some(MockConnectionId("hello"))))
        )(user =>
          <.div(
            <.h1("Welcome"),
            <.p(s"Hello ${user.name}"),
            <.button(
              ^.onClick --> {
                import sttp.client4.*
                AuthClient
                  .withAuth[String](basicRequest.get(uri"/api/secured"))
                  .map(s => Callback.alert(s.fold(error => s"Error: $error", s => s)))
                  .completeWith(_.get)
              },
              "Secured content"
            ),
            <.button(
              ^.onClick --> AuthClient
                .logout()
                .map(_ => $.hook1.modState(_ => None))
                .completeWith(_ => $.hook1.modState(_ => None)),
              "Logout"
            )
          )
        )
      )

    component().renderIntoDOM(dom.document.getElementById("content"))
    ()

  }

}
