import { defineConfig } from "vite";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

// Driven by sbt (see viteDistImpl in build.sbt), not the other way round: @scala-js/vite-plugin-scalajs would
// resolve the linker output by spawning `sbt print fastLinkJSOutput`, which from inside an sbt task means sbt
// re-entering itself. So sbt passes the paths in through the environment.
function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set; build this through sbt's serverJS/dist or serverJS/debugDist.`);
  return value;
}

const scalaJSOutputDir = required("SCALAJS_OUTPUT_DIR");
const outDir = required("VITE_OUT_DIR");

// Under sbt 2 the linker output lands in <repo>/target/out/sjs1/..., nowhere near server/js/node_modules, so the
// bare imports inside it (react, react-dom) do not resolve by walking up. Re-resolve them as if they came from
// server/js instead.
const resolveScalaJSImportsFromHere = {
  name: "scalajs-bare-imports",
  enforce: "pre",
  async resolveId(source, importer, options) {
    if (!importer || !importer.startsWith(scalaJSOutputDir)) return null;
    if (source.startsWith(".") || path.isAbsolute(source)) return null;
    const resolved = await this.resolve(source, path.join(here, "main.js"), { ...options, skipSelf: true });
    return resolved ?? null;
  },
};

export default defineConfig(({ mode }) => ({
  root: here,
  // Static assets (css/, the texture) are copied by viteDistImpl, which has to lay them out anyway.
  publicDir: false,
  build: {
    outDir,
    emptyOutDir: true,
    minify: mode === "production",
    sourcemap: true,
  },
  resolve: {
    alias: [{ find: /^scalajs$/, replacement: path.resolve(scalaJSOutputDir, "main.js") }],
  },
  plugins: [resolveScalaJSImportsFromHere],
}));
