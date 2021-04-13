package net.webtide.cluster;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import net.webtide.cluster.common.util.IOUtil;

public class SshRemoteNodeLauncher implements RemoteNodeLauncher
{
    @Override
    public void launch(JvmSettings jvmSettings, Node node, String connectString)
    {
        try (InputStream is = getClass().getResourceAsStream("/node.jar");
             OutputStream os = new FileOutputStream(System.getProperty("java.io.tmpdir") + "/node.jar");
        )
        {
            IOUtil.copy(is, os);

            ProcessBuilder processBuilder = new ProcessBuilder("java", "-jar", System.getProperty("java.io.tmpdir") + "/node.jar", node.getId(), connectString);
            processBuilder.inheritIO();
            Process nodeProcess = processBuilder.start();

           nodeProcess.waitFor();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
