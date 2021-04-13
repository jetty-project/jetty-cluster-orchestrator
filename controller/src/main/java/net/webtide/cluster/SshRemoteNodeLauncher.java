package net.webtide.cluster;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import net.webtide.cluster.common.util.IOUtil;

public class SshRemoteNodeLauncher implements RemoteNodeLauncher
{
    private final Map<Node, Process> nodeProcesses = new HashMap<>();
    private final JvmSettings jvmSettings;

    public SshRemoteNodeLauncher(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
    }

    @Override
    public void close()
    {
        for (Process process : nodeProcesses.values())
        {
            process.destroy();
        }
        nodeProcesses.clear();
    }

    @Override
    public void launch(Node node, String connectString)
    {
        try (InputStream is = getClass().getResourceAsStream("/node.jar");
             OutputStream os = new FileOutputStream(System.getProperty("java.io.tmpdir") + "/node.jar");
        )
        {
            IOUtil.copy(is, os);

            ProcessBuilder processBuilder = new ProcessBuilder("java", "-jar", System.getProperty("java.io.tmpdir") + "/node.jar", node.getId(), connectString);
            processBuilder.inheritIO();
            Process process = processBuilder.start();
            nodeProcesses.put(node, process);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
