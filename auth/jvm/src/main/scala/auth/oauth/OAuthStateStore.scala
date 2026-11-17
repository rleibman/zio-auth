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

package auth.oauth

import zio.*

import java.time.Instant

/** Where OAuth states wait between the redirect to the provider and its callback: each state's creation time, provider
  * and PKCE verifier. A state is removed when its callback uses it, or by the cleanup once it has expired.
  */
trait OAuthStateStore {

  def put(
    state: String,
    entry: OAuthStateStore.Entry,
  ): UIO[Unit]

  /** Takes the state's entry out (a state works once). */
  def remove(state: String): UIO[Option[OAuthStateStore.Entry]]

  /** Drops the entries older than `lifetime`. */
  def cleanup(lifetime: Duration): UIO[Unit]

}

object OAuthStateStore {

  /** A pending sign-in: when it started, with which provider, and the PKCE verifier (for providers that support PKCE).
    */
  final case class Entry(
    createdAt:    Instant,
    provider:     String,
    codeVerifier: Option[String],
  )

  final case class Live(store: Ref[Map[String, Entry]]) extends OAuthStateStore {

    override def put(
      state: String,
      entry: Entry,
    ): UIO[Unit] = store.update(_ + (state -> entry))

    override def remove(state: String): UIO[Option[Entry]] = store.modify(map => (map.get(state), map - state))

    override def cleanup(lifetime: Duration): UIO[Unit] =
      Clock.instant.flatMap(now =>
        store.update(_.filter { case (_, entry) => entry.createdAt.plus(lifetime).isAfter(now) }),
      )

  }

  def live(
    cleanupIntervalMinutes: Int = 5,
    expirationMinutes:      Int = 10,
  ): ZLayer[Any, Nothing, OAuthStateStore] =
    ZLayer.scoped {
      for {
        store <- Ref.make(Map.empty[String, Entry])
        stateStore = Live(store)
        _ <- stateStore
          .cleanup(expirationMinutes.minutes)
          .repeat(Schedule.fixed(cleanupIntervalMinutes.minutes))
          .forkScoped
      } yield stateStore
    }

}
