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
import japgolly.scalajs.react.component.ScalaFn.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.*
import japgolly.scalajs.react.{CtorType, *}

val RequestRegistrationPage: Component[RouterCtl[LoginPages], CtorType.Props] = ScalaFnComponent
  .withHooks[RouterCtl[LoginPages]]
  .useState(ClientAuthConfig())
  .useState(UserRegistrationRequest())
  .useState("") // Repeat password
  .useState(None: Option[String])
  .useEffectOnMountBy {
    (
      _,
      config,
      _, // Request
      _, // Repeat password
      _ // error
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
      state,
      repeatPassword,
      error
    ) =>
      <.form(
        ^.className := "login-form",
        ^.onSubmit ==> { e =>
          val validated: ValidatedNec[String, UserRegistrationRequest] = UserRegistrationRequest.validateRequest(
            state.value.name,
            state.value.email,
            state.value.password,
            repeatPassword.value
          )

          e.preventDefaultCB >> (validated match {
            case Validated.Valid(a) =>
              AuthClient
                .requestRegistration(a).map(r =>
                  r.fold(
                    e => error.modState(_ => Some(s"Error requesting registration: $e")),
                    _ =>
                      error.modState(_ =>
                        Some("Account created successfully, please await for an email to confirm registration")
                      )
                  )
                ).completeWith(_.get)
            case Validated.Invalid(errors) =>
              error.modState(_ => Some(errors.toList.mkString(", ")))
          })

        },
        <.div(
          <.label("Name", ^.`for` := "name"),
          <.input(
            ^.name        := "name",
            ^.placeholder := "Name",
            ^.`type`      := "text",
            ^.onChange ==> { (e: ReactEventFromInput) => state.modState(_.copy(name = e.target.value)) }
          )
        ),
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
          <.label("Password", ^.`for` := "password"),
          <.input(
            ^.name        := "password",
            ^.placeholder := "Password",
            ^.`type`      := "password",
            ^.onChange ==> { (e: ReactEventFromInput) => state.modState(_.copy(password = e.target.value)) }
          )
        ),
        <.div(
          <.label("Repeat Password", ^.`for` := "repeatPassword"),
          <.input(
            ^.name        := "repeatPassword",
            ^.placeholder := "Repeat Password",
            ^.`type`      := "password",
            ^.onChange ==> { (e: ReactEventFromInput) => repeatPassword.modState(_ => e.target.value) }
          )
        ),
        <.div(
          <.button(^.`type` := "submit", "Register")
        ),
        <.div(
          "Already have an account?",
          <.a(
            "Sign In",
            ^.href := config.value.loginUrl,
            ^.onClick ==> { e => e.preventDefaultCB >> ctl.set(LoginPages.Login) }
          )
        ),
        <.div(error.value.fold(EmptyVdom)(e => <.div(^.className := "error", e)))
      )
  )
