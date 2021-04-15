package net.webtide.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.webtide.cluster.configuration.ClusterConfiguration;
import net.webtide.cluster.configuration.HostLauncher;
import net.webtide.cluster.configuration.LocalHostLauncher;
import net.webtide.cluster.configuration.Node;
import net.webtide.cluster.configuration.NodeArrayConfiguration;
import net.webtide.cluster.rpc.RpcClient;
import net.webtide.cluster.rpc.command.SpawnNodeCommand;
import net.webtide.cluster.util.IOUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;

public class Cluster implements AutoCloseable
{
    private final String id;
    private final ClusterConfiguration configuration;
    private final LocalHostLauncher localHostLauncher = new LocalHostLauncher();
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
        if (localHostLauncher.jvm() == null)
            localHostLauncher.jvm(configuration.jvm());

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
        curator = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new RetryNTimes(0, 0));
        curator.start();
        curator.blockUntilConnected();

        List<String> hostnames = configuration.nodeArrays().stream()
            .flatMap(cfg -> cfg.topology().nodes().stream())
            .map(Node::getHostname)
            .distinct()
            .collect(Collectors.toList());
        for (String hostname : hostnames)
        {
            String hostId = hostIdFor(hostname);
            HostLauncher launcher = hostname.equals(LocalHostLauncher.HOSTNAME) ? localHostLauncher : hostLauncher;
            if (launcher == null)
                throw new IllegalStateException("No configured host launcher to start node on " + hostname);
            launcher.launch(hostname, hostId, zkServer.getConnectString());
            hostClients.put(hostId, new RpcClient(curator, hostId));
        }

        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            Collection<String> nodeIds = new ArrayList<>();
            for (Node node : nodeArrayConfiguration.topology().nodes())
            {
                String hostId = hostIdFor(node.getHostname());
                String nodeId = hostId + "/" + sanitize(nodeArrayConfiguration.id()) + "/" + sanitize(node.getId());
                nodeIds.add(nodeId);

                RpcClient rpcClient = hostClients.get(hostId);
                rpcClient.call(new SpawnNodeCommand(nodeArrayConfiguration.jvm(), hostId, nodeId, zkServer.getConnectString()));
            }
            nodeArrays.put(nodeArrayConfiguration.id(), new NodeArray(nodeIds, curator));
        }
    }

    private String hostIdFor(String hostname)
    {
        return id + "/" + sanitize(hostname);
    }

    public ClusterTools tools()
    {
        return new ClusterTools(curator, hostIdFor(LocalHostLauncher.HOSTNAME));
    }

    @Override
    public void close()
    {
        nodeArrays.values().forEach(IOUtil::close);
        nodeArrays.clear();
        hostClients.values().forEach(IOUtil::close);
        hostClients.clear();
        IOUtil.close(hostLauncher);
        IOUtil.close(localHostLauncher);
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
