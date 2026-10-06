# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Multi-module build (all modules)
mvn clean install          # Build and run all tests
mvn test                   # Run tests only
mvn validate               # Spotless check only (formatting, imports, license headers, pom order)
mvn -Pmay-spotless-apply validate   # Reformat everything instead of just complaining

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

Needs Maven 3.9+ and JDK 17+ (enforcer). `-Dorg.mortbay.jetty.orchestrator.skipDiskCleanup=true` keeps
the `~/.jco/<hostId>` folders after a run, which helps when debugging a node.

The surefire config in the root `pom.xml` sets test-only system properties: shorter ZooKeeper timeouts,
`jco.zookeeper.retry.maxRetries=1`, and `skipExitOnHealthCheckFailure=true` (see `LocalLauncher` below).

CI:
- GitHub Actions (`.github/workflows/ci.yml`): JDK 17, 21, 25 on Ubuntu, plain `mvn clean install`.
- Jenkins (`Jenkinsfile`): `linux-dind` agents, JDK 17 and JDK 21 (the stage called "JDK25" really runs
  jdk21). Snapshots get deployed from `main` on the JDK 17 leg.
- Both run the k3s-backed Kubernetes tests, so the agent needs Docker. That adds about 2.5 min.

## Project Overview

Jetty Cluster Orchestrator is a Java 17+ library for writing multi-JVM tests. It spawns JVM processes (locally, over SSH, or as Kubernetes pods), serializes lambdas to those JVMs for execution, and provides coordination primitives (barriers, atomic counters) via Apache ZooKeeper.

**Not for production use** - designed only for multi-machine testing with no network failure recovery.

User docs live in `README.adoc` (API, configuration properties) and `KUBERNETES.md` (K8s mode).

## Multi-Module Structure

- **`jetty-cluster-orchestrator-api`**: Core orchestration logic, interfaces, RPC framework, `LocalLauncher`, coordination tools
- **`jetty-cluster-orchestrator-ssh`**: SSH/SFTP implementation on Apache MINA SSHD (`SshRemoteHostLauncher`, `SFTPNodeFileSystem`)
- **`jetty-cluster-orchestrator-k8s`**: Kubernetes implementation on fabric8 (`KubernetesRemoteHostLauncher`, `KubernetesNodeFileSystem`)

SSH and K8s depend on the API module and plug in their filesystems through `META-INF/services`. Users only pull in the modules they need.

## Architecture

The core flow: `Cluster` calls `HostLauncher.initialize()` to get a ZooKeeper connect string, has the launcher start the host processes of each node array, then spawns worker nodes within those hosts. Lambdas (`NodeJob`) are serialized and sent to nodes via an RPC layer built on ZooKeeper distributed queues.

Key layers:
- **Configuration** (`configuration/`): `ClusterConfiguration` -> `NodeArrayConfiguration` -> `Node`. Fluent builder API. `Node` is an identity-only interface (`getId()`/`getHostname()`, `SimpleNode` is the plain one); each launcher ships its own `NodeArrayConfiguration` and, when it needs one, its own `Node` type:
  - `LocalNodeArrayConfiguration` + `LocalLauncher` (api module)
  - `SshNodeArrayConfiguration` + `SshRemoteHostLauncher` (ssh module)
  - `K8sNodeArrayConfiguration` + `K8sNode` + `KubernetesRemoteHostLauncher` (k8s module, built with `KubernetesRemoteHostLauncher.Builder`)
  `AbstractHostLauncher` (api module) holds what every launcher needs: the node-array type check, host dedup by hostname, the parallel launch of an array's hosts, and reuse of a host shared by several arrays.
- **RPC** (`rpc/`): `RpcClient`/`RpcServer` talk through a ZooKeeper `DistributedQueue`. Commands are serialized Java objects (`Command` interface in `rpc/command/`): `SpawnNodeCommand`, `ExecuteNodeJobCommand`, `CheckNodeCommand`, `KillNodeCommand`.
- **Coordination** (`tools/`): `ClusterTools` provides `Barrier` (non-cyclic, single-use) and `AtomicCounter`. There is no Curator: `util/ZooKeeperClient` re-implements the few Curator recipes we need (counter, double barrier, queue) on the raw ZooKeeper client, and reads the `jco.zookeeper.*` timeout and retry properties.
- **Node Filesystem** (`nodefs/`): Read-only NIO `FileSystem` provider (URI scheme `jco:`) registered via Java SPI, giving read access to remote node working directories. Each remote module plugs in a `NodeFileSystemFactory`: `SFTPNodeFileSystemFactory` (SSH) and `KubernetesNodeFileSystemFactory` (K8s). `LocalLauncher` has none: its `rootPathOf()` returns a plain `Path`.
- **Process Management** (`util/`): `ProcessHolder` manages spawned JVM processes via `ProcessHandle` API. `ZooKeeperServer` wraps embedded ZK. `JvmUtil` and `FilenameSupplier` find the java executable to run.

**Where ZooKeeper runs** depends on the launcher's `initialize()`:
- `LocalLauncher` and `SshRemoteHostLauncher` start an embedded `ZooKeeperServer` in the test JVM. SSH hosts reach it through a remote port forward bound to `127.0.0.1`, not `localhost`, because `localhost` can resolve to `::1` and the ZK client then randomly fails to connect.
- `KubernetesRemoteHostLauncher` runs ZK as its own pod (`zookeeper:3.9`, override with `-Dzookeeper.image.name`). The test JVM reaches it through a fabric8 port-forward, the other pods through cluster DNS.

**Two-tier node hierarchy**: Host nodes (one per machine) are launched first via `HostLauncher`, then worker nodes are spawned as child JVMs on each host. `NodeProcess` serves as both the remote JVM entry point (`main()`) and a serializable process handle. `LocalLauncher` is the exception at the host tier: it only accepts the hostname `localhost`, and runs that one host node as a thread inside the test JVM (`NodeProcess.spawnThread()`). Its worker nodes are still child JVMs.

**Health monitoring**: Both ways. The cluster sends `CheckNodeCommand` every `healthCheckDelay` (5s default), and each `NodeProcess` has a keepalive thread that calls `System.exit(1)` if nothing arrives within `healthCheckTimeout` (30s default). Because a local host node lives in the test JVM, tests set `-Dorg.mortbay.jetty.orchestrator.skipExitOnHealthCheckFailure=true` so a missed check doesn't kill surefire.

**Default JVM**: `SimpleClusterConfiguration` defaults to `JvmUtil.mavenToolchainsOrJavaOnPath("[17")`. It looks for a JDK 17+ in `~/.m2/toolchains.xml` (or `~/.m2/discovered-jdk-toolchains-cache.xml`) on *each host's* filesystem, and falls back to `java` on the PATH.

**Classpath replication**: Every launcher copies the current JVM's classpath to `~/.jco/{hostId}/.classpath/` before launching child JVMs.

## Conventions

- **License**: Dual-licensed EPL-2.0 / Apache-2.0. All Java files must have the license header from `header-template.txt`, enforced by spotless during the `validate` phase.
- **Package root**: `org.mortbay.jetty.orchestrator`
- **Code style**: spotless with the `princeOfSpace` formatter: 4-space indent, 132-column lines, `WIDE` wrap style, trailing commas, unused imports removed. Import order is `java`, `javax`, everything else, then static. Spotless also sorts the `pom.xml` files (sortPom). `.mvn/jvm.config` holds javac `--add-exports` that came in with spotless for the formatter. Wrap anything the formatter should not touch in `// spotless:off` / `// spotless:on`.
- **Mechanical reformat commits** go in `.git-blame-ignore-revs`.
- **Logging**: SLF4J with Logback for tests. Guard debug logs with `if (LOG.isDebugEnabled())`.
- **Resource management**: `AutoCloseable` used pervasively (`Cluster`, `RpcClient`, `RpcServer`, `HostLauncher`, `NodeProcess`, file systems). `IOUtil.close()` used for exception-swallowing cleanup.
- **No framework DI**: All wiring is manual constructor injection.
- **Serialization**: All user code passed to `executeOnAll()` must be `Serializable`: lambdas, commands, requests, and responses are all Java-serialized.
- **Tests**: JUnit 5 with Hamcrest assertions. `AbstractSshTest` (ssh module) starts an embedded Apache MINA SSHD server for SSH-based tests. Test helper `Closer` (ssh module, `utils/`) provides LIFO `AutoCloseable` cleanup.

## Gotchas

- **A cluster has exactly one launcher**: there is no `localhost` bypass. Whatever `hostLauncher()` returns launches every node, and it only accepts its own `NodeArrayConfiguration` type. `SimpleClusterConfiguration` defaults to `LocalLauncher`.
- **Host dedup lives in `AbstractHostLauncher`, not `Cluster`**: nodes sharing a hostname share a host JVM, in the same node array or not. `launchedHosts` maps a hostname to a `CompletableFuture`, so arrays racing for the same host all wait on the one launch. Override `checkSharedHost()` to reject nodes that share a host but want different ones.
- **`SimpleClusterConfiguration.jvm()` must come before `nodeArray()`/`hostLauncher()`**: `ensureJvmSet()` runs at registration and the cluster JVM starts non-null, so a later `.jvm()` never reaches them. Set the JVM on the node array itself if the order is awkward.
- **K8s `spec.hostname` ≤63 chars**: `nodeId.getHostId()` is a composite cluster-scoped string (89+ chars), so never use it as `spec.hostname`. Use the first DNS label of `nodeId.getHostname()` instead (via `podHostnameFor()`), or Kubernetes rejects the pod with 422.
- **`K8sNode` is immutable**: all fields are `final` and `withNodeSelectors()` returns a new instance. Array-level and node-level selectors are merged in `K8sNodeArrayConfiguration.nodes()`, which builds fresh nodes rather than mutating the declared ones.
- **Pod labels must include hostname for service routing**: with `.withServicePort()`, the service selects pods on the label `hostname: <node.getHostname()>`. The launcher merges that label with the node's own `getLabels()` into one map before building the pod. Calling `.withLabels()` twice would overwrite instead of merge.
- **Service DNS propagation**: a new Service can take a moment to be routable. The launcher calls `waitForServiceEndpoints()` (30s timeout) after creating it, so other pods don't get "Connection refused" right away.
- **Downstream consumer**: `jetty-perf` (`common/.../PerfTestParams.java`, `common/.../assertions/Assertions.java`) builds `ClusterConfiguration`s against this API and breaks whenever it changes. `Assertions` reads `Node.getId()` *after* the cluster is up, to build report paths. That is why `Node` keeps an id and a hostname in the api module.
- **fabric8 mock server** (`kubernetes-server-mock`): still declared in the k8s pom but no test uses it. It only matches exact paths (`withPath()`), and upload/exec URLs embed the full args and classpath, so launch behaviour is tested against real k3s instead.
- **K8s integration tests**: `KubernetesClusterTest` starts a throwaway k3s cluster in Docker through Testcontainers, so `mvn test -pl jetty-cluster-orchestrator-k8s` just works, and skips when Docker is missing. Options:
  - `-Dkubernetes.config.path=<kubeconfig>` to use an existing cluster instead.
  - `-Dk8s.image=<image>` for the node image, which needs a JRE and `tar`. Maven passes the pom's `jettyproject/jetty-perf-node:ubuntu24-jdk21`; the test's own `eclipse-temurin:21-jre` default only applies when run outside Maven.
  - `-Dk3s.image=<image>` for the k3s image.
  - `-Djco.k3s.registry.mirror=<url>` (plus `-Djco.k3s.registry.insecure=true` for a self-signed proxy) to pull images through a mirror instead of Docker Hub.
