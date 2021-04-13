package net.webtide.cluster;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.webtide.cluster.common.util.IOUtil;
import net.webtide.cluster.configuration.ClusterConfiguration;
import net.webtide.cluster.configuration.NodeArrayConfiguration;
import net.webtide.cluster.configuration.RemotingConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;

public class Cluster implements AutoCloseable
{
    private final String id;
    private final ClusterConfiguration configuration;
    private final Map<String, NodeArray> nodeArrays = new HashMap<>();
    private TestingServer zkServer;
    private CuratorFramework curator;

    public Cluster(String id, ClusterConfiguration configuration) throws Exception
    {
        this.id = id;
        this.configuration = configuration;

        init();
    }

    private void init() throws Exception
    {
        zkServer = new TestingServer(true);
        curator = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), new ExponentialBackoffRetry(1000, 3));
        curator.start();
        curator.blockUntilConnected();

        RemotingConfiguration remotingConfiguration = configuration.remotingConfiguration();
        RemoteNodeLauncher remoteNodeLauncher = remotingConfiguration.remoteNodeLauncher();
        for (NodeArrayConfiguration nodeArrayConfiguration : configuration.nodeArrays())
        {
            JvmSettings jvmSettings = nodeArrayConfiguration.jvmSettings();
            if (jvmSettings == null)
                jvmSettings = configuration.jvmSettings();
            NodeArrayTopology topology = nodeArrayConfiguration.topology();
            Collection<Node> nodes = topology.nodes();
            for (Node node : nodes)
            {
                remoteNodeLauncher.launch(node, zkServer.getConnectString());
            }

            nodeArrays.put(nodeArrayConfiguration.id(), new NodeArray());
        }
    }

    @Override
    public void close()
    {
        IOUtil.close(curator);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

}
