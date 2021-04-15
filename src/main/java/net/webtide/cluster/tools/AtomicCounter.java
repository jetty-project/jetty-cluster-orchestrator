package net.webtide.cluster.tools;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.atomic.AtomicValue;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong;
import org.apache.curator.framework.recipes.atomic.PromotedToLock;
import org.apache.curator.retry.RetryNTimes;

public class AtomicCounter
{
    private final DistributedAtomicLong distributedAtomicLong;
    private final String nodeId;
    private final String name;

    public AtomicCounter(CuratorFramework curator, String nodeId, String name)
    {
        this(curator, nodeId, "AtomicCounter", name);
    }

    AtomicCounter(CuratorFramework curator, String nodeId, String internalPath, String name)
    {
        this.nodeId = nodeId;
        this.name = name;
        String prefix = "/clients/" + clusterIdOf(nodeId) + "/" + internalPath;
        this.distributedAtomicLong = new DistributedAtomicLong(curator,
            prefix + "/" + name,
            new RetryNTimes(0, 0),
            PromotedToLock.builder().lockPath(prefix + "/Lock/" + name).build());
    }

    private static String clusterIdOf(String nodeId)
    {
        return nodeId.split("/")[0];
    }

    public boolean compareAndSet(long expectedValue, long newValue)
    {
        try
        {
            AtomicValue<Long> result = distributedAtomicLong.compareAndSet(expectedValue, newValue);
            return result.succeeded();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("node " + nodeId + " failed to compare and set counter " + name, e);
        }
    }

    public long incrementAndGet()
    {
        try
        {
            AtomicValue<Long> result = distributedAtomicLong.add(1L);
            if (result.succeeded())
                return result.postValue();
            throw new IllegalStateException("node " + nodeId + " failed to increment and get counter " + name);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("node " + nodeId + " failed to increment and get counter " + name, e);
        }
    }

    public long getAndIncrement()
    {
        try
        {
            AtomicValue<Long> result = distributedAtomicLong.add(1L);
            if (result.succeeded())
                return result.preValue();
            throw new IllegalStateException("node " + nodeId + " failed to get and increment counter " + name);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("node " + nodeId + " failed to get and increment counter " + name, e);
        }
    }

    public long get()
    {
        try
        {
            AtomicValue<Long> result = distributedAtomicLong.get();
            if (result.succeeded())
                return result.postValue();
            throw new IllegalStateException("node " + nodeId + " failed to get counter " + name);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("node " + nodeId + " failed to get counter " + name, e);
        }
    }

    public void set(long value)
    {
        try
        {
            distributedAtomicLong.forceSet(value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("node " + nodeId + " failed to set counter " + name, e);
        }
    }
}
