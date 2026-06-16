{
  description = "dev env";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
    devenv.url = "github:ramblurr/nix-devenv";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
    clj-helpers.url = "github:outskirtslabs/clojure-nix-locker-helpers";
    clj-helpers.inputs.nixpkgs.follows = "nixpkgs";
  };
  outputs =
    inputs@{
      clj-helpers,
      self,
      devenv,
      devshell,
      ...
    }:
    let
      jdk = "jdk25";
      package =
        pkgs:
        let
          jdkPackage = pkgs.${jdk};
          clojure = pkgs.clojure.override { jdk = jdkPackage; };
        in
        clj-helpers.lib.mkCljApp {
          inherit pkgs;
          modules = [
            {
              jdk = jdkPackage;
              name = "probematic";
              version = "0.0.TODO";
              src = ./.;
              buildCommand = "clojure -Srepro -T:build uberjar";
              lockCommand = ''
                export HOME="$tmp/home"
                export GITLIBS="$HOME/.gitlibs"
                export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
                unset CLJ_CACHE CLJ_CONFIG XDG_CACHE_HOME XDG_CONFIG_HOME XDG_DATA_HOME
                export JAVA_HOME="${jdkPackage.home}"
                export JAVA_CMD="${jdkPackage}/bin/java"
                export GIT_REV="lockfile-generation"
                export PATH="${clojure}/bin:${jdkPackage}/bin:$PATH"

                clojure -Srepro -X:deps prep :aliases '[:dev :kaocha]'
                clojure -Srepro -P -M:dev:kaocha
                clojure -Srepro -T:build uberjar
              '';
              gitRev = clj-helpers.lib.gitRev self;
            }
          ];
        };
    in
    devenv.lib.mkFlake ./. {
      inherit inputs;
      pname = "probematic";
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      packages = {
        default = package;
        locker = pkgs: (package pkgs).locker;
      };
      devShell =
        pkgs:
        pkgs.devshell.mkShell {
          imports = [
            devenv.capsules.base
            devenv.capsules.clojure
          ];
          # https://numtide.github.io/devshell
          commands = [
            # { package = pkgs.bazqux; }
          ];
          packages = [
            pkgs.lightningcss
            pkgs.watchexec
            pkgs.gitleaks
            (
              if self ? packages then
                self.packages.${pkgs.system}.locker
              else
                clj-helpers.packages.${pkgs.system}.deps-lock
            )
            # pkgs.foobar
          ];

        };
    };
}
