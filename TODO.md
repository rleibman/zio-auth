- Google Auth
- Theme emails
- Cross-publish the session types so downstream shared code can depend on zio-auth with `%%%`.
  `AuthenticatedSession` / `Session` / `UnauthenticatedSession` currently live in the JVM-only tree
  (`auth/jvm/src/main/scala/auth/Session.scala`), so the published `zio-auth_sjs1_3` artifact does NOT contain them
  (its `auth` package only has the client-side API: `AuthClient`, `ClientAuthConfig`, ...). Move the plain session
  data types into `auth/shared/src/main/scala/auth/` (leave `AuthServer.scala` and anything JVM-specific where it is).
  Then both artifacts carry the types and consumers can use `%%%` in shared crossProject settings.
  Impact: meal-o-rama's `model` has to declare zio-auth twice as a workaround (shared `%% "zio-auth"` -> the JVM jar,
  plus a separate `% "zio-auth_sjs1_3"` in jsSettings) because `%%%` breaks the JS compile today. Fixing this here lets
  that collapse to a single `%%%` line. See meal-o-rama build.sbt (modelJS/model dependencies).
