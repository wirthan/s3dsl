let
  fetchNixpkgs = import ./fetchNixpkgs.nix;
  nixpkgs = builtins.fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/749a3a0d00b5d4cb3f039ea53e7d5efc23c296a2.tar.gz";
    sha256 = "14dqndpxa4b3d3xnzwknjda21mm3p0zmk8jbljv66viqj5plvgdw";
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
    minio server -C target/minio/.config --address 127.0.0.1:9000 target/minio &
    PID=$!
    figlet "s3dsl" | lolcat --freq 0.5
  '';
}
