package net.webtide.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.webtide.cluster.configuration.LocalHostLauncher;
import net.webtide.cluster.rpc.RpcClient;
import net.webtide.cluster.rpc.command.SpawnNodeCommand;
import net.webtide.cluster.configuration.Node;
import net.webtide.cluster.configuration.HostLauncher;
import net.webtide.cluster.util.IOUtil;
import net.webtide.cluster.configuration.ClusterConfiguration;
import net.webtide.cluster.configuration.NodeArrayConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;

public class Cluster implements AutoCloseable
{
    private final String id;
    private final ClusterConfiguration configuration;
    private final HostLauncher localHostLauncher = new LocalHostLauncher();
    private final HostLauncher hostLauncher;
    private final Map<String, NodeArray> nodeArrays = new HashMap<>();
    private final Map<String, RpcClient> hostClients = new HashMap<>();
    private TestingServer zkServer;
    private CuratorFramework curator;

    public Cluster(String id, ClusterConfiguration configuration) throws Exception
    {
        this.id = sanitize(id);
        this.configuration = configuration;
        this.hostLauncher = configuration.hostLauncher();

        try
        {
            init();
        }
        catch (Exception e)
        {
            close();
            throw e;
        }
    }

    private static String sanitize(String id)
    {
        return id.replace(':', '_')
            .replace('/', '_');
    }

    private void init() throws Exception
    {
        zkServer = new TestingServer(true);
        curator = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        List<String> hostnames = configuration.nodeArrays().stream()
            .flatMap(cfg -> cfg.topology().nodes().stream())
            .map(Node::getHostname)
            .distinct()
            .collect(Collectors.toList());
        for (String hostname : hostnames)
        {
            String hostId = id + "/" + sanitize(hostname);
            hostLauncher.launch(hostname, hostId, zkServer.getConnectString());
            hostClients.put(hostId, new RpcClient(curator, hostId));
        }

        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            Collection<String> remoteNodeIds = new ArrayList<>();
            for (Node node : nodeArrayConfiguration.topology().nodes())
            {
                String hostId = id + "/" + sanitize(node.getHostname());
                String remoteNodeId = hostId + "/" + sanitize(nodeArrayConfiguration.id()) + "/" + sanitize(node.getId());
                remoteNodeIds.add(remoteNodeId);

                RpcClient rpcClient = hostClients.get(hostId);
                rpcClient.call(new SpawnNodeCommand(nodeArrayConfiguration.jvm(), hostId, remoteNodeId, zkServer.getConnectString()));
            }
            nodeArrays.put(nodeArrayConfiguration.id(), new NodeArray(remoteNodeIds, curator));
        }
    }

    @Override
    public void close()
    {
        for (NodeArray nodeArray : nodeArrays.values())
        {
            nodeArray.close();
        }
        hostClients.clear();
        nodeArrays.clear();
        IOUtil.close(hostLauncher);
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
