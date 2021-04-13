package net.webtide.cluster.common.command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.webtide.cluster.common.Jvm;
import net.webtide.cluster.common.util.IOUtil;

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
    public Object execute() throws Exception
    {
        File rootPath = new File(System.getProperty("user.home") + "/.wtc/" + hostname);
        File libPath = new File(rootPath, "lib");
        File nodeRootPath = new File(System.getProperty("user.home") + "/.wtc/" + remoteNodeId);
        nodeRootPath.mkdirs();

        String jarOfCurrentClass = getClass().getProtectionDomain().getCodeSource().getLocation().getFile();
        IOUtil.copyFile(new File(jarOfCurrentClass), libPath);

        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.getHome() + "/bin/java");
        cmdLine.addAll(opts);
        cmdLine.add("-classpath");
        cmdLine.add(buildClassPath(libPath));
        cmdLine.add("net.webtide.cluster.node.RemoteNode");
        cmdLine.add(remoteNodeId);
        cmdLine.add(connectString);

        try
        {
            new ProcessBuilder(cmdLine).directory(nodeRootPath).inheritIO().start();
            return null;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static String buildClassPath(File libPath)
    {
        File[] entries = libPath.listFiles();
        StringBuilder sb = new StringBuilder();
        for (File entry : entries)
        {
            sb.append(entry.getPath()).append(File.pathSeparatorChar);
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }
}
