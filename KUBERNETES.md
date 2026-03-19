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

## Maven Dependency

```xml
<dependency>
    <groupId>org.mortbay.jetty.orchestrator</groupId>
    <artifactId>jetty-cluster-orchestrator</artifactId>
    <version>2.0-SNAPSHOT</version>
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
| `controllerHost(String)` | auto-detected | IP of the controller machine, used when `manageZooKeeper(false)` so pods can reach the embedded ZK |
| `manageZooKeeper(boolean)` | `true` | `true` — launcher creates a `zookeeper:3.9` pod; `false` — pods connect to the controller's embedded ZK |

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

**ZooKeeper coordination modes.**
The launcher supports two coordination modes:

1. **Managed ZooKeeper (default):** Creates a `zookeeper:3.9` pod and ClusterIP service in your namespace. 
   The controller connects via `LocalPortForward`, requiring no inbound network access to the controller machine.
   Pods connect to the ZooKeeper service within the cluster.

2. **Embedded ZooKeeper:** Use `manageZooKeeper(false)` to connect pods directly to the controller's embedded 
   ZooKeeper. Requires pods to reach the controller IP (auto-detected or set via `controllerHost()`).
