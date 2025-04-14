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

import japgolly.scalajs.react.component.ScalaFn.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.*
import japgolly.scalajs.react.{CtorType, *}

case class RequestLostPasswordState(
  email: String = "",
  error: Option[String] = None
)

val RequestLostPasswordPage: Component[RouterCtl[LoginPages], CtorType.Props] = ScalaFnComponent
  .withHooks[RouterCtl[LoginPages]]
  .useState(ClientAuthConfig())
  .useState(RequestLostPasswordState())
  .useEffectOnMountBy {
    (
      _,
      config,
      _ // state
    ) =>
      AuthClient
        .clientAuthConfig()
        .map(c => config.modState(_ => c))
        .completeWith(_.get)
  }
  .render(
    (
      ctl,
      config,
      state
    ) =>
      <.form(
        ^.className := "login-form",
        ^.onSubmit ==> { e =>
          e.preventDefaultCB
        },
        <.div(
          <.label("Email", ^.`for` := "email"),
          <.input(
            ^.name        := "email",
            ^.placeholder := "Email",
            ^.`type`      := "email",
            ^.onChange ==> { (e: ReactEventFromInput) => state.modState(_.copy(email = e.target.value)) }
          )
        ),
        <.div(
          <.button(^.`type` := "submit", "Request Password Reset"),
          state.value.error.fold(EmptyVdom)(e => <.div(^.className := "error", e))
        ),
        <.div(
          "Remembered your password ?",
          <.a(
            "Sign In",
            ^.href := config.value.loginUrl,
            ^.onClick ==> { e => e.preventDefaultCB >> ctl.set(LoginPages.Login) }
          )
        ),
        <.div(state.value.error.fold(EmptyVdom)(e => <.div(^.className := "error", e)))
      )
  )
