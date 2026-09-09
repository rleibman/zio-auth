// Vite's entry point. `scalajs` is aliased in vite.config.js to the linker output directory sbt hands over;
// importing it runs AuthTestApp's main (Compile / scalaJSUseMainModuleInitializer := true).
import "scalajs";
