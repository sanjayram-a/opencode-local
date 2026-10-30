# proot binaries — sources

Fetched by `native/proot/fetch.sh` from the Termux apt repo (https://packages.termux.dev/apt/termux-main).
proot is GPL-2.0 (source: https://github.com/termux/proot); talloc is LGPL-3.0 (source: https://www.samba.org/ftp/talloc/).
Build recipes: https://github.com/termux/termux-packages/tree/master/packages/{proot,libtalloc}

- arm64-v8a: `proot` 5.1.107.101 — https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.101_aarch64.deb (sha256 a28355e9be257e1f87f4e9294b3118470d4fd7a5442fed8b278f4443c83407d7)
- arm64-v8a: `libtalloc` 2.5.0 — https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb (sha256 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7)
- arm64-v8a: `libandroid-shmem` 0.7 — https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb (sha256 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6)
- x86_64: `proot` 5.1.107.101 — https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.101_x86_64.deb (sha256 85adb8d0d2e1edc6c56e60c17136adf05e54ce75f69407ec7116a4c5997eaed5)
- x86_64: `libtalloc` 2.5.0 — https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_x86_64.deb (sha256 b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530)
- x86_64: `libandroid-shmem` 0.7 — https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb (sha256 ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797)

## ELF LOAD alignment (Play requires 16 KB = 0x4000 for targetSdk 35+)

- arm64-v8a/libproot.so: 0x10000 0x4000 
- arm64-v8a/libproot-loader.so: 0x4000 
- arm64-v8a/libtalloc.so: 0x10000 0x4000 
- arm64-v8a/libandroid-shmem.so: 0x4000 
- x86_64/libproot.so: 0x1000 0x4000 
- x86_64/libproot-loader.so: 0x4000 
- x86_64/libtalloc.so: 0x1000 0x4000 
- x86_64/libandroid-shmem.so: 0x4000 

arm64-v8a: every LOAD is >= 0x4000, so 16 KB page devices and the Play 16 KB rule are satisfied.
x86_64: has 0x1000 LOAD segments. It is for the emulator (4 KB page image) only and must not ship in a
Play release unless rebuilt from source with -Wl,-z,max-page-size=16384 (Phase 2 TODO).
