{
  description = "Flake for dev shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
  };

  outputs = inputs@{ nixpkgs, flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      perSystem = { config, self', inputs', pkgs, system, ... }:
      let
        jdk21-overlay = self: super: {
          jdk = super.jdk21;
          jre = super.jdk21;
          sbt = super.sbt.override { jre = super.jdk21; };
        };
        newPkgs = import nixpkgs {
          inherit system;
          overlays = [ jdk21-overlay ];
        };
        playwrightDriver = newPkgs.playwright-driver;
      in {
        devShells.default = newPkgs.mkShell {
          nativeBuildInputs = with newPkgs; [
            sbt
            jdk
            nodejs_24
            postgresql_14
          ];
          # Give sbt a larger heap to avoid OOM during Scala 3 compilation.
          SBT_OPTS = "-Xms1g -Xmx4g -XX:MaxMetaspaceSize=1g";
        };
        # Native client tests need no JVM, database or companion checkout.
        # Driver and browser revisions come from the same locked nixpkgs input.
        devShells.browser = newPkgs.mkShell {
          nativeBuildInputs = with newPkgs; [ bash coreutils nodejs_24 ];
          PLAYWRIGHT_DRIVER_PATH = "${playwrightDriver}";
          PLAYWRIGHT_BROWSERS_PATH = "${playwrightDriver.browsers}";
          PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1";
          # Playwright checks /sbin/ldconfig, which cannot see Nix closures.
          # The packaged browsers carry patched runtime paths; native launch
          # and the fixture assertions remain the dependency/behavior gate.
          PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = nixpkgs.lib.optionalString newPkgs.stdenv.isLinux "1";
          # The Linux WebKit wrapper needs an EGL vendor on headless machines.
          # Tests apply this only to their owned WebKit process.
          SPOONBILL_PLAYWRIGHT_EGL_VENDOR = nixpkgs.lib.optionalString newPkgs.stdenv.isLinux
            "${newPkgs.mesa}/share/glvnd/egl_vendor.d/50_mesa.json";
        };
      };
    };
}
