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

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.VdomNode
import japgolly.scalajs.react.vdom.html_<^.*

/** Generic OAuth button component
  *
  * This component renders a button/link that initiates OAuth login flow. It's designed to be styled via CSS classes for
  * maximum flexibility.
  *
  * @param provider
  *   Provider name (e.g., "google", "github", "discord")
  * @param icon
  *   Optional icon/logo to display (VdomNode for flexibility)
  * @param label
  *   Button text (e.g., "Continue with Google")
  * @param className
  *   Optional CSS class(es) for styling
  */
case class OAuthButtonProps(
  provider:  String,
  icon:      Option[VdomNode] = None,
  label:     String,
  className: Option[String] = None
)

object OAuthButton {

  val component = ScalaFnComponent[OAuthButtonProps] { props =>
    <.a(
      ^.href      := s"/oauth/${props.provider}/login",
      ^.className := props.className.getOrElse("auth-oauth-button"),
      props.icon.whenDefined,
      <.span(props.label)
    )
  }

  def apply(
    provider:  String,
    icon:      Option[VdomNode] = None,
    label:     String,
    className: Option[String] = None
  ): VdomElement = component(OAuthButtonProps(provider, icon, label, className))

}
