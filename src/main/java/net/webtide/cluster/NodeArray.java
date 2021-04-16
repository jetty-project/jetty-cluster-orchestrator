package net.webtide.cluster;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.webtide.cluster.configuration.LocalHostLauncher;
import net.webtide.cluster.rpc.RpcClient;
import net.webtide.cluster.rpc.command.ExecuteNodeJobCommand;
import net.webtide.cluster.rpc.command.ShutdownCommand;
import net.webtide.cluster.util.IOUtil;

public class NodeArray implements AutoCloseable
{
    private final Map<String, Node> nodes;

    NodeArray(Map<String, Node> nodes)
    {
        this.nodes = nodes;
    }

    public Path rootPathOf(String id)
    {
        Node node = nodes.get(id);
        if (node == null)
            throw new IllegalArgumentException("No such node with ID " + id);
        if (node.local)
        {
            return LocalHostLauncher.rootPathOf(node.nodeId).toPath();
        }
        else
        {
            URI uri = URI.create("wtc:" + node.nodeId + "!/");
            return Paths.get(uri);
        }
    }

    public Set<String> ids()
    {
        return nodes.keySet();
    }

    @Override
    public void close()
    {
        for (Node node : nodes.values())
        {
            try
            {
                node.rpcClient.call(new ShutdownCommand());
            }
            catch (Exception e)
            {
                // ignore
            }
            IOUtil.close(node.rpcClient);
        }
        nodes.clear();
    }

    public NodeArrayFuture executeOnAll(NodeJob nodeJob) throws Exception
    {
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (Node node : nodes.values())
        {
            CompletableFuture<Object> future = node.rpcClient.callAsync(new ExecuteNodeJobCommand(nodeJob));
            futures.add(future);
        }
        return new NodeArrayFuture(futures);
    }

    static class Node
    {
        private final String nodeId;
        private final RpcClient rpcClient;
        private final boolean local;

        Node(String nodeId, RpcClient rpcClient, boolean local)
        {
            this.nodeId = nodeId;
            this.rpcClient = rpcClient;
            this.local = local;
        }
    }
}
