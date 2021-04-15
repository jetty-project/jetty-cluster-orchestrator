package net.webtide.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.webtide.cluster.rpc.RpcClient;
import net.webtide.cluster.rpc.command.SpawnNodeCommand;
import net.webtide.cluster.configuration.Node;
import net.webtide.cluster.configuration.RemoteHostLauncher;
import net.webtide.cluster.util.CommandLineUtil;
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
    private final RemoteHostLauncher remoteHostLauncher;
    private final Map<String, NodeArray> nodeArrays = new HashMap<>();
    private final Map<String, RpcClient> hostClients = new HashMap<>();
    private TestingServer zkServer;
    private CuratorFramework curator;

    public Cluster(String id, ClusterConfiguration configuration) throws Exception
    {
        this.id = sanitize(id);
        this.configuration = configuration;
        this.remoteHostLauncher = configuration.remotingConfiguration().buildRemoteNodeLauncher();

        init();
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

        List<String> remoteHostIds = configuration.nodeArrays().stream()
            .flatMap(cfg -> cfg.topology().nodes().stream())
            .map(n -> id + "/" + sanitize(n.getHostname()))
            .distinct()
            .collect(Collectors.toList());
        for (String remoteHostId : remoteHostIds)
        {
            remoteHostLauncher.launch(remoteHostId, zkServer.getConnectString());
            hostClients.put(remoteHostId, new RpcClient(curator, remoteHostId));
        }

        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            Collection<String> remoteNodeIds = new ArrayList<>();
            for (Node node : nodeArrayConfiguration.topology().nodes())
            {
                String remoteHostId = id + "/" + sanitize(node.getHostname());
                String remoteNodeId = remoteHostId + "/" + sanitize(nodeArrayConfiguration.id()) + "/" + sanitize(node.getId());
                remoteNodeIds.add(remoteNodeId);

                RpcClient rpcClient = hostClients.get(remoteHostId);
                rpcClient.call(new SpawnNodeCommand(nodeArrayConfiguration.jvm(), remoteHostId, remoteNodeId, zkServer.getConnectString()));
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
        IOUtil.close(remoteHostLauncher);
        CommandLineUtil.defaultRootPath(id).delete();
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
