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

import auth.AuthError
import zio.*

/** OAuth service for managing multiple OAuth providers
  *
  * This service acts as a registry for OAuth providers and provides utility functions like state token generation.
  */
trait OAuthService {

  /** Get an OAuth provider by name
    *
    * @param name
    *   Provider name (e.g., "google", "github", "discord")
    * @return
    *   The OAuth provider or an error if not found/configured
    */
  def getProvider(name: String): IO[AuthError, OAuthProvider]

  /** Generate a cryptographically secure state token for CSRF protection
    *
    * @return
    *   A random state token
    */
  def generateState(): UIO[String]

  /** List all available (configured) OAuth provider names
    *
    * @return
    *   List of provider names that are configured and available
    */
  def listProviders: UIO[List[String]]

}

object OAuthService {

  /** Create an OAuthService with the given provider configurations
    *
    * Only providers with valid configurations will be available.
    *
    * @param googleConfig
    *   Optional Google OAuth configuration
    * @param githubConfig
    *   Optional GitHub OAuth configuration (provider implementation not yet included)
    * @param discordConfig
    *   Optional Discord OAuth configuration (provider implementation not yet included)
    * @return
    *   ZLayer providing OAuthService
    */
  def live(
    googleConfig:  Option[OAuthProviderConfig] = None,
    githubConfig:  Option[OAuthProviderConfig] = None,
    discordConfig: Option[OAuthProviderConfig] = None
  ): ULayer[OAuthService] =
    ZLayer.succeed {
      new OAuthService {

        private val providers: Map[String, OAuthProvider] = List(
          googleConfig.map(config => "google" -> new GoogleOAuthProvider(config))
          // NOTE: GitHub and Discord providers not yet implemented
          // Uncomment when providers are added:
          // githubConfig.map(config => "github" -> new GitHubOAuthProvider(config)),
          // discordConfig.map(config => "discord" -> new DiscordOAuthProvider(config))
        ).flatten.toMap

        override def getProvider(name: String): IO[AuthError, OAuthProvider] =
          ZIO
            .fromOption(providers.get(name.toLowerCase))
            .orElseFail(
              AuthError(
                s"OAuth provider '$name' not found or not configured. Available providers: ${providers.keys.mkString(", ")}"
              )
            )

        override def generateState(): UIO[String] = Random.nextUUID.map(_.toString.replace("-", ""))

        override def listProviders: UIO[List[String]] = ZIO.succeed(providers.keys.toList)

      }
    }

}
