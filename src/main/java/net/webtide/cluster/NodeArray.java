package net.webtide.cluster;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    private final Map<String, String> nodeIds;

    NodeArray(Map<String, String> nodeIds, CuratorFramework curator)
    {
        this.nodeIds = nodeIds;
        for (String nodeId : nodeIds.values())
        {
            this.nodes.put(nodeId, new RpcClient(curator, nodeId));
        }
    }

    public Path rootPathOf(String nodeId)
    {
        String id = nodeIds.get(nodeId);
        if (id == null)
            throw new IllegalArgumentException("No such node with ID " + nodeId);
        URI uri = URI.create("wtc:" + id + "!/");
        return Paths.get(uri);
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
