package net.webtide.cluster.common.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.webtide.cluster.common.Jvm;

public class SpawnNodeCommand implements Command
{
    private final Jvm jvm;
    private final List<String> opts;
    private final String remoteNodeId;
    private final String connectString;

    public SpawnNodeCommand(Jvm jvm, List<String> opts, String remoteNodeId, String connectString)
    {
        this.jvm = jvm;
        this.opts = opts;
        this.remoteNodeId = remoteNodeId;
        this.connectString = connectString;
    }

    @Override
    public Object execute() throws Exception
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.getHome() + "/bin/java");
        cmdLine.addAll(opts);
        cmdLine.addAll(Arrays.asList("-jar", System.getProperty("java.io.tmpdir") + "/node.jar", remoteNodeId, connectString));

        try
        {
            new ProcessBuilder(cmdLine).inheritIO().start();
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
