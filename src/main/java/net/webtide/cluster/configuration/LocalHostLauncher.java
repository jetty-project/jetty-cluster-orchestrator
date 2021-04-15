package net.webtide.cluster.configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import net.webtide.cluster.rpc.NodeProcess;
import net.webtide.cluster.util.IOUtil;

public class LocalHostLauncher implements HostLauncher, JvmDependent
{
    public static final String HOSTNAME = "localhost";

    private NodeProcess localhostProcess;
    private Jvm jvm;

    @Override
    public Jvm jvm()
    {
        return jvm;
    }

    @Override
    public LocalHostLauncher jvm(Jvm jvm)
    {
        this.jvm = jvm;
        return this;
    }

    @Override
    public void launch(String hostname, String hostId, String connectString) throws Exception
    {
        if (!"localhost".equals(hostname))
            throw new IllegalArgumentException("local launcher can only work with 'localhost' hostname");
        if (localhostProcess != null)
            throw new IllegalStateException("local launcher already spawned 'localhost' process");

        String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
        for (String classpathEntry : classpathEntries)
        {
            File cpFile = new File(classpathEntry);
            if (!cpFile.isDirectory())
            {
                String filename = cpFile.getName();
                try (InputStream is = new FileInputStream(cpFile))
                {
                    copyFile(hostId, filename, is);
                }
            }
            else
            {
                copyDir(hostId, cpFile, 1);
            }
        }

        try
        {
            this.localhostProcess = NodeProcess.spawn(jvm, hostId, hostId, connectString);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close()
    {
        IOUtil.close(localhostProcess);
        localhostProcess = null;
    }

    private void copyFile(String hostId, String filename, InputStream contents) throws Exception
    {
        File rootPath = new File(System.getProperty("user.home") + "/.wtc/" + hostId);
        File libPath = new File(rootPath, "lib");

        File file = new File(libPath, filename);
        file.getParentFile().mkdirs();
        try (OutputStream fos = new FileOutputStream(file))
        {
            IOUtil.copy(contents, fos);
        }
    }

    private void copyDir(String hostId, File cpFile, int depth) throws Exception
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
                    copyFile(hostId, filename, is);
                }
            }
            else
            {
                copyDir(hostId, file, depth + 1);
            }
        }
    }
}
