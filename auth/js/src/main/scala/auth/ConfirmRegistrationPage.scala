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

import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.*
import japgolly.scalajs.react.{CtorType, *}

val ConfirmRegistrationPage = ScalaFnComponent
  .withHooks[(String, RouterCtl[LoginPages])]
  .useState(None: Option[String]) // error
  .useEffectOnMountBy {
    (
      props,
      error
    ) =>
      AuthClient
        .confirmRegistration(props._1)
        .map {
          case Left(e) =>
            error.modState(_ => Some(e))
          case _ =>
            Callback.empty
        }
        .completeWith(_.get)
  }
  .render(
    (
      props,
      error
    ) =>
      <.div(
        <.h1("Account Activation"),
        <.p(^.className := "instructions", ""),
        <.div(
          ^.className := "form-container",
          <.form(
            ^.onSubmit ==> { _ => Callback.empty }, // Nothing to do
            error.value.fold {
              VdomArray(
                <.h2(^.key := "a", "Account Activated"),
                <.div(
                  ^.key := "b",
                  s"You're ready to ",
                  <.a(
                    ^.href := "login",
                    ^.onClick ==> { e => e.preventDefaultCB >> props._2.set(LoginPages.Login) },
                    "Log in."
                  )
                )
              )
            }(e => VdomArray(<.h2(^.key := "c", "Account Creation Failed"), <.div(^.key := "d", e)))
          )
        )
      )
  )
