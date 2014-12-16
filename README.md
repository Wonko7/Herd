# aqua-node

An Anonymous Quanta implementation.

## Building

### Program versions:
- Node:		v0.10.12-release:a088cf4f930d3928c97d239adf950ab43e7794aa --> this should no longer be a requirement now that we've stopped using the node dtls module.
- OpenSSl:	1.0.1e

### Environment:
- `prefix=...`
- `PATH=$prefix/bin:$prefix/sbin:$prefix/lib/node_modules/npm/bin/node-gyp-bin:$PATH`
- `export LD_LIBRARY_PATH=$prefix/lib`

### Building:
- OpenSSL:	`./config shared --prefix=$prefix enable-tlsext enable-dtls enable-ssl; make; make install`
- Node:		`./configure --prefix=$prefix --shared-openssl --shared-openssl-includes $prefix/include --shared-openssl-libpath $prefix/lib; make; make install`
- Aqua:		`lein cljsbuild once`

## Usage

`node target/aqua.js`
Also see aquarc config file.

## License

Copyright © 2013 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
