package net.webtide.cluster.rpc.command;

import java.io.File;
import java.util.List;

import net.webtide.cluster.configuration.JvmSettings;
import net.webtide.cluster.util.CommandLineUtil;

public class SpawnNodeCommand implements Command
{
    private final JvmSettings jvmSettings;
    private final String hostname;
    private final String remoteNodeId;
    private final String connectString;

    public SpawnNodeCommand(JvmSettings jvmSettings, String hostname, String remoteNodeId, String connectString)
    {
        this.jvmSettings = jvmSettings;
        this.hostname = hostname;
        this.remoteNodeId = remoteNodeId;
        this.connectString = connectString;
    }

    @Override
    public Object execute()
    {
        try
        {
            File nodeRootPath = CommandLineUtil.defaultRootPath(remoteNodeId);
            nodeRootPath.mkdirs();
            List<String> cmdLine = CommandLineUtil.remoteNodeCommandLine(jvmSettings, CommandLineUtil.defaultLibPath(hostname), remoteNodeId, connectString);

            new ProcessBuilder(cmdLine).directory(nodeRootPath).inheritIO().start();
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
