Ideal Graph Visualizer or IGV is a developer tool, currently maintained as part of the GraalVM compiler, recommended for performance issues investigation.

The tool is essential for any language implementers building on top of **GraalVM Enterprise Edition**.
It is available as a separate download on [Oracle Technology Network](https://www.oracle.com/technetwork/oracle-labs/program-languages/downloads/index.html) and requires accepting the Oracle Technology Network Developer License.

Ideal Graph Visualizer is developed to view and inspect interim graph representations from GraalVM and Truffle compilations.


1.Unzip the downloaded package and enter `bin` directory:

```
$ cd idealgraphvisualizer/bin
```

2.Launch the tool:

```
$ idealgraphvisualizer
```

3.Save the following code snippet as  `Test.rb`:

```
require 'json'

obj = {
  time: Time.now,
  msg: 'Hello World',
  payload: (1..10).to_a
}

encoded = JSON.dump(obj)

js_obj = Polyglot.eval('js', 'JSON.parse').call(encoded)

puts js_obj[:time]
puts js_obj[:msg]
puts js_obj[:payload].join(' ')
```

4.From another console window, make sure `ruby` component is installed in GraalVM,
and connect `Test.rb` script to the running IGV:

```shell
$ gu list
$ ruby --jvm --vm.Dgraal.Dump=:1 --vm.Dgraal.PrintGraph=Network Test.rb
```
This causes GraalVM to dump compiler graphs in IGV format over the network to an IGV process listening
on `127.0.0.1:4445`. Once the connection is made, you are able to see the graphs in the Outline window.
Find e.g., `java.lang.String.char(int)` folder and open its _After parsing_ graph by double-clicking.
If the node has `sourceNodePosition` property, then the Processing Window will attempt to display its location and the entire stacktrace.

### Browsing Graphs
Once a specific graph is opened, you can search for nodes by name, ID, or by `property=value` data, and all matching results will be shown.
Another cool feature of this tool is the ability to navigate to the original guest language source code!
Select a node in graph and press 'go to source' button in the Stack View window.

![](/docs/img/IGV_navigate_to_source.png)

Graphs navigation is available also from the context menu, enabled by focusing
and right-clicking a specific graph node. Extract nodes option will re-render
a graph and display just selected nodes and their neighbours.

![](/docs/img/IGV_context_menu.png)

If the graph is larger than the screen, manipulate with the 'satellite' view button
in the main toolbar to move the viewport rectangle.

![](/docs/img/IGV_satellite_view.png)

For user preference, the graph color scheme is adjustable by editing
the Coloring filter, enabled by default in the left sidebar.

### Viewing Source Code

Source code views can be opened in manual and assisted modes. Once you select a node
in the graph view, the Processing View will open. If IGV knows where the source code
for the current frame is, the green 'go to source' arrow is enabled. If IGV does not
know where the source is, the line is grayed out and a 'looking glass' button appears.

![](/docs/img/IGV_add_source.png)

Press it and select "Locate in Java project" to locate the correct project in the dialog.
IGV hides projects which do not contain the required source file.
The "Source Collections" serves to display the stand alone roots added by "Add root of sources" general action.
If the source is located using the preferred method (i.e., from a Java project),
its project can be later managed on the Project tab. That one is initially hidden,
but you can display the list of opened projects using Window - Projects.

### Dumping Graphs

The IGV tool is developed to allow GraalVM language implementers
to optimize their languages assembled with the Truffle framework. As a development
tool it should not be installed to production environments.

To dump the GraalVM compiler graphs from an embedded Java application to IGV,
you need to add options to GraalVM based processes. Depending on the language/VM
used, you may need to prefix the options by `--vm`. See the particular
language's documentation for the details. The main option to add is
`-Dgraal.Dump=:1`. This will dump graphs in an IGV readable format to the local
file system. To send the dumps directly to IGV over the network, add
`-Dgraal.PrintGraph=Network` when starting a GraalVM instance. Optionally a
port can be specified. Then dumps are sent to IGV from the running GraalVM on
localhost. If IGV does not listen on localhost, options "Ideal Graph Settings|
Accept Data from network" can be checked. If there is not an IGV instance
listening on `127.0.0.1` or it cannot be connected to, the dumps will be
redirected to the local file system. The file system location is `graal_dumps/`
under the current working directory of the process and can be changed with the
`-Dgraal.DumpPath` option.

In case an older GraalVM is used, you may need to explicitly request that dumps
include the `nodeSourcePosition` property. This is done by adding the
`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` options.
