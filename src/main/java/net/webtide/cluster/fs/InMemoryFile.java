package net.webtide.cluster.fs;

import java.io.ByteArrayOutputStream;

import net.schmizz.sshj.xfer.InMemoryDestFile;

class InMemoryFile extends InMemoryDestFile
{
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    @Override
    public ByteArrayOutputStream getOutputStream()
    {
        return outputStream;
    }
}
