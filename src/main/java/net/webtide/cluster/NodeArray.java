package net.webtide.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.webtide.cluster.rpc.RpcClient;
import net.webtide.cluster.rpc.command.ExecuteNodeJobCommand;
import net.webtide.cluster.rpc.command.ShutdownCommand;
import net.webtide.cluster.util.IOUtil;
import org.apache.curator.framework.CuratorFramework;

public class NodeArray implements AutoCloseable
{
    private final Map<String, RpcClient> nodes = new HashMap<>();

    public NodeArray(Collection<String> nodeIds, CuratorFramework curator)
    {
        for (String nodeId : nodeIds)
        {
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
            IOUtil.close(client);
        }
        nodes.clear();
    }

    public NodeArrayFuture executeOnAll(NodeJob nodeJob) throws Exception
    {
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (RpcClient rpcClient : nodes.values())
        {
            CompletableFuture<Object> future = rpcClient.callAsync(new ExecuteNodeJobCommand(nodeJob));
            futures.add(future);
        }
        return new NodeArrayFuture(futures);
    }
}
