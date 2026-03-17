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
    .nodeSelector("kubernetes.io/hostname", "k8s-node-2")
    .node(new Node("loader-1"))
    .node(new Node("loader-2"))
```

**Node level** — set directly on the `Node` object (returns a new immutable instance):

```java
new Node("server", "server")
    .nodeSelector("kubernetes.io/hostname", "k8s-node-1")
    .nodeSelector("beer", "australian")
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

**`clusterConfiguration` is transient so do not call `getClusterConfiguration()` inside a node lambda.**
The `ClusterConfiguration` object is marked `transient` and will not be serialized to the pod.
Any values the lambda needs (hostnames, participant counts, etc.) must be captured in non-transient fields
before the lambda is created.

**`rootPathOf()` is read-only and backed by `kubectl exec`.**
The launcher registers a `jco:` NIO filesystem for each pod at launch time, so `rootPathOf(id)` works for
Kubernetes nodes the same way it does for SSH nodes. Use it to download files after the test, not to write them.
For absolute paths inside the pod, use `rootPathOf(id).getFileSystem().getPath("/abs/path")`.

**Image must include all required tools.**
`eclipse-temurin:21-jre` ships no `mpstat`, `sar`, `iostat`, or `perf`. Build a custom image if monitoring
is needed (see `docker/Dockerfile` in jetty-perf for a minimal Ubuntu 24.04 + temurin-21 + sysstat example).

**Pod hostname limit.**
Kubernetes DNS labels must be ≤ 63 characters. The launcher truncates automatically, but keep node IDs short
to avoid collisions between truncated names.

**ZooKeeper pod lifecycle.**
By default the launcher creates a `zookeeper:3.9` pod and a ClusterIP service in your namespace for
coordination, and deletes them when the `Cluster` is closed. It exposes the ZooKeeper address to `Cluster`
via `HostLauncher.getZooKeeperConnectString()`, so the controller connects through a `LocalPortForward`
and no inbound network access to the controller machine is required. Use `manageZooKeeper(false)` to skip
this and connect pods to the controller's embedded ZooKeeper instead (pods must be able to reach the
controller IP, auto-detected or set via `controllerHost()`).
