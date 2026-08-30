# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Multi-module build (all modules)
mvn clean install          # Build and run all tests
mvn test                   # Run tests only
mvn validate               # Run license header check only

# Single module build
mvn clean install -pl jetty-cluster-orchestrator-api     # Build API module only
mvn test -pl jetty-cluster-orchestrator-ssh              # Test SSH module only
mvn test -pl jetty-cluster-orchestrator-k8s              # Test K8s module only

# Single test execution
# Surefire fails any module with no match, so scope with -pl or disable that check.
mvn test -pl jetty-cluster-orchestrator-ssh -Dtest=ClusterTest
mvn test -pl jetty-cluster-orchestrator-ssh -Dtest=ClusterTest#testCluster
mvn test -Dtest=ClusterTest -Dsurefire.failIfNoSpecifiedTests=false   # across all modules
```

CI matrix: JDK 17, 21, 25 on Ubuntu (`.github/workflows/ci.yml`). CI runs plain `mvn clean install`,
which now includes the k3s-backed Kubernetes tests — they need Docker on the runner and add ~2.5 min.

## Project Overview

Jetty Cluster Orchestrator is a Java 17+ library for writing multi-JVM tests. It spawns JVM processes (locally, over SSH, or as Kubernetes pods), serializes lambdas to those JVMs for execution, and provides coordination primitives (barriers, atomic counters) via Apache ZooKeeper/Curator.

**Not for production use** - designed only for multi-machine testing with no network failure recovery.

## Multi-Module Structure

The project is organized as a multi-module Maven build:

- **`jetty-cluster-orchestrator-api`**: Core orchestration logic, interfaces, RPC framework, `LocalHostLauncher`, coordination tools
- **`jetty-cluster-orchestrator-ssh`**: SSH/SFTP implementation (`SshRemoteHostLauncher`, `SFTPNodeFileSystem`) 
- **`jetty-cluster-orchestrator-k8s`**: Kubernetes implementation (`KubernetesRemoteHostLauncher`, `KubernetesNodeFileSystem`)

### Module Dependencies
- SSH and K8s modules depend on the API module
- Implementation modules use ServiceLoader for pluggable filesystem providers
- Users can include only the modules they need (e.g., API + SSH for SSH-only deployments)

## Architecture

The core flow: `Cluster` asks its `HostLauncher` for a ZooKeeper connect string, has the launcher start the host processes of each node array, then spawns worker nodes within those hosts. Lambdas (`NodeJob`) are serialized and sent to nodes via an RPC layer built on Curator distributed queues.

Key layers:
- **Configuration** (`configuration/`): `ClusterConfiguration` -> `NodeArrayConfiguration` -> `Node`. Fluent builder API. `Node` is an identity-only interface (`getId()`/`getHostname()`); each launcher ships its own `NodeArrayConfiguration` and, when it needs one, its own `Node` type:
  - `LocalNodeArrayConfiguration` + `LocalHostLauncher` (in-process, api module)
  - `SshNodeArrayConfiguration` + `SshRemoteHostLauncher` (SSH/SFTP, ssh module)
  - `K8sNodeArrayConfiguration` + `K8sNode` + `KubernetesRemoteHostLauncher` (fabric8 kubernetes-client, k8s module)
  `AbstractHostLauncher` (api module) holds what every launcher needs: the node-array type check, host dedup by hostname, the parallel launch of an array's hosts, and reuse of a host shared by several arrays.
- **RPC** (`rpc/`): `RpcClient`/`RpcServer` communicate via ZooKeeper `SimpleDistributedQueue`. Commands are serialized Java objects (`Command` interface in `rpc/command/`): `SpawnNodeCommand`, `ExecuteNodeJobCommand`, `CheckNodeCommand`, `KillNodeCommand`.
- **Coordination** (`tools/`): `ClusterTools` provides `Barrier` (non-cyclic, single-use) and `AtomicCounter` backed by Curator recipes.
- **Node Filesystem** (`nodefs/`): Read-only NIO `FileSystem` provider (URI scheme `jco:`) registered via Java SPI for transparent read access to remote node working directories. Uses `NodeFileSystemFactory` interface for pluggable implementations: `SFTPNodeFileSystemFactory` (SSH module) and `KubernetesNodeFileSystemFactory` (K8s module). Files >1MB use piped streaming.
- **Process Management** (`util/`): `ProcessHolder` manages spawned JVM processes via `ProcessHandle` API. `ZooKeeperServer` wraps embedded ZK. `JvmUtil` resolves java executables across platforms.

**Two-tier node hierarchy**: Host nodes (one per machine) are launched first via `HostLauncher`, then worker nodes are spawned as child JVMs on each host. `NodeProcess` serves as both the remote JVM entry point (`main()`) and a serializable process handle.

**Health monitoring**: Dual-direction — the cluster periodically sends `CheckNodeCommand` to verify nodes are alive; each `NodeProcess` has a keepalive thread that calls `System.exit(1)` if no commands arrive within the timeout.

**Classpath replication**: Every launcher copies the current JVM's classpath to `~/.jco/{hostId}/.classpath/` before launching child JVMs.

## Conventions

- **License**: Dual-licensed EPL-2.0 / Apache-2.0. All Java files must have the license header from `header-template.txt`, enforced by `license-maven-plugin` during the `validate` phase.
- **Package root**: `org.mortbay.jetty.orchestrator`
- **Code style**: Braces on same line as control structures (Jetty/K&R style).
- **Logging**: SLF4J with Logback for tests. Guard debug logs with `if (LOG.isDebugEnabled())`.
- **Resource management**: `AutoCloseable` used pervasively (`Cluster`, `RpcClient`, `RpcServer`, `HostLauncher`, `NodeProcess`, file systems). `IOUtil.close()` used for exception-swallowing cleanup.
- **No framework DI**: All wiring is manual constructor injection.
- **Serialization**: All user code passed to `executeOnAll()` must be `Serializable` — lambdas, commands, requests, and responses are all Java-serialized.
- **Tests**: JUnit 5 with Hamcrest assertions. `AbstractSshTest` provides an embedded Apache MINA SSHD server for SSH-based tests. Test helper `Closer` provides LIFO `AutoCloseable` cleanup.

## Gotchas

- **A cluster has exactly one launcher**: there is no `localhost` bypass. Whatever `hostLauncher()` returns launches every node, and it only accepts its own `NodeArrayConfiguration` type. `SimpleClusterConfiguration` defaults to `LocalHostLauncher`.
- **Host dedup lives in `AbstractHostLauncher`, not `Cluster`**: nodes sharing a hostname share a host JVM, whether they are in the same node array or not. `launchedHosts` maps hostname to a `CompletableFuture` so parallel node arrays racing for the same host all wait on the one launch. Override `checkSharedHost()` to reject nodes that share a host but ask for incompatible ones.
- **`SimpleClusterConfiguration.jvm()` must come before `nodeArray()`/`hostLauncher()`**: `ensureJvmSet()` runs eagerly at registration and the cluster JVM starts as a non-null default, so a later `.jvm()` never propagates. Set the JVM on the node array itself if order is awkward.
- **K8s `spec.hostname` ≤63 chars**: `nodeId.getHostId()` is a composite cluster-scoped string (89+ chars) — never use it as `spec.hostname`. Use the first DNS label of `nodeId.getHostname()` instead (via `podHostnameFor()`), or Kubernetes rejects the pod with 422.
- **`K8sNode` is immutable**: all fields are `final` and `withNodeSelectors()` returns a new instance. Array-level and node-level selectors are merged in `K8sNodeArrayConfiguration.nodes()`, which builds fresh nodes rather than mutating the declared ones.
- **Pod labels must include hostname for service routing**: When using `.withServicePort()`, the pod must have label `hostname: <node.getHostname()>` for the service selector to work. The launcher merges this hostname label with any custom labels from `node.getLabels()`. Previous bug: calling `.withLabels()` twice overwrites instead of merging — now fixed by creating a merged HashMap before building the pod.
- **Service DNS propagation**: After creating a Kubernetes Service, DNS names may take 100-500ms to propagate cluster-wide. The launcher automatically calls `waitForServiceEndpoints()` (30s timeout) after service creation to ensure the service is bound to the pod and DNS is ready before proceeding. This prevents "Connection refused" errors when other pods immediately try to connect.
- **ZooKeeper configuration**: ZooKeeper connections use configurable retry policies. Configure with system properties like `-Djco.curator.retry.maxRetries=5` if needed. See README.adoc for full configuration options.
- **Downstream consumer**: `jetty-perf` (`common/.../PerfTestParams.java`, `common/.../assertions/Assertions.java`) builds `ClusterConfiguration`s against this API and breaks whenever it changes. `Assertions` reads `Node.getId()` *after* the cluster is up to build report paths, which is why `Node` keeps an identity surface in the launcher-agnostic api module.
- **fabric8 mock server** (`kubernetes-server-mock`): Only supports exact URL path matching (`withPath()`), not regex. Upload/command URLs embed full args and classpath, making them impossible to match in unit tests — put full-launch tests in an integration test instead.
- **K8s integration tests**: `KubernetesClusterTest` starts a throwaway k3s cluster in Docker via Testcontainers, so `mvn test -pl jetty-cluster-orchestrator-k8s` just works; it skips only when Docker is unavailable. Pass `-Dkubernetes.config.path=<kubeconfig>` to use an existing cluster, `-Dk8s.image=<image>` for the node image (needs a JRE and `tar`), `-Dk3s.image=<image>` for the k3s one.
