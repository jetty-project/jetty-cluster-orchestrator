package net.webtide.cluster;

import net.webtide.cluster.tools.AtomicCounter;
import net.webtide.cluster.tools.Barrier;
import org.apache.curator.framework.CuratorFramework;

public class ClusterTools
{
    private final CuratorFramework curator;
    private final String nodeId;

    public ClusterTools(CuratorFramework curator, String nodeId)
    {
        this.curator = curator;
        this.nodeId = nodeId;
    }

    public Barrier barrier(String name, int count)
    {
        return new Barrier(curator, nodeId, name, count);
    }

    public AtomicCounter atomicCounter(String name)
    {
        return new AtomicCounter(curator, nodeId, name);
    }
}
