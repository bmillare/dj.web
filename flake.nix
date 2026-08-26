{
  description = "dj.web — a small server-driven web stack for Clojure";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f:
        nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      devShells = forAllSystems (pkgs:
        let
          # The browser bundle is deliberately pinned rather than fetched from a
          # CDN at runtime. dj.web.datastar.assets reads this path.
          datastarJs = pkgs.fetchurl {
            url = "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js";
            hash = "sha256-KDfYes9u4LqOTmN2WSbCWpjWOIOwL4i+GUqGuB0/0ko=";
          };
        in {
          default = pkgs.mkShell {
            packages = [
              pkgs.temurin-bin
              pkgs.clojure
              pkgs.babashka
            ];
            shellHook = ''
              export DATASTAR_JS=${datastarJs}
            '';
          };
        });
    };
}
