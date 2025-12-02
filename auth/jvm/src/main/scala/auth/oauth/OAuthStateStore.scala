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

import java.time.LocalDateTime

/** Manages OAuth state tokens for CSRF protection
  *
  * Stores state tokens with their timestamp and associated provider. Automatically cleans up expired states.
  */
trait OAuthStateStore {

  /** Store a state token with its timestamp and provider
    *
    * @param state
    *   The state token
    * @param timestamp
    *   When the state was created
    * @param provider
    *   The OAuth provider name
    */
  def put(
    state:     String,
    timestamp: LocalDateTime,
    provider:  String
  ): UIO[Unit]

  /** Retrieve and remove a state token
    *
    * @param state
    *   The state token to retrieve
    * @return
    *   Some((timestamp, provider)) if found, None otherwise
    */
  def remove(state: String): UIO[Option[(LocalDateTime, String)]]

  /** Clean up expired state tokens
    *
    * @param expirationMinutes
    *   States older than this many minutes will be removed
    */
  def cleanup(expirationMinutes: Int): UIO[Unit]

}

object OAuthStateStore {

  /** Live implementation using a concurrent TrieMap
    */
  final case class Live(
    store: Ref[Map[String, (LocalDateTime, String)]]
  ) extends OAuthStateStore {

    override def put(
      state:     String,
      timestamp: LocalDateTime,
      provider:  String
    ): UIO[Unit] = store.update(_ + (state -> (timestamp, provider)))

    override def remove(state: String): UIO[Option[(LocalDateTime, String)]] =
      store.modify { map =>
        (map.get(state), map - state)
      }

    override def cleanup(expirationMinutes: Int): UIO[Unit] =
      ZIO.succeed {
        val now = LocalDateTime.now()
        store.update { map =>
          map.filter { case (_, (timestamp, _)) =>
            timestamp.plusMinutes(expirationMinutes).isAfter(now)
          }
        }
      }.unit

  }

  /** Create a layer that provides the OAuthStateStore service with automatic cleanup
    *
    * @param cleanupIntervalMinutes
    *   How often to run cleanup (default: 5 minutes)
    * @param expirationMinutes
    *   How long states are valid (default: 10 minutes)
    */
  def live(
    cleanupIntervalMinutes: Int = 5,
    expirationMinutes:      Int = 10
  ): ZLayer[Any, Nothing, OAuthStateStore] =
    ZLayer.scoped {
      for {
        store <- Ref.make(Map.empty[String, (LocalDateTime, String)])
        stateStore = Live(store)
        // Start background cleanup fiber
        _ <- stateStore
          .cleanup(expirationMinutes)
          .repeat(Schedule.fixed(cleanupIntervalMinutes.minutes))
          .forkScoped
      } yield stateStore
    }

}
