let
  nixpkgs = builtins.fetchGit {
              name = "nixos-pinned";
              url = https://github.com/nixos/nixpkgs/;
              # `git ls-remote https://github.com/nixos/nixpkgs-channels nixos-unstable`
              rev = "481a00f3b1194c11d348cdf3a948f57a8002fa12";
            };
in
with import nixpkgs { config = {}; overlays = []; };

stdenv.mkDerivation {
  name = "s3dsl-env";

  buildInputs = [
    figlet
    lolcat
    minio
  ];

  shellHook = ''
    shutdown_hook() {
      kill $PID
      rm -rf target/minio
    }
    rm -rf target/minio
    trap shutdown_hook EXIT
    export MINIO_ACCESS_KEY=BQKN8G6V2DQ83DH3AHPN
    export MINIO_SECRET_KEY=GPD7MUZqy6XGtTz7h2QPyJbggGkQfigwDnaJNrgF
    export MINIO_DOMAIN=localhost
    minio --compat server -C target/minio/.config --address 127.0.0.1:9000 target/minio &
    PID=$!
    figlet "s3dsl" | lolcat --freq 0.5
  '';
}
