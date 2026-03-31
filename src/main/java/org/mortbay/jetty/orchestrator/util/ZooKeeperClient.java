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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.atomic.AtomicValue;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong;
import org.apache.curator.framework.recipes.atomic.PromotedToLock;
import org.apache.curator.framework.recipes.barriers.DistributedDoubleBarrier;
import org.apache.curator.framework.recipes.queue.SimpleDistributedQueue;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.KeeperException;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.tools.AtomicCounter;
import org.mortbay.jetty.orchestrator.tools.Barrier;
import org.mortbay.jetty.orchestrator.tools.DistributedQueue;

public class ZooKeeperClient implements Closeable
{
    private final CuratorFramework curator;

    public ZooKeeperClient(String connectString) throws Exception
    {
        curator = CuratorFrameworkFactory.newClient(connectString, createRetryPolicy());
        curator.start();
        curator.blockUntilConnected();
    }

    @Override
    public void close() throws IOException
    {
        curator.close();
    }

    private static RetryPolicy createRetryPolicy()
    {
        return new RetryNTimes(150, 100);
    }

    public AtomicCounter createAtomicCounter(GlobalNodeId globalNodeId, String name, long initialValue)
    {
        return new AtomicCounterImpl(curator, globalNodeId, name, initialValue);
    }

    public Barrier createBarrier(GlobalNodeId globalNodeId, String name, int count)
    {
        return new BarrierImpl(curator, globalNodeId, name, count);
    }

    public DistributedQueue createDistributedQueue(GlobalNodeId globalNodeId, String name)
    {
        return new DistributedQueueImpl(curator, globalNodeId, name);
    }

    private static class AtomicCounterImpl implements AtomicCounter
    {
        private final DistributedAtomicLong distributedAtomicLong;
        private final GlobalNodeId globalNodeId;
        private final String name;

        AtomicCounterImpl(CuratorFramework curator, GlobalNodeId globalNodeId, String name, long initialValue)
        {
            this(curator, globalNodeId, "AtomicCounter", name, initialValue);
        }

        AtomicCounterImpl(CuratorFramework curator, GlobalNodeId globalNodeId, String internalPath, String name, long initialValue)
        {
            this.globalNodeId = globalNodeId;
            this.name = name;
            String prefix = "/" + globalNodeId.getClusterId() + "/" + internalPath;
            String counterName = prefix + "/Counter/" + name;
            String lockName = prefix + "/Lock/" + name;
            this.distributedAtomicLong = newDistributedAtomicLong(curator, counterName, lockName, initialValue);
        }

        private DistributedAtomicLong newDistributedAtomicLong(CuratorFramework curator, String counterPath, String lockName, long initialValue)
        {
            try
            {
                byte[] initialBytes = new byte[Long.BYTES];
                ByteBuffer.wrap(initialBytes).putLong(initialValue);
                curator.create().creatingParentsIfNeeded().forPath(counterPath, initialBytes);
            }
            catch (KeeperException.NodeExistsException e)
            {
                // node already exists, no need to set its initial value
            }
            catch (Exception e)
            {
                throw new IllegalStateException("Error accessing AtomicCounter " + counterPath);
            }

            return new DistributedAtomicLong(curator,
                counterPath,
                createRetryPolicy(),
                PromotedToLock.builder().lockPath(lockName).build());
        }

        @Override
        public long incrementAndGet()
        {
            try
            {
                while (true)
                {
                    AtomicValue<Long> result = distributedAtomicLong.add(1L);
                    if (result.succeeded())
                        return result.postValue();
                }
            }
            catch (Exception e)
            {
                throw new IllegalStateException("node " + globalNodeId.getNodeId() + " failed to increment and get counter " + name, e);
            }
        }

        @Override
        public long decrementAndGet()
        {
            try
            {
                while (true)
                {
                    AtomicValue<Long> result = distributedAtomicLong.add(-1L);
                    if (result.succeeded())
                        return result.postValue();
                }
            }
            catch (Exception e)
            {
                throw new IllegalStateException("node " + globalNodeId.getNodeId() + " failed to decrement and get counter " + name, e);
            }
        }

        @Override
        public long getAndIncrement()
        {
            try
            {
                while (true)
                {
                    AtomicValue<Long> result = distributedAtomicLong.add(1L);
                    if (result.succeeded())
                        return result.preValue();
                }
            }
            catch (Exception e)
            {
                throw new IllegalStateException("node " + globalNodeId.getNodeId() + " failed to get and increment counter " + name, e);
            }
        }

        @Override
        public long getAndDecrement()
        {
            try
            {
                while (true)
                {
                    AtomicValue<Long> result = distributedAtomicLong.add(-1L);
                    if (result.succeeded())
                        return result.preValue();
                }
            }
            catch (Exception e)
            {
                throw new IllegalStateException("node " + globalNodeId.getNodeId() + " failed to get and decrement counter " + name, e);
            }
        }

        @Override
        public long get()
        {
            try
            {
                while (true)
                {
                    AtomicValue<Long> result = distributedAtomicLong.get();
                    if (result.succeeded())
                        return result.postValue();
                }
            }
            catch (Exception e)
            {
                throw new IllegalStateException("node " + globalNodeId.getNodeId() + " failed to get counter " + name, e);
            }
        }

        @Override
        public void set(long value)
        {
            try
            {
                distributedAtomicLong.forceSet(value);
            }
            catch (Exception e)
            {
                throw new IllegalStateException("node " + globalNodeId.getNodeId() + " failed to set counter " + name, e);
            }
        }
    }

    private static class BarrierImpl implements Barrier
    {
        private final DistributedDoubleBarrier distributedDoubleBarrier;
        private final AtomicCounterImpl atomicCounter;
        private final int parties;
        private final AtomicBoolean guard = new AtomicBoolean();

        BarrierImpl(CuratorFramework curator, GlobalNodeId globalNodeId, String name, int parties)
        {
            this.parties = parties;
            distributedDoubleBarrier = newDistributedDoubleBarrier(curator, "/" + globalNodeId.getClusterId() + "/Barrier/" + name, parties);
            atomicCounter = new AtomicCounterImpl(curator, globalNodeId, "Barrier", name, parties);
        }

        private DistributedDoubleBarrier newDistributedDoubleBarrier(CuratorFramework curator, String barrierPath, int parties)
        {
            return new DistributedDoubleBarrier(curator, barrierPath, parties);
        }

        @Override
        public int await() throws Exception
        {
            if (!guard.compareAndSet(false, true))
                throw new BrokenBarrierException("Barrier is not cyclic");
            distributedDoubleBarrier.enter();
            try
            {
                int index = (int)atomicCounter.decrementAndGet();
                if (index == 0)
                    atomicCounter.set(parties);
                return index;
            }
            finally
            {
                distributedDoubleBarrier.leave();
            }
        }

        @Override
        public int await(long timeout, TimeUnit unit) throws Exception
        {
            if (!guard.compareAndSet(false, true))
                throw new BrokenBarrierException("Barrier is not cyclic");
            boolean success = distributedDoubleBarrier.enter(timeout, unit);
            if (!success)
                throw new TimeoutException("Timeout awaiting on barrier");
            try
            {
                int index = (int)atomicCounter.decrementAndGet();
                if (index == 0)
                    atomicCounter.set(parties);
                return index;
            }
            finally
            {
                distributedDoubleBarrier.leave(timeout, unit);
            }
        }
    }

    private static class DistributedQueueImpl implements DistributedQueue
    {
        private final SimpleDistributedQueue simpleDistributedQueue;

        DistributedQueueImpl(CuratorFramework curator, GlobalNodeId globalNodeId, String name)
        {
            String queuePath = "/" + globalNodeId.getNodeId() + "/Queue/" + name;
            simpleDistributedQueue = new SimpleDistributedQueue(curator, queuePath);
        }

        @Override
        public void offer(Object o) throws Exception
        {
            byte[] serialized = serialize(o);
            simpleDistributedQueue.offer(serialized);
        }

        @Override
        public Object take() throws Exception
        {
            byte[] serialized = simpleDistributedQueue.take();
            return deserialize(serialized);
        }

        private static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException
        {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
            return ois.readObject();
        }

        private static byte[] serialize(Object obj) throws IOException
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            return baos.toByteArray();
        }
    }
}
