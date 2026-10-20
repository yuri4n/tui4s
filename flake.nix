{
  description = "tui4s development tools: JDK 25, pinned sbt, and terminal tests";

  # Keep these inputs aligned with vendor/pini4s/flake.nix and both lockfiles.
  # nix develop also uses the input named nixpkgs to find Bash. It must
  # support Intel macOS, which nixpkgs 26.11 dropped.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-26.05-darwin";
  inputs.nixpkgs-current.url = "github:NixOS/nixpkgs/319e4cebef10cbb636c6ea392137b52ad105336a";

  outputs = { nixpkgs, nixpkgs-current, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = nixpkgs-current.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import
            (if system == "x86_64-darwin" then nixpkgs else nixpkgs-current)
            { inherit system; };
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
            ];

            JAVA_HOME = "${jdk.home}";
          } // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
            # The crossterm JNI library extracted from its jar needs libgcc_s.
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ];
          });
        });
    };
}
