//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.mortbay.jetty.orchestrator.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.tools.AtomicCounter;
import org.mortbay.jetty.orchestrator.tools.Barrier;
import org.mortbay.jetty.orchestrator.tools.DistributedQueue;

/**
 * A thin client wrapping a raw {@link ZooKeeper} connection with the small set of coordination
 * recipes (atomic counter, double barrier, distributed queue) needed by this project. This
 * intentionally re-implements the relevant parts of the equivalent Apache Curator recipes
 * directly on top of the ZooKeeper client, without pulling in the Curator dependency.
 */
public class ZooKeeperClient implements Closeable {
    public static final String RETRY_BASE_SLEEP_TIME_MS_PROPERTY = "jco.zookeeper.retry.baseSleepTimeMs";
    public static final String RETRY_MAX_RETRIES_PROPERTY = "jco.zookeeper.retry.maxRetries";
    public static final String SESSION_TIMEOUT_MS_PROPERTY = "jco.zookeeper.sessionTimeoutMs";
    public static final String CONNECTION_TIMEOUT_MS_PROPERTY = "jco.zookeeper.connectionTimeoutMs";

    private static final int DEFAULT_BASE_SLEEP_TIME_MS = 1000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_SESSION_TIMEOUT_MS = 30000;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 15000;

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final List<ACL> OPEN_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;

    private final ZooKeeper zk;
    private final int baseSleepTimeMs;
    private final int maxRetries;

    public ZooKeeperClient(String connectString) throws Exception {
        int sessionTimeoutMs = Integer.getInteger(SESSION_TIMEOUT_MS_PROPERTY, DEFAULT_SESSION_TIMEOUT_MS);
        int connectionTimeoutMs = Integer.getInteger(CONNECTION_TIMEOUT_MS_PROPERTY, DEFAULT_CONNECTION_TIMEOUT_MS);
        this.baseSleepTimeMs = Integer.getInteger(RETRY_BASE_SLEEP_TIME_MS_PROPERTY, DEFAULT_BASE_SLEEP_TIME_MS);
        this.maxRetries = Integer.getInteger(RETRY_MAX_RETRIES_PROPERTY, DEFAULT_MAX_RETRIES);

        CountDownLatch connectedLatch = new CountDownLatch(1);
        zk = new ZooKeeper(connectString, sessionTimeoutMs, event -> {
            if (event.getState() == Watcher.Event.KeeperState.SyncConnected) connectedLatch.countDown();
        });

        if (!connectedLatch.await(connectionTimeoutMs, TimeUnit.MILLISECONDS)) {
            IOUtil.close(this);
            throw new TimeoutException("Timed out connecting to ZooKeeper at " + connectString);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            zk.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    public AtomicCounter createAtomicCounter(GlobalNodeId globalNodeId, String name, long initialValue) {
        return new AtomicCounterImpl(globalNodeId, name, initialValue);
    }

    public Barrier createBarrier(GlobalNodeId globalNodeId, String name, int count) {
        return new BarrierImpl(globalNodeId, name, count);
    }

    public <T> DistributedQueue<T> createDistributedQueue(GlobalNodeId globalNodeId, String name) {
        return new DistributedQueueImpl<>(globalNodeId, name);
    }

    /*
     * Low-level ZooKeeper helpers shared by the recipes below. Every call is wrapped with a
     * bounded exponential-backoff retry for the transient, connection-related exceptions that
     * a CuratorFramework retry policy used to absorb automatically.
     */

    private <T> T retry(Callable<T> operation) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return operation.call();
            } catch (KeeperException.ConnectionLossException
                    | KeeperException.SessionExpiredException
                    | KeeperException.OperationTimeoutException e) {
                if (attempt >= maxRetries) throw e;
                Thread.sleep(baseSleepTimeMs * (1L << attempt));
                attempt++;
            }
        }
    }

    private void ensurePath(String path) throws Exception {
        if (path.isEmpty()) return;
        int idx = path.lastIndexOf('/');
        String parent = path.substring(0, idx);
        if (!parent.isEmpty()) ensurePath(parent);
        try {
            retry(() -> zk.create(path, EMPTY_BYTES, OPEN_ACL, CreateMode.PERSISTENT));
        } catch (KeeperException.NodeExistsException ignore) {
            // already exists
        }
    }

    private String create(String path, byte[] data, CreateMode mode) throws Exception {
        String parent = path.substring(0, path.lastIndexOf('/'));
        if (!parent.isEmpty()) ensurePath(parent);
        return retry(() -> zk.create(path, data, OPEN_ACL, mode));
    }

    private byte[] getData(String path, Watcher watcher, Stat stat) throws Exception {
        return retry(() -> zk.getData(path, watcher, stat));
    }

    private void setData(String path, byte[] data, int version) throws Exception {
        retry(() -> zk.setData(path, data, version));
    }

    private List<String> getChildren(String path, Watcher watcher) throws Exception {
        return retry(() -> zk.getChildren(path, watcher));
    }

    private void delete(String path, int version) throws Exception {
        retry(() -> {
            zk.delete(path, version);
            return null;
        });
    }

    private Stat exists(String path, Watcher watcher) throws Exception {
        return retry(() -> zk.exists(path, watcher));
    }

    /**
     * Equivalent of Curator's {@code DistributedAtomicLong}, simplified to a pure optimistic
     * compare-and-swap loop: since the caller already retries indefinitely on a failed CAS,
     * there is no need for the lock-promotion fallback that the Curator recipe offers.
     */
    private class AtomicCounterImpl implements AtomicCounter {
        private final String counterPath;
        private final GlobalNodeId globalNodeId;
        private final String name;

        AtomicCounterImpl(GlobalNodeId globalNodeId, String name, long initialValue) {
            this(globalNodeId, "AtomicCounter", name, initialValue);
        }

        AtomicCounterImpl(GlobalNodeId globalNodeId, String internalPath, String name, long initialValue) {
            this.globalNodeId = globalNodeId;
            this.name = name;
            String prefix = "/" + globalNodeId.getClusterId() + "/" + internalPath;
            this.counterPath = prefix + "/Counter/" + name;
            initializeCounter(initialValue);
        }

        private void initializeCounter(long initialValue) {
            try {
                byte[] initialBytes = new byte[Long.BYTES];
                ByteBuffer.wrap(initialBytes).putLong(initialValue);
                create(counterPath, initialBytes, CreateMode.PERSISTENT);
            } catch (KeeperException.NodeExistsException e) {
                // node already exists, no need to set its initial value
            } catch (Exception e) {
                throw new IllegalStateException("Error accessing AtomicCounter " + counterPath, e);
            }
        }

        private long readValue(Stat stat) throws Exception {
            byte[] data = getData(counterPath, null, stat);
            return ByteBuffer.wrap(data).getLong();
        }

        private boolean tryUpdate(long newValue, int version) throws Exception {
            byte[] data = new byte[Long.BYTES];
            ByteBuffer.wrap(data).putLong(newValue);
            try {
                setData(counterPath, data, version);
                return true;
            } catch (KeeperException.BadVersionException | KeeperException.NoNodeException e) {
                return false;
            }
        }

        @Override
        public long incrementAndGet() {
            try {
                while (true) {
                    Stat stat = new Stat();
                    long updated = readValue(stat) + 1L;
                    if (tryUpdate(updated, stat.getVersion())) return updated;
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "node " + globalNodeId.getNodeId() + " failed to increment and get counter " + name, e);
            }
        }

        @Override
        public long decrementAndGet() {
            try {
                while (true) {
                    Stat stat = new Stat();
                    long updated = readValue(stat) - 1L;
                    if (tryUpdate(updated, stat.getVersion())) return updated;
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "node " + globalNodeId.getNodeId() + " failed to decrement and get counter " + name, e);
            }
        }

        @Override
        public long getAndIncrement() {
            try {
                while (true) {
                    Stat stat = new Stat();
                    long current = readValue(stat);
                    if (tryUpdate(current + 1L, stat.getVersion())) return current;
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "node " + globalNodeId.getNodeId() + " failed to get and increment counter " + name, e);
            }
        }

        @Override
        public long getAndDecrement() {
            try {
                while (true) {
                    Stat stat = new Stat();
                    long current = readValue(stat);
                    if (tryUpdate(current - 1L, stat.getVersion())) return current;
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "node " + globalNodeId.getNodeId() + " failed to get and decrement counter " + name, e);
            }
        }

        @Override
        public long get() {
            try {
                return readValue(null);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "node " + globalNodeId.getNodeId() + " failed to get counter " + name, e);
            }
        }

        @Override
        public void set(long value) {
            try {
                byte[] data = new byte[Long.BYTES];
                ByteBuffer.wrap(data).putLong(value);
                setData(counterPath, data, -1);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "node " + globalNodeId.getNodeId() + " failed to set counter " + name, e);
            }
        }
    }

    /**
     * Port of Curator's {@code DistributedDoubleBarrier} recipe onto the raw ZooKeeper API,
     * combined with an {@link AtomicCounterImpl} to compute the arrival index the way the
     * original implementation did. The entry phase blocks on a watch of a single "ready" node
     * that is created once enough parties have arrived; the exit phase drains parties one at a
     * time by watching the lowest/highest sibling node, exactly like the ZK double-barrier
     * recipe this is based on. Watching specific node paths - rather than "any children changed"
     * - is what lets the same barrier path be safely reused round after round without a party
     * that is still leaving one round being woken up by a party that already entered the next.
     */
    private class BarrierImpl implements Barrier {
        private static final String READY_NODE = "ready";

        private final String barrierPath;
        private final String ourPath;
        private final String readyPath;
        private final int memberQty;
        private final AtomicCounterImpl atomicCounter;
        private final AtomicBoolean guard = new AtomicBoolean();
        private final AtomicBoolean hasBeenNotified = new AtomicBoolean();
        private final Watcher watcher = event -> {
            synchronized (this) {
                hasBeenNotified.set(true);
                notifyAll();
            }
        };

        BarrierImpl(GlobalNodeId globalNodeId, String name, int parties) {
            this.memberQty = parties;
            this.barrierPath = "/" + globalNodeId.getClusterId() + "/Barrier/" + name;
            this.ourPath = barrierPath + "/" + UUID.randomUUID();
            this.readyPath = barrierPath + "/" + READY_NODE;
            this.atomicCounter = new AtomicCounterImpl(globalNodeId, "Barrier", name, parties);
        }

        @Override
        public int await() throws Exception {
            if (!guard.compareAndSet(false, true)) throw new BrokenBarrierException("Barrier is not cyclic");
            enter(-1, null);
            try {
                return computeIndex();
            } finally {
                leave(-1, null);
            }
        }

        @Override
        public int await(long timeout, TimeUnit unit) throws Exception {
            if (!guard.compareAndSet(false, true)) throw new BrokenBarrierException("Barrier is not cyclic");
            boolean success = enter(timeout, unit);
            if (!success) throw new TimeoutException("Timeout awaiting on barrier");
            try {
                return computeIndex();
            } finally {
                leave(timeout, unit);
            }
        }

        private int computeIndex() {
            int index = (int) atomicCounter.decrementAndGet();
            if (index == 0) atomicCounter.set(memberQty);
            return index;
        }

        private boolean enter(long maxWait, TimeUnit unit) throws Exception {
            long startNanos = System.nanoTime();
            boolean hasMaxWait = (unit != null);
            long maxWaitNanos = hasMaxWait ? unit.toNanos(maxWait) : Long.MAX_VALUE;

            boolean readyPathExists = (exists(readyPath, watcher) != null);
            create(ourPath, EMPTY_BYTES, CreateMode.EPHEMERAL);

            return readyPathExists || internalEnter(startNanos, hasMaxWait, maxWaitNanos);
        }

        private synchronized boolean internalEnter(long startNanos, boolean hasMaxWait, long maxWaitNanos)
                throws Exception {
            boolean result = true;
            List<String> children = getChildren(barrierPath, null);
            if (children.size() >= memberQty) {
                try {
                    create(readyPath, EMPTY_BYTES, CreateMode.PERSISTENT);
                } catch (KeeperException.NodeExistsException ignore) {
                    // ignore
                }
            } else if (hasMaxWait && !hasBeenNotified.get()) {
                long elapsedNanos = System.nanoTime() - startNanos;
                long thisWaitMs = TimeUnit.NANOSECONDS.toMillis(maxWaitNanos - elapsedNanos);
                if (thisWaitMs <= 0) {
                    result = false;
                } else {
                    wait(thisWaitMs);
                    if (!hasBeenNotified.get()) result = false;
                }
            } else {
                wait();
            }
            return result;
        }

        private synchronized boolean leave(long maxWait, TimeUnit unit) throws Exception {
            long startNanos = System.nanoTime();
            boolean hasMaxWait = (unit != null);
            long maxWaitNanos = hasMaxWait ? unit.toNanos(maxWait) : Long.MAX_VALUE;

            String ourPathName = ourPath.substring(ourPath.lastIndexOf('/') + 1);
            boolean ourNodeShouldExist = true;
            boolean result = true;
            while (true) {
                List<String> children;
                try {
                    children = getChildren(barrierPath, null);
                } catch (KeeperException.NoNodeException dummy) {
                    children = new ArrayList<>();
                }
                children = filterAndSortChildren(children);
                if (children.isEmpty()) break;

                int ourIndex = children.indexOf(ourPathName);
                if (ourIndex < 0 && ourNodeShouldExist)
                    throw new IllegalStateException("Our path (" + ourPathName + ") is missing");

                if (children.size() == 1) {
                    if (ourNodeShouldExist && !children.get(0).equals(ourPathName))
                        throw new IllegalStateException(
                                "Last path (" + children.get(0) + ") is not ours (" + ourPathName + ")");
                    checkDeleteOurPath(ourNodeShouldExist);
                    break;
                }

                Stat stat;
                boolean isLowestNode = (ourIndex == 0);
                if (isLowestNode) {
                    String highestNodePath = barrierPath + "/" + children.get(children.size() - 1);
                    stat = exists(highestNodePath, watcher);
                } else {
                    String lowestNodePath = barrierPath + "/" + children.get(0);
                    stat = exists(lowestNodePath, watcher);

                    checkDeleteOurPath(ourNodeShouldExist);
                    ourNodeShouldExist = false;
                }

                if (stat != null) {
                    if (hasMaxWait) {
                        long elapsedNanos = System.nanoTime() - startNanos;
                        long thisWaitMs = TimeUnit.NANOSECONDS.toMillis(maxWaitNanos - elapsedNanos);
                        if (thisWaitMs <= 0) {
                            result = false;
                            break;
                        }
                        wait(thisWaitMs);
                    } else {
                        wait();
                    }
                }
            }

            try {
                delete(readyPath, -1);
            } catch (KeeperException.NoNodeException ignore) {
                // ignore
            }

            return result;
        }

        private void checkDeleteOurPath(boolean shouldExist) throws Exception {
            if (shouldExist) delete(ourPath, -1);
        }

        private List<String> filterAndSortChildren(List<String> children) {
            List<String> filtered = new ArrayList<>(children.size());
            for (String name : children) {
                if (!name.equals(READY_NODE)) filtered.add(name);
            }
            Collections.sort(filtered);
            return filtered;
        }
    }

    /**
     * Port of Curator's {@code SimpleDistributedQueue} recipe, restricted to the {@code offer}
     * and {@code take} operations actually used by the RPC layer.
     */
    private class DistributedQueueImpl<T> implements DistributedQueue<T> {
        private static final String PREFIX = "qn-";

        private final String queuePath;

        DistributedQueueImpl(GlobalNodeId globalNodeId, String name) {
            this.queuePath = "/" + globalNodeId.getNodeId() + "/Queue/" + name;
        }

        @Override
        public void offer(T o) throws Exception {
            byte[] serialized = serialize(o);
            create(queuePath + "/" + PREFIX, serialized, CreateMode.PERSISTENT_SEQUENTIAL);
        }

        @Override
        public T take() throws Exception {
            ensurePath(queuePath);
            while (true) {
                CountDownLatch changed = new CountDownLatch(1);
                Watcher watcher = event -> changed.countDown();

                List<String> nodes;
                try {
                    nodes = getChildren(queuePath, watcher);
                } catch (KeeperException.NoNodeException dummy) {
                    ensurePath(queuePath);
                    continue;
                }
                List<String> sorted = new ArrayList<>(nodes);
                Collections.sort(sorted);

                for (String node : sorted) {
                    if (!node.startsWith(PREFIX)) continue;

                    String nodePath = queuePath + "/" + node;
                    try {
                        byte[] data = getData(nodePath, null, null);
                        delete(nodePath, -1);
                        return deserialize(data);
                    } catch (KeeperException.NoNodeException ignore) {
                        // another consumer took it first, try the next one
                    }
                }

                changed.await();
            }
        }

        private static <T> T deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
            @SuppressWarnings("unchecked")
            T t = (T) ois.readObject();
            return t;
        }

        private static byte[] serialize(Object obj) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            return baos.toByteArray();
        }
    }
}
