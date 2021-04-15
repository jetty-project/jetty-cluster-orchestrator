package sample;

import net.webtide.cluster.Cluster;
import net.webtide.cluster.NodeArray;
import net.webtide.cluster.NodeArrayFuture;
import net.webtide.cluster.configuration.ClusterConfiguration;
import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.configuration.Node;
import net.webtide.cluster.configuration.NodeArrayTopology;
import net.webtide.cluster.configuration.SimpleClusterConfiguration;
import net.webtide.cluster.configuration.SimpleNodeArrayConfiguration;
import org.junit.jupiter.api.Test;

public class ClusterTest
{
    @Test
    public void test() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm(() -> "/work/tools/jdk/1.8/bin/java"))
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").topology(new NodeArrayTopology(new Node("1", "localhost")))
                .jvm(new Jvm(() -> "/work/tools/jdk/1.11/bin/java"))
            )
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").topology(new NodeArrayTopology(new Node("1", "localhost")))
                .jvm(new Jvm(() -> "/work/tools/jdk/1.15/bin/java"))
            )
            ;

        try (Cluster cluster = new Cluster("ClusterTest::test", cfg))
        {
            NodeArray serverArray = cluster.nodeArray("server-array");
            NodeArray clientArray = cluster.nodeArray("client-array");

            NodeArrayFuture sf = serverArray.executeOnAll(tools ->
            {
                long counter = tools.atomicCounter("counter").incrementAndGet();
                String javaVersion = System.getProperty("java.version");
                int pos = tools.barrier("barrier", 2).await();
                System.out.println("servers: hello, world! from java " + javaVersion + " counter = " + counter + " arrival = " + pos);
            });
            NodeArrayFuture cf = clientArray.executeOnAll(tools ->
            {
                long counter = tools.atomicCounter("counter").incrementAndGet();
                String javaVersion = System.getProperty("java.version");
                int pos = tools.barrier("barrier", 2).await();
                System.out.println("clients: hello, world! from java " + javaVersion + " counter = " + counter + " arrival = " + pos);
            });
            sf.get();
            cf.get();
        }
    }
}
