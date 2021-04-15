package net.webtide.cluster.rpc.command;

import java.io.File;
import java.util.List;

import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.util.CommandLineUtil;

public class SpawnNodeCommand implements Command
{
    private final Jvm jvm;
    private final String remoteHostId;
    private final String remoteNodeId;
    private final String connectString;

    public SpawnNodeCommand(Jvm jvm, String remoteHostId, String remoteNodeId, String connectString)
    {
        this.jvm = jvm;
        this.remoteHostId = remoteHostId;
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
            List<String> cmdLine = CommandLineUtil.remoteNodeCommandLine(jvm, CommandLineUtil.defaultLibPath(remoteHostId), remoteNodeId, connectString);

            new ProcessBuilder(cmdLine).directory(nodeRootPath).inheritIO().start();
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
