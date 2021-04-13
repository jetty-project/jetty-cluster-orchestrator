package net.webtide.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.webtide.cluster.common.command.EchoCommand;
import org.apache.curator.framework.CuratorFramework;

public class NodeArray implements AutoCloseable
{
    private final Map<String, RpcClient> nodes = new HashMap<>();

    public NodeArray(String topologyId, NodeArrayTopology topology, CuratorFramework curator) throws Exception
    {
        Collection<Node> nodes = topology.nodes();
        for (Node node : nodes)
        {
            String nodeId = topologyId + "#" + node.getId();
            this.nodes.put(nodeId, new RpcClient(curator, nodeId));
        }
    }

    @Override
    public void close()
    {
        nodes.clear();
    }

    public NodeArrayFuture executeOnAll(NodeJob nodeJob) throws Exception
    {
        for (RpcClient rpcClient : nodes.values())
        {
            Object response = rpcClient.call(new EchoCommand("hello"));
        }

        return new NodeArrayFuture();
    }
}
