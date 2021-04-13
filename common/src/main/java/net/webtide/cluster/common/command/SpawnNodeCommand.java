package net.webtide.cluster.common.command;

import java.io.File;
import java.util.List;

import net.webtide.cluster.common.Jvm;
import net.webtide.cluster.common.JvmSettings;
import net.webtide.cluster.common.util.CommandLineUtil;

public class SpawnNodeCommand implements Command
{
    private final Jvm jvm;
    private final List<String> opts;
    private final String hostname;
    private final String remoteNodeId;
    private final String connectString;

    public SpawnNodeCommand(Jvm jvm, List<String> opts, String hostname, String remoteNodeId, String connectString)
    {
        this.jvm = jvm;
        this.opts = opts;
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
            List<String> cmdLine = CommandLineUtil.remoteNodeCommandLine(new JvmSettings(() -> jvm, opts.toArray(new String[0])), CommandLineUtil.defaultLibPath(hostname), remoteNodeId, connectString);

            new ProcessBuilder(cmdLine).directory(nodeRootPath).inheritIO().start();
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
