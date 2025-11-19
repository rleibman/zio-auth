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

import japgolly.scalajs.react.CtorType.Summoner.Aux
import japgolly.scalajs.react.component.ScalaFn.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.internal.Box
import japgolly.scalajs.react.vdom.html_<^.*
import japgolly.scalajs.react.{CtorType, *}
import zio.json.*
import zio.json.ast.Json

case class LoginPageState(
  email:    String = "",
  password: String = "",
  error:    Option[String] = None
)

def LoginPage[ConnectionId: JsonEncoder] =
  ScalaFnComponent
    .withHooks[(ctl: RouterCtl[LoginPages], connectionId: Option[ConnectionId])]
    .useState(ClientAuthConfig())
    .useState(LoginPageState())
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
        p,
        config,
        state
      ) =>
        <.div(
          <.h1("Sign in to your account"),
          <.p(^.className := "instructions", "Welcome back! Please enter your details to login."),
          <.div(
            ^.className := "form-container",
            <.form(
              ^.onSubmit ==> { e =>
                e.preventDefaultCB >>
                  AuthClient
                    .login[Json, ConnectionId](state.value.email, state.value.password, p.connectionId)
                    .map {
                      case Left(e) =>
                        state.modState(_.copy(error = Some(e)))
                      case _ => // All's good, we're going to get reloaded, so don't worry about the return value, but do reset the url
                        p.ctl.set(LoginPages.Login) >> state.modState(_.copy(error = None))
                    }
                    .completeWith(_.get)
              },
              <.div(
                <.label("Email Address", ^.`for` := "email"),
                <.input(
                  ^.name        := "email",
                  ^.placeholder := "wizard@example.com",
                  ^.required    := true,
                  ^.`type`      := "email",
                  ^.onChange ==> { (e: ReactEventFromInput) => state.modState(_.copy(email = e.target.value)) }
                )
              ),
              <.div(
                <.label("Password", ^.`for` := "password"),
                <.input(
                  ^.name         := "password",
                  ^.placeholder  := "Password",
                  ^.autoComplete := "current-password",
                  ^.required     := true,
                  ^.placeholder  := "••••••••",
                  ^.`type`       := "password",
                  ^.onChange ==> { (e: ReactEventFromInput) => state.modState(_.copy(password = e.target.value)) }
                )
              ),
              <.div(
                <.a(
                  ^.display    := "block",
                  ^.paddingTop := 20.px,
                  "Forgot password?",
                  ^.href := config.value.requestPasswordRecoveryUrl,
                  ^.onClick ==> { e => e.preventDefaultCB >> p.ctl.set(LoginPages.RequestLostPassword) }
                )
              ),
              <.div(<.button(^.`type` := "submit", "Login")),
              state.value.error.fold(EmptyVdom)(e => <.div(^.className := "error", e))
            ),
            <.div(
              ^.className := "other-instructions",
              "Don't have an account?",
              <.a(
                ^.marginLeft := 5.px,
                "Register now",
                ^.href := config.value.requestRegistrationUrl,
                ^.onClick ==> { e => e.preventDefaultCB >> p.ctl.set(LoginPages.RequestRegistration) }
              ),
              <.div("By Logging in you agree to our terms of service and privacy policy.")
            )
          )
        )
    )
