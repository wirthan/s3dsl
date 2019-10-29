let
  fetchNixpkgs = import ./fetchNixpkgs.nix;
  nixpkgs = builtins.fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/f52505fac8c82716872a616c501ad9eff188f97f.tar.gz";
    sha256 = "0q2m2qhyga9yq29yz90ywgjbn9hdahs7i8wwlq7b55rdbyiwa5dy";
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
    }
    trap shutdown_hook EXIT
    export MINIO_ACCESS_KEY=BQKN8G6V2DQ83DH3AHPN
    export MINIO_SECRET_KEY=GPD7MUZqy6XGtTz7h2QPyJbggGkQfigwDnaJNrgF
    export MINIO_DOMAIN=localhost
    minio server -C target/minio/.config --address 127.0.0.1:9000 target/minio &
    PID=$!
    figlet "s3dsl" | lolcat --freq 0.5
  '';
}
