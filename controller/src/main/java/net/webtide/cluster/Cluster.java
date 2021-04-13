package net.webtide.cluster;

import java.util.HashMap;
import java.util.Map;

import net.webtide.cluster.common.util.IOUtil;
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
    private final RemoteNodeLauncher remoteNodeLauncher;
    private final Map<String, NodeArray> nodeArrays = new HashMap<>();
    private TestingServer zkServer;
    private CuratorFramework curator;

    public Cluster(String id, ClusterConfiguration configuration) throws Exception
    {
        this.id = id;
        this.configuration = configuration;
        this.remoteNodeLauncher = configuration.remotingConfiguration().buildRemoteNodeLauncher();

        init();
    }

    private void init() throws Exception
    {
        zkServer = new TestingServer(true);
        curator = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            NodeArrayTopology topology = nodeArrayConfiguration.topology();
            for (Node node : topology.nodes())
            {
                String id = nodeArrayConfiguration.id() + "#" + node.getId();
                remoteNodeLauncher.launch(id, zkServer.getConnectString());
            }
            nodeArrays.put(nodeArrayConfiguration.id(), new NodeArray());
        }
    }

    @Override
    public void close()
    {
        IOUtil.close(remoteNodeLauncher);
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
