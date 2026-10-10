{ lib, llvmPackages_21, cmake, ninja, pkg-config, perl, python3, ruby, icu, patchelf
, src
, expectedCgroupNamespace
}:

assert lib.hasPrefix "cgroup:[" expectedCgroupNamespace;
llvmPackages_21.stdenv.mkDerivation {
  pname = "spoonbill-experimental-jsc-cell-ledger";
  version = "webkitgtk-2.50.4";
  inherit src;

  # The caller supplies the already hash-verified official archive. This file
  # never downloads a different source or opts into a browser build.
  patches = [ ./cumulative-allocation.patch ];
  nativeBuildInputs = [ cmake ninja pkg-config perl python3 ruby patchelf ];
  buildInputs = [ icu ];
  enableParallelBuilding = false;
  strictDeps = true;
  dontStrip = true;

  # CMake probes invoke a compiler, so containment is checked before configure,
  # again before build, and before executing the oracle.
  preConfigure = ''
    checkPrototypeContainment() {
      test "$(id -u)" != 0 || { echo 'prototype-refused:root-builder' >&2; exit 125; }
      test "$(cat /proc/self/cgroup)" = '0::/' || { echo 'prototype-refused:wrong-cgroup' >&2; exit 125; }
      test "$(readlink /proc/self/ns/cgroup)" = ${lib.escapeShellArg expectedCgroupNamespace} || {
        echo 'prototype-refused:wrong-cgroup-namespace' >&2; exit 125;
      }
      awk '$1 == "CapEff:" { found=1; if ($2 != "0000000000000000") exit 1 } END { if (!found) exit 1 }' /proc/self/status || {
        echo 'prototype-refused:effective-capabilities' >&2; exit 125;
      }
    }
    checkPrototypeContainment
    export CMAKE_BUILD_PARALLEL_LEVEL=1
    export NIX_BUILD_CORES=1
    cmakeFlagsArray+=(
      "-DCMAKE_C_FLAGS_RELEASE=-O1 -g0 -DNDEBUG"
      "-DCMAKE_CXX_FLAGS_RELEASE=-O1 -g0 -DNDEBUG"
    )
  '';
  preBuild = "checkPrototypeContainment";
  cmakeBuildType = "Release";
  cmakeFlags = [
    "-GNinja"
    "-DPORT=JSCOnly"
    "-DEVENT_LOOP_TYPE=Generic"
    "-DENABLE_TOOLS=OFF"
    "-DENABLE_JAVASCRIPT_SHELL=ON"
    "-DENABLE_REMOTE_INSPECTOR=OFF"
    "-DUSE_LIBBACKTRACE=OFF"
    "-DENABLE_C_LOOP=ON"
    "-DENABLE_JIT=OFF"
    "-DENABLE_DFG_JIT=OFF"
    "-DENABLE_FTL_JIT=OFF"
    "-DENABLE_WEBASSEMBLY=OFF"
    "-DENABLE_WEBASSEMBLY_BBQJIT=OFF"
    "-DENABLE_WEBASSEMBLY_OMGJIT=OFF"
    "-DENABLE_SAMPLING_PROFILER=OFF"
    "-DENABLE_UNIFIED_BUILDS=ON"
    "-DDEVELOPER_MODE=OFF"
    "-DDEBUG_FISSION=OFF"
    "-DENABLE_STATIC_JSC=OFF"
    "-DLTO_MODE="
    "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF"
  ];
  buildPhase = ''
    runHook preBuild
    ninja -j1 jsc
    runHook postBuild
  '';
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    checkPrototypeContainment
    timeout 90 bin/jsc --preciseAllocationCutoff=400 ${./oracle.js}
    runHook postCheck
  '';
  installPhase = ''
    runHook preInstall
    mkdir -p "$out/bin" "$out/lib" "$out/share/jsc-prototype"
    cp bin/jsc "$out/bin/"
    cp -a lib/libJavaScriptCore* "$out/lib/"
    cp ${./oracle.js} "$out/share/jsc-prototype/oracle.js"
    cp CMakeCache.txt "$out/share/jsc-prototype/"
    runHook postInstall
  '';
  # Keep the installed shell's lookup independent of its temporary build tree.
  postFixup = ''
    for binary in "$out/bin/jsc" "$out"/lib/libJavaScriptCore*; do
      test ! -L "$binary" || continue
      runtimePath="$out/lib"
      IFS=: read -ra entries <<< "$(patchelf --print-rpath "$binary")"
      for entry in "''${entries[@]}"; do
        case "$entry" in /nix/store/*) runtimePath="$runtimePath:$entry" ;; esac
      done
      patchelf --set-rpath "$runtimePath" "$binary"
    done
  '';
  meta = {
    description = "Experimental interpreter-only cumulative JSC GC-cell allocation ledger";
    platforms = lib.platforms.linux;
    license = lib.licenses.lgpl21Plus;
  };
}
