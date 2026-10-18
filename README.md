# teavm-example-1

Minimal reproduction of a crash in the C backend of TeaVM (0.16.0): the garbage collector does
not keep alive (nor relocate) the `Object.Monitor` of an object, so a GC that runs inside a
`synchronized` block corrupts the monitor.

The program ([`MonitorGc.java`](src/main/java/MonitorGc.java)) takes a monitor (`synchronized`
block, then `synchronized` method) and allocates a lot of short-lived objects while holding it.
On the JVM it prints `MonitorGc: done`.

## Reproduce

Requirements: JDK 17+, CMake, a C compiler (Visual Studio Build Tools on Windows). Gradle is not
needed: the Gradle wrapper is included.

Windows (x64 or ARM64):

```
build-c.bat
bin\Release\monitorgc.exe
echo %ERRORLEVEL%
```

(`build-c.bat` runs `gradlew.bat generateC` first if the C sources are not generated yet, then CMake.
`build-c.bat x64` or `build-c.bat ARM64` forces the architecture; otherwise the one of the machine is used.)

Other platforms (not tested by me):

```
./gradlew generateC
cmake -S . -B build/cmake
cmake --build build/cmake
./bin/monitorgc
```

The heap settings are the defaults of TeaVM (1 MB to 16 MB).

## Result

Windows 11 ARM64, Visual Studio 2022 Build Tools (cl.exe ARM64), JDK 17, TeaVM 0.16.0:
the executable prints nothing and exits with code 0xC0000005 (access violation) during the
first round, as soon as a GC runs inside the first `synchronized` block.

## How it was found

The problem first appeared in a large application (PlantUML, about 2400 generated C files)
that terminated silently with 0xC0000005. Under a debugger, the access violation is in
`ArrayDeque.isEmpty` called with a garbage `this` (a small integer):

```
meth_ju_ArrayDeque_isEmpty                  (ArrayDeque.c)
meth_jl_Object_isEmptyMonitor+0x70          (Object.c)
meth_jl_Object_monitorExitSync+0x60         (Object.c)
meth_nsp_Lazy_get+0xec                      (Lazy.c)       <- a "synchronized" method that allocates
...
```

The crash is deterministic for a given input and depends on when the GC runs: starting the heap at
its maximum size (64 MB) made it disappear, because no GC ran during short executions.

## Analysis (from the generated C)

* `monitorEnterSync` calls `createMonitor`, which allocates an `Object.Monitor` and stores it only in
  the `hash` field of the object header, packed as `((address - heapAddress) / 4) | 0x80000000`
  (`TEAVM_PACK_MONITOR`).
* In `GC.c`, `markFields` and `updatePointersInFields` only walk the `layout` of the class, and the
  layout of `java.lang.Object` is `NULL`. The monitor referenced from the header is therefore
  neither marked nor relocated.
* After a collection the `Monitor` is considered garbage (its memory is reused), or it is moved while
  `hash` keeps the old offset. `monitorExitSync` then reads a stale `Monitor` (`owner`,
  `enteringThreads`, `notifyListeners`) and crashes, or throws `IllegalMonitorStateException`.

Possible fix: when marking an object whose `hash` has the high bit set, also mark the corresponding
`Monitor`; when updating pointers after defragmentation, rewrite the packed value with the new
address of the `Monitor`.
