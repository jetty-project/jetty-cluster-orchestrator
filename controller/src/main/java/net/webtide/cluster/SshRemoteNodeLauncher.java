package net.webtide.cluster;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.webtide.cluster.common.util.IOUtil;

public class SshRemoteNodeLauncher implements RemoteNodeLauncher
{
    private final Map<String, Process> nodeProcesses = new HashMap<>();
    private final JvmSettings jvmSettings;

    public SshRemoteNodeLauncher(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
        try (InputStream is = getClass().getResourceAsStream("/node.jar");
             OutputStream os = new FileOutputStream(System.getProperty("java.io.tmpdir") + "/node.jar");
        )
        {
            IOUtil.copy(is, os);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
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
    public void launch(String nodeId, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvmSettings.jvm().getHome() + "/bin/java");
        cmdLine.addAll(jvmSettings.getOpts());
        cmdLine.addAll(Arrays.asList("-jar", System.getProperty("java.io.tmpdir") + "/node.jar", nodeId, connectString));

        try
        {
            ProcessBuilder processBuilder = new ProcessBuilder(cmdLine);
            processBuilder.inheritIO();
            Process process = processBuilder.start();
            nodeProcesses.put(nodeId, process);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
