package net.webtide.cluster.configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.webtide.cluster.util.CommandLineUtil;
import net.webtide.cluster.util.IOUtil;

public class SshRemoteHostLauncher implements RemoteHostLauncher
{
    private final Map<String, Process> nodeProcesses = new HashMap<>();
    private final Jvm jvm;

    public SshRemoteHostLauncher(Jvm jvm)
    {
        this.jvm = jvm;
    }

    @Override
    public void close()
    {
        for (Map.Entry<String, Process> entry : nodeProcesses.entrySet())
        {
            Process process = entry.getValue();
            process.destroy();
            try
            {
                process.waitFor();
            }
            catch (InterruptedException e)
            {
                // ignore
            }
            String remoteHostId = entry.getKey();
            File rootPath = CommandLineUtil.defaultRootPath(remoteHostId);
            IOUtil.deltree(rootPath);
        }
        nodeProcesses.clear();
    }

    @Override
    public void launch(String remoteHostId, String connectString) throws Exception
    {
        if (nodeProcesses.containsKey(remoteHostId))
            return;

        String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
        for (String classpathEntry : classpathEntries)
        {
            File cpFile = new File(classpathEntry);
            if (!cpFile.isDirectory())
            {
                String filename = cpFile.getName();
                try (InputStream is = new FileInputStream(cpFile))
                {
                    uploadFile(remoteHostId, filename, is);
                }
            }
            else
            {
                uploadDir(remoteHostId, cpFile, 1);
            }
        }

        try
        {
            List<String> cmdLine = CommandLineUtil.remoteNodeCommandLine(jvm, CommandLineUtil.defaultLibPath(remoteHostId), remoteHostId, connectString);
            Process process = new ProcessBuilder(cmdLine).inheritIO().start();
            nodeProcesses.put(remoteHostId, process);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }


    private void uploadFile(String remoteHostId, String filename, InputStream contents) throws Exception
    {
        File rootPath = new File(System.getProperty("user.home") + "/.wtc/" + remoteHostId);
        File libPath = new File(rootPath, "lib");

        File file = new File(libPath, filename);
        file.getParentFile().mkdirs();
        try (OutputStream fos = new FileOutputStream(file))
        {
            IOUtil.copy(contents, fos);
        }
    }

    private void uploadDir(String remoteHostId, File cpFile, int depth) throws Exception
    {
        File[] files = cpFile.listFiles();
        if (files == null)
            return;

        for (File file : files)
        {
            if (!file.isDirectory())
            {

                String filename = file.getName();
                File currentFile = file;
                for (int i = 0; i < depth; i++)
                {
                    currentFile = currentFile.getParentFile();
                    filename = currentFile.getName() + "/" + filename;
                }
                try (InputStream is = new FileInputStream(file))
                {
                    uploadFile(remoteHostId, filename, is);
                }
            }
            else
            {
                uploadDir(remoteHostId, file, depth + 1);
            }
        }
    }
}
