package net.webtide.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.webtide.cluster.rpc.RpcClient;
import net.webtide.cluster.rpc.command.ExecuteNodeJobCommand;
import net.webtide.cluster.rpc.command.ShutdownCommand;
import org.apache.curator.framework.CuratorFramework;

public class NodeArray implements AutoCloseable
{
    private final Map<String, RpcClient> nodes = new HashMap<>();

    public NodeArray(Collection<String> remoteNodeIds, CuratorFramework curator)
    {
        for (String remoteNodeId : remoteNodeIds)
        {
            this.nodes.put(remoteNodeId, new RpcClient(curator, remoteNodeId));
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
