## Memory Management Configuration

When running a native image you can specify:
`-Xmn` - to set the size of the young generation (the amount of memory that can be allocated without triggering a GC). The value is specified in bytes, suffix `k`, `m`, or `g` can be used for scaling for example: `-Xmn16M`, default `256M`.
`-Xmx` - maximum heap size in bytes. Note that this is not the maximum amount of consumed memory, because during GC the system can request more temporary memory, for example `-Xmx16M`, default unlimited.
`-Xms` - minimum heap size in bytes. The value is specified in bytes, suffix `k`, `m`, or `g` can be used for scaling. Heap space that is unused will be retained for future heap usage, rather than being returned to the operating system.

`-XX:+PrintGC`: prints some information about garbage collections.
`-XX:+VerboseGC`: prints detailed information about garbage collections.

## Low Latency Garbage Collection

When using GraalVM Enterprise you can enable the experimental option to use a lower latency garbage collection implementation.
A lower latency GC should improve the performance of the native image applications by reducing the stop-the-world pauses.

To enable it use the `-H:+UseLowLatencyGC` option when building a native image.

Currently the lower latency GC works on Linux in the AMD64 builds.
There are also a few limitations to which configurations it supports:
* Weak and other special references are currently not supported. All references are treated as strong references instead.
* Just-in-time (JIT) compiled code is not supported yet.
* Setting the runtime heap size via `-Xms/-Xmx/-Xmn` is currently not supported. It is however possible to specify values for those options at image build time, e.g.: `-R:MinHeapSize=256m -R:MaxHeapSize=2g -R:MaxNewSize=128m`.

You can use `-XX:+PrintGC`, `-XX:+VerboseGC` to get some information about garbage collections.
