{
  description = "tui4s development tools: JDK 25, pinned sbt, and terminal tests";

  # Keep this input aligned with vendor/pini4s/flake.nix and both lockfiles.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/319e4cebef10cbb636c6ea392137b52ad105336a";

  outputs = { nixpkgs, ... }:
    let
      # claude 2026-10-20: changed: x86_64-darwin too (the maintainer's Mac evaluates as x86_64-darwin)
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          jdk = pkgs.temurin-bin-25;
        in
        {
          default = pkgs.mkShellNoCC ({
            packages = [
              jdk
              # This runner reads sbt.version from project/build.properties.
              # Scala is likewise selected by the build, not by the shell.
              (pkgs.sbt-extras.override { inherit jdk; })
              pkgs.git
              pkgs.curl
              pkgs.expect
              pkgs.python3
              pkgs.coreutils
              # claude 2026-10-19: created: Node.js runs the Scala.js tests of
              # tui4s-use and tui4s-widgets (sbt check) (L2a)
              pkgs.nodejs
            ];

            JAVA_HOME = "${jdk.home}";
          } // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
            # The crossterm JNI library extracted from its jar needs libgcc_s.
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ];
          });
        });
    };
}
