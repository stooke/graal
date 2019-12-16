GraalVM comes with **GraalVM VisualVM**, an enhanced version of the popular
[VisualVM](https://visualvm.github.io) tool which includes special heap analysis
features for the supported guest languages. These languages and features are
currently available:

 - __Java:__ Heap Summary, Objects View, Threads View, OQL Console
 - __JavaScript:__ Heap Summary, Objects View, Thread View
 - __Python:__ Heap Summary, Objects View
 - __Ruby:__ Heap Summary, Objects View, Threads View
 - __R:__ Heap Summary, Objects View

### Starting GraalVM VisualVM
To start GraalVM VisualVM execute `jvisualvm`. Immediately after the startup,
the tool shows all locally running Java processes in the Applications area,
including the VisualVM process itself.

__Important:__ [GraalVM Native Image]({{ "/docs/reference-manual/aot-compilation/" | relative_url }}) does not implement JVMTI agent, hence triggering heap dump creation from Applications area is impossible. Apply `-H:+AllowVMInspection` flag with the `native-image` tool for Native Image processes. This way your application will handle signals and get a heap dump when it receives SIGUSR1 signal. Guest language REPL process must be started also with the `--jvm` flag to monitor it using GraalVM VisualVM. This functionality is available with [GraalVM Enterprise Edition](http://www.oracle.com/technetwork/oracle-labs/program-languages/downloads/index.html). It is **not** available in GraalVM open source version available on GitHub. See the [Generating Native Heap Dumps]({{ "/docs/reference-manual/native_heapdump/" | relative_url }}) page for details on creating heap dumps from a native image process.

### Getting Heap Dump
To get a heap dump of, for example, a Ruby application for later analysis,
first start your application, and let it run for a few seconds to warm up. Then
right-click its process in GraalVM VisualVM and invoke the Heap Dump action. A
new heap viewer for the Ruby process opens.

### Analyzing Objects
Initially the Summary view for the Java heap is displayed. To analyze the Ruby
heap, click the leftmost (Summary) dropdown in the heap viewer toolbar, choose
the Ruby Heap scope and select the Objects view. Now the heap viewer displays
all Ruby heap objects, aggregated by their type.

Expand the Proc node in the results view to see a list of objects of this type.
Each object displays its logical value as provided by the underlying
implementation. Expand the objects to access their variables and references,
where available.

![](/docs/img/HeapViewer_objects.png "GraalVM VisualVM Heap Viewer - analyzing objects")

Now enable the Preview, Variables and References details by clicking the buttons
in the toolbar and select the individual _ProcType_ objects. Where available, the
Preview view shows the corresponding source fragment, the Variables view shows
variables of the object and References view shows objects referring to the
selected object.

Last, use the Presets dropdown in the heap viewer toolbar to switch the view
from All Objects to Dominators or GC Roots. To display the heap dominators,
retained sizes must be computed first, which can take a few minutes for the
_server.rb_ example. Select the Objects aggregation in the toolbar to view the
individual dominators or GC roots.

![](/docs/img/HeapViewer_objects_dominators.png "GraalVM VisualVM Heap Viewer - analyzing objects")

### Analyzing Threads
Click the leftmost dropdown in the heap viewer toolbar and select the Threads
view for the Ruby heap. The heap viewer now displays the Ruby thread stack
trace, including local objects. The stack trace can alternatively be displayed
textually by clicking the HTML toolbar button.

![](/docs/img/HeapViewer_thread.png "GraalVM VisualVM Heap Viewer - analyzing thread")

### Reading JFR Snapshots
VisualVM tool bundled with GraalVM 19.2.x and later in both Community and Enterprise
editions has the ability to read JFR snapshots -- snapshots taken with JDK
Flight Recorder (previously Java Flight Recorder). JFR is a tool for collecting
diagnostic and profiling data about a running Java application. It is integrated
into the Java Virtual Machine (JVM) and causes almost no performance overhead,
so it can be used even in heavily loaded production environments.

To install the JFR support, released as a plugin:
1. run `<GRAALVM_HOME>/bin/jvisualvm` to start VisualVM;
2. navigate to Tools > Plugins > Available Plugins to list all available plugins and install the _VisualVM-JFR_ and
_VisualVM-JFR-Generic_ modules.

The JFR snapshots can be opened using either the File > Load action or by
double-clicking the JFR Snapshots node and adding the snapshot into the JFR
repository permanently. Please follow the documentation for your Java version to
create JFR snapshots.

The JFR viewer reads all JFR snapshots created from Java 7 and newer and presents the data in typical
VisualVM views familiar to the tool users.

![](/docs/img/visualvm_jfr.png "JFR viewer - read JFR snapshots")

These views and functionality are currently available:

* _Overview tab_ displays the basic information about the recorded process like
its main class, arguments, JVM version and configuration, and system properties.
This tab also provides access to the recorded thread dumps.
* _Monitor tab_ shows the process uptime and basic telemetry -- CPU usage, Heap
and Metaspace utilization, number of loaded classes and number of live & started
threads.
* _Threads tab_ reconstructs the threads timeline based on all events recorded in
the snapshot as precisely as possible, based on the recording configuration.
* _Locks tab_ allows to analyze threads synchronization.
* _File IO tab_ presents information on read and write events to the filesystem.
* _Socket IO tab_ presents information on read and write events to the network.
* _Sampler tab_ shows per-thread CPU utilization and memory allocations, and a
heap histogram. There is also an experimental feature "CPU sampler" building CPU
snapshot from the recorded events. It does not provide an exact performance
analysis but still helps to understand what was going on in the recorded
application and where the CPU bottleneck might be.
* _Browser tab_ provides a generic browser of all events recorded in the snapshot.
* _Environment tab_ gives an overview of the recording machine setup and condition
like CPU model, memory size, operating system version, CPU utilization, memory
usage, etc..
* _Recording tab_ lists the recording settings and basic snapshot telemetry like
number of events, total recording time, etc..

Warning: The support of JDK Flight Recorder is currently [experimental](https://docs.oracle.com/en/graalvm/enterprise/19/guide/overview/license/licensing-information.html). Some advanced features like
analyzing JVM internals, showing event stack traces or support for creating JFR
snapshots from live processes are not available in this preview version and will
be addressed incrementally in the following releases.
