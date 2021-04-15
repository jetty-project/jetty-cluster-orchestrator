package net.webtide.cluster.tools;

import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.barriers.DistributedDoubleBarrier;

public class Barrier
{
    private final DistributedDoubleBarrier distributedDoubleBarrier;
    private final AtomicCounter atomicCounter;
    private final int parties;

    public Barrier(CuratorFramework curator, String nodeId, String name, int parties)
    {
        this.parties = parties;
        distributedDoubleBarrier = new DistributedDoubleBarrier(curator, "/clients/" + clusterIdOf(nodeId) + "/Barrier/" + name, parties);
        atomicCounter = new AtomicCounter(curator, nodeId, "Barrier/Counter", name);
    }

    private static String clusterIdOf(String nodeId)
    {
        return nodeId.split("/")[0];
    }

    private int calculateArrivalIndex()
    {
        int arrivalIndex = (int)atomicCounter.getAndIncrement();
        if (arrivalIndex == parties - 1)
            atomicCounter.set(0L);
        return arrivalIndex;
    }

    public int await() throws Exception
    {
        distributedDoubleBarrier.enter();
        return calculateArrivalIndex();
    }

    public int await(long timeout, TimeUnit unit) throws Exception
    {
        distributedDoubleBarrier.enter(timeout, unit);
        return calculateArrivalIndex();
    }
}
