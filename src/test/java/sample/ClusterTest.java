package sample;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import net.webtide.cluster.Cluster;
import net.webtide.cluster.ClusterTools;
import net.webtide.cluster.NodeArray;
import net.webtide.cluster.NodeArrayFuture;
import net.webtide.cluster.configuration.ClusterConfiguration;
import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.configuration.Node;
import net.webtide.cluster.configuration.NodeArrayTopology;
import net.webtide.cluster.configuration.SimpleClusterConfiguration;
import net.webtide.cluster.configuration.SimpleNodeArrayConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ClusterTest
{
    private static Stream<ClusterConfiguration> clusterConfigurations() throws Exception
    {
        ClusterConfiguration cfg1 = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").topology(new NodeArrayTopology(new Node("1", "localhost"), new Node("2", "localhost")))
                .jvm(new Jvm(() -> "/work/tools/jdk/1.11/bin/java"))
            )
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").topology(new NodeArrayTopology(new Node("1", "localhost"), new Node("2", "localhost")))
                .jvm(new Jvm(() -> "/work/tools/jdk/1.8/bin/java"))
            )
            ;

        ClusterConfiguration cfg2 = new SimpleClusterConfiguration()
            .jvm(new Jvm(() -> "/work/tools/jdk/1.15/bin/java"))
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").topology(new NodeArrayTopology(new Node("1", "localhost"))))
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").topology(new NodeArrayTopology(new Node("1", "localhost"))))
            ;

        String localHostname = InetAddress.getLocalHost().getHostName();
        ClusterConfiguration cfg3 = new SimpleClusterConfiguration()
            .jvm(new Jvm(() -> "/work/tools/jdk/1.15/bin/java"))
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").topology(new NodeArrayTopology(new Node("1", localHostname))))
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").topology(new NodeArrayTopology(new Node("1", localHostname))))
            ;

        return Stream.of(cfg1, cfg2, cfg3);
    }

    @ParameterizedTest
    @MethodSource("clusterConfigurations")
    public void test(ClusterConfiguration cfg) throws Exception
    {
        try (Cluster cluster = new Cluster("ClusterTest::test", cfg))
        {
            final int participantCount = cfg.nodeArrays().stream().mapToInt(cc -> cc.topology().nodes().size()).sum() + 1;
            NodeArray serverArray = cluster.nodeArray("server-array");
            NodeArray clientArray = cluster.nodeArray("client-array");

            NodeArrayFuture sf = serverArray.executeOnAll(tools ->
            {
                long counter = tools.atomicCounter("counter").incrementAndGet();
                String javaVersion = System.getProperty("java.version");
                int pos = tools.barrier("barrier", participantCount).await();
                System.out.println("servers: hello, world! from java " + javaVersion + " counter = " + counter + " arrival = " + pos);
            });
            NodeArrayFuture cf = clientArray.executeOnAll(tools ->
            {
                long counter = tools.atomicCounter("counter").incrementAndGet();
                String javaVersion = System.getProperty("java.version");
                int pos = tools.barrier("barrier", participantCount).await();
                System.out.println("clients: hello, world! from java " + javaVersion + " counter = " + counter + " arrival = " + pos);

                File f = new File("data.txt");
                try (FileOutputStream fos = new FileOutputStream(f))
                {
                    fos.write("hello, clients in file\n".getBytes(StandardCharsets.UTF_8));
                }
                System.out.println("wrote file " + f.getAbsolutePath());
            });

            ClusterTools tools = cluster.tools();
            long counter = tools.atomicCounter("counter").incrementAndGet();
            int pos = tools.barrier("barrier", participantCount).await();
            System.out.println("test: hello, world! counter = " + counter + " arrival = " + pos);

            sf.get();
            cf.get();

            Path dataPath = clientArray.rootPathOf("1").resolve("data.txt");

            try (InputStream is = Files.newInputStream(dataPath))
            {
                while (true)
                {
                    int b = is.read();
                    if (b == -1)
                        break;
                    System.out.print((char) b);
                }
            }
        }
    }
}
