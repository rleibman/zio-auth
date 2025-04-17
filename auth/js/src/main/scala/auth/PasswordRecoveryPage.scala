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

import cats.data.*
import cats.implicits.given
import cats.syntax.all.given
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.hooks.HookComponentBuilder.ComponentP
import japgolly.scalajs.react.vdom.html_<^.*
import japgolly.scalajs.react.{CtorType, *}

val PasswordRecoveryPage = ScalaFnComponent
  .withHooks[(String, RouterCtl[LoginPages])]
  .useState(ClientAuthConfig())
  .useStateBy(a => PasswordRecoveryNewPasswordRequest(a.props._1))
  .useState("") // Repeat password
  .useState(None: Option[String])
  .useEffectOnMountBy {
    (
      _,
      config,
      _, // state,
      _, // repeatPassword
      _ // error
    ) =>
      AuthClient
        .clientAuthConfig()
        .map(c => config.modState(_ => c))
        .completeWith(_.get)
  }
  .render(
    (
      props,
      config,
      state,
      repeatPassword,
      error
    ) =>
      <.div(
        <.h1("Reset Your Password"),
        <.p(^.className := "instructions", "Enter your new password."),
        <.div(
          ^.className := "form-container",
          <.form(
            ^.onSubmit ==> { e =>
              val validated: ValidatedNec[String, PasswordRecoveryNewPasswordRequest] =
                PasswordRecoveryNewPasswordRequest.validateRequest(
                  state.value.confirmationCode,
                  state.value.password,
                  repeatPassword.value
                )

              e.preventDefaultCB >> (validated match {
                case Validated.Valid(a) =>
                  AuthClient
                    .passwordRecovery(a).map(r =>
                      r.fold(
                        e => error.modState(_ => Some(s"Error setting password: $e")),
                        _ => props._2.set(LoginPages.Login)
                      )
                    ).completeWith(_.get)
                case Validated.Invalid(errors) =>
                  error.modState(_ => Some(errors.toList.mkString(", ")))
              })

            },
            <.div(
              <.label("Password", ^.`for` := "password"),
              <.input(
                ^.name        := "password",
                ^.placeholder := "••••••••",
                ^.`type`      := "password",
                ^.onChange ==> { (e: ReactEventFromInput) => state.modState(_.copy(password = e.target.value)) }
              )
            ),
            <.div(
              <.label("Repeat Password", ^.`for` := "repeatPassword"),
              <.input(
                ^.name        := "repeatPassword",
                ^.placeholder := "••••••••",
                ^.`type`      := "password",
                ^.onChange ==> { (e: ReactEventFromInput) => repeatPassword.modState(_ => e.target.value) }
              )
            ),
            <.div(<.button(^.`type` := "submit", "Reset Password")),
            <.div(error.value.fold(EmptyVdom)(e => <.div(^.className := "error", e)))
          ),
          <.div(
            ^.className := "other-instructions",
            "Remembered your password?",
            <.a(
              ^.marginLeft := 5.px,
              "Sign In",
              ^.href := config.value.loginUrl,
              ^.onClick ==> { e => e.preventDefaultCB >> props._2.set(LoginPages.Login) }
            )
          )
        )
      )
  )
