package sample;

import net.webtide.cluster.Cluster;
import net.webtide.cluster.Node;
import net.webtide.cluster.NodeArrayFuture;
import net.webtide.cluster.NodeArrayTopology;
import net.webtide.cluster.configuration.ClusterConfiguration;
import net.webtide.cluster.configuration.SimpleClusterConfiguration;
import net.webtide.cluster.common.Jvm;
import net.webtide.cluster.JvmSettings;
import net.webtide.cluster.NodeArray;
import net.webtide.cluster.configuration.SimpleNodeArrayConfiguration;
import org.junit.jupiter.api.Test;

public class ClusterTest
{
    @Test
    public void test() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvmSettings(new JvmSettings(() -> new Jvm("/work/tools/jdk/1.8", "8")))
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").topology(new NodeArrayTopology(new Node("1", "localhost"))).jvmSettings(new JvmSettings(() -> new Jvm("/work/tools/jdk/1.11", "11"))))
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").topology(new NodeArrayTopology(new Node("1", "localhost"))).jvmSettings(new JvmSettings(() -> new Jvm("/work/tools/jdk/1.11", "11"))))
            ;

        try (Cluster cluster = new Cluster("ClusterTest::test", cfg))
        {
            NodeArray serverArray = cluster.nodeArray("server-array");
            NodeArray clientArray = cluster.nodeArray("client-array");

            NodeArrayFuture sf = serverArray.executeOnAll(env ->
            {
                System.out.println("servers: hello, world!");
            });
            sf.get();
            NodeArrayFuture cf = clientArray.executeOnAll(env ->
            {
                System.out.println("clients: hello, world!");
            });
            cf.get();
        }
    }
}
