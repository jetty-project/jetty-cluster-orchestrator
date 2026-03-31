# Kubernetes Mode

`jetty-cluster-orchestrator` can launch each host node as a Kubernetes pod instead of a local process or SSH remote.
Coordination uses a ZooKeeper pod (managed automatically by default, or delegated to an external ensemble).
Remote file access uses the `jco:` NIO filesystem backed by `kubectl exec`, so `NodeArray.rootPathOf()` and report
download work the same way as in SSH mode.

## Prerequisites

- A running Kubernetes cluster reachable from the machine that runs the tests
- A `kubeconfig` file with permission to create/delete pods and services in the target namespace
- A container image that includes a JDK and any monitoring tools the test needs
  - `eclipse-temurin:21-jre` works for basic tests
  - Build a custom image (see `docker/Dockerfile` in jetty-perf for an example) if you need `mpstat`, `sar`,
    `iostat`, or async-profiler

## Maven Dependencies

For Kubernetes functionality, you need both the core API and Kubernetes modules:

```xml
<!-- Core API (required) -->
<dependency>
    <groupId>org.mortbay.jetty.orchestrator</groupId>
    <artifactId>jetty-cluster-orchestrator-api</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>

<!-- Kubernetes support (required for K8s functionality) -->
<dependency>
    <groupId>org.mortbay.jetty.orchestrator</groupId>
    <artifactId>jetty-cluster-orchestrator-k8s</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## Basic Usage

```java
KubernetesRemoteHostLauncher launcher = new KubernetesRemoteHostLauncher.Builder()
    .namespace("my-namespace")
    .image("eclipse-temurin:21-jre")
    .kubernetesConfig(Paths.get(System.getProperty("user.home"), ".kube", "config"))
    .build();

ClusterConfiguration cfg = new SimpleClusterConfiguration()
    .hostLauncher(launcher)
    .nodeArray(new SimpleNodeArrayConfiguration("servers")
        .jvm(new Jvm((fs, h) -> "java", "-Xmx1g"))
        .node(new Node("server", "server")))
    .nodeArray(new SimpleNodeArrayConfiguration("loaders")
        .jvm(new Jvm((fs, h) -> "java", "-Xmx512m"))
        .node(new Node("loader-1"))
        .node(new Node("loader-2")));

try (Cluster cluster = new Cluster(cfg)) {
    NodeArray servers = cluster.nodeArray("servers");
    NodeArray loaders = cluster.nodeArray("loaders");

    servers.executeOnAll(tools -> {
        // start server ...
        tools.barrier("run-start", 3).await();
        tools.barrier("run-end", 3).await();
    });

    loaders.executeOnAll(tools -> {
        tools.barrier("run-start", 3).await();
        // run load ...
        tools.barrier("run-end", 3).await();
    }).get(2, TimeUnit.MINUTES);

    // Collect report files from pods
    for (String id : servers.ids()) {
        Path dest = Paths.get("reports", id);
        Files.createDirectories(dest);
        Files.copy(servers.rootPathOf(id).resolve("report.txt"), dest.resolve("report.txt"));
    }
}
```

## Kubernetes Services for Pod-to-Pod Communication

When a node needs to be reachable via a stable DNS name (e.g., a server that other pods connect to), use `.withServicePort()` to automatically create a Kubernetes Service:

```java
new SimpleNodeArrayConfiguration("servers")
    .node(new Node.Builder()
        .withId("server-1")
        .withHostname("server")
        .withServicePort(8080)  // Creates service "server" exposing port 8080
        .build())
```

The launcher will:
1. Create a ClusterIP Service named after the node's hostname (`server` in this example)
2. Configure the service to route traffic to the pod via label selector `hostname: server`
3. Wait for service endpoints to be ready (typically 200-500ms for DNS propagation)
4. Make the pod accessible at `server.<namespace>.svc.cluster.local:8080`

**DNS propagation wait**: The launcher automatically waits up to 30 seconds for the service endpoints to become ready. This ensures that other pods can immediately resolve the DNS name without encountering "Connection refused" or "Unknown host" errors.

**Example - Server and client pods:**
```java
ClusterConfiguration cfg = new SimpleClusterConfiguration()
    .hostLauncher(launcher)
    .nodeArray(new SimpleNodeArrayConfiguration("server")
        .node(new Node.Builder()
            .withId("server-1")
            .withHostname("server")
            .withServicePort(8080)
            .build()))
    .nodeArray(new SimpleNodeArrayConfiguration("clients")
        .node(new Node("client-1"))
        .node(new Node("client-2")));

try (Cluster cluster = new Cluster(cfg)) {
    String serverUrl = "http://server." + namespace + ".svc.cluster.local:8080";

    cluster.nodeArray("server").executeOnAll(tools -> {
        // Start HTTP server on port 8080
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);
        server.start();

        tools.barrier("ready", 3).await();
        tools.barrier("done", 3).await();
        server.stop();
    });

    cluster.nodeArray("clients").executeOnAll(tools -> {
        tools.barrier("ready", 3).await();
        // Connect to server via DNS name
        HttpClient client = new HttpClient();
        client.start();
        client.GET(serverUrl);  // DNS name is guaranteed to be ready
        tools.barrier("done", 3).await();
    });
}
```

## Node Selectors — Pinning Pods to Specific Nodes

Node selectors constrain which Kubernetes nodes a pod may be scheduled on. They can be set at two levels.

**Array level** — applies to every node in the array:

```java
new SimpleNodeArrayConfiguration("loaders")
    .withNodeSelector("kubernetes.io/hostname", "k8s-node-2")
    .node(new Node("loader-1"))
    .node(new Node("loader-2"))
```

**Node level** — set directly on the `Node` object (returns a new immutable instance):

```java
new Node("server", "server")
    .withNodeSelector("kubernetes.io/hostname", "k8s-node-1")
    .withNodeSelector("beer", "australian")
```

`Cluster` merges the two levels: array-level selectors are the base and node-level selectors are merged
on top, with node-level values winning on key conflicts.

## Builder Reference

| Method | Default | Description |
|---|---|---|
| `namespace(String)` | required | Kubernetes namespace to use (created if absent) |
| `image(String)` | required | Container image for all node pods |
| `kubernetesConfig(Path)` | required | Path to kubeconfig file |

## Collecting Files After the Test

`NodeArray.rootPathOf(id)` returns a read-only `Path` backed by the `jco:` NIO filesystem.
Walk it or copy individual files after `executeOnAll()` completes:

```java
for (String id : nodeArray.ids()) {
    Path dest = Paths.get("reports", id);
    Files.createDirectories(dest);
    Path remote = nodeArray.rootPathOf(id).resolve("perf.hlog");
    if (Files.exists(remote))
        Files.copy(remote, dest.resolve("perf.hlog"));
}
```

## Details and Gotchas

**No working directory in pods.**
The Fabric8 exec API does not set a working directory; pod processes start in the container default (`/root`
for `eclipse-temurin`), not in `$HOME/.jco/<nodeId>/`. Files written with relative paths land in `/root`.
Use `rootPathOf(id)` to resolve report files by their absolute path inside the pod.

**`rootPathOf()` is read-only and backed by `kubectl exec`.**
The launcher registers a `jco:` NIO filesystem for each pod at launch time, so `rootPathOf(id)` works for
Kubernetes nodes the same way it does for SSH nodes. Use it to download files after the test, not to write them.
For absolute paths inside the pod, use `rootPathOf(id).getFileSystem().getPath("/abs/path")`.

**Image must include all required tools.**
The base `eclipse-temurin:21-jre` image ships without monitoring tools like `mpstat`, `sar`, `iostat`, or `perf`. 
If your tests require system monitoring, build a custom image that includes the necessary tools (e.g., sysstat package).

**Pod hostname limit.**
Kubernetes DNS labels must be ≤ 63 characters. The launcher truncates automatically, but keep node IDs short
to avoid collisions between truncated names.

**ZooKeeper coordination.**
The launcher creates a `zookeeper:3.9` pod and ClusterIP service in your namespace for cluster coordination.
The controller connects via `LocalPortForward`, requiring no inbound network access to the controller machine.
Worker pods connect to the ZooKeeper service using cluster-internal DNS.
