package net.webtide.cluster.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.webtide.cluster.configuration.Jvm;
import net.webtide.cluster.rpc.RemoteNode;

public class CommandLineUtil
{
    public static File defaultRootPath(String remoteHostId)
    {
        return new File(System.getProperty("user.home") + "/.wtc/" + remoteHostId);
    }

    public static File defaultLibPath(String remoteHostId)
    {
        File rootPath = defaultRootPath(remoteHostId);
        return new File(rootPath, "lib");
    }

    public static List<String> remoteNodeCommandLine(Jvm jvm, File libPath, String remoteHostId, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.executable());
        cmdLine.addAll(jvm.getOpts());
        cmdLine.add("-classpath");
        cmdLine.add(buildClassPath(libPath));
        cmdLine.add(RemoteNode.class.getName());
        cmdLine.add(remoteHostId);
        cmdLine.add(connectString);
        return cmdLine;
    }

    private static String buildClassPath(File libPath)
    {
        File[] entries = libPath.listFiles();
        StringBuilder sb = new StringBuilder();
        for (File entry : entries)
        {
            sb.append(entry.getPath()).append(File.pathSeparatorChar);
        }
        if (sb.length() > 0)
            sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }
}
