package net.webtide.cluster.tools;

import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.barriers.DistributedDoubleBarrier;

public class Barrier
{

    private final DistributedDoubleBarrier distributedDoubleBarrier;
    private final AtomicCounter atomicCounter;

    public Barrier(CuratorFramework curator, String nodeId, String name, int parties)
    {
        distributedDoubleBarrier = new DistributedDoubleBarrier(curator, "/clients/" + clusterIdOf(nodeId) + "/Barrier/" + name, parties);
        atomicCounter = new AtomicCounter(curator, nodeId, "Barrier/Counter", name);
    }

    private static String clusterIdOf(String nodeId)
    {
        return nodeId.split("/")[0];
    }

    public int await() throws Exception
    {
        distributedDoubleBarrier.enter();
        return (int)atomicCounter.getAndIncrement();
    }

    public int await(long timeout, TimeUnit unit) throws Exception
    {
        distributedDoubleBarrier.enter(timeout, unit);
        return (int)atomicCounter.getAndIncrement();
    }
}
