package net.webtide.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.webtide.cluster.common.NodeJob;
import net.webtide.cluster.common.command.ExecuteNodeJobCommand;
import net.webtide.cluster.common.command.ShutdownCommand;
import org.apache.curator.framework.CuratorFramework;

public class NodeArray implements AutoCloseable
{
    private final Map<String, RpcClient> nodes = new HashMap<>();

    public NodeArray(String topologyId, NodeArrayTopology topology, CuratorFramework curator)
    {
        Collection<Node> nodes = topology.nodes();
        for (Node node : nodes)
        {
            String nodeId = node.getHostname() + "/" + topologyId + "/" + node.getId();
            this.nodes.put(nodeId, new RpcClient(curator, nodeId));
        }
    }

    @Override
    public void close()
    {
        for (RpcClient client : nodes.values())
        {
            try
            {
                client.call(new ShutdownCommand());
            }
            catch (Exception e)
            {
                // ignore
            }
        }
        nodes.clear();
    }

    public NodeArrayFuture executeOnAll(NodeJob nodeJob) throws Exception
    {
        for (RpcClient rpcClient : nodes.values())
        {
            rpcClient.call(new ExecuteNodeJobCommand(nodeJob));
        }

        return new NodeArrayFuture();
    }
}
