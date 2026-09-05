//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.mortbay.jetty.orchestrator.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamCopier
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamCopier.class);

    private final InputStream is;
    private final OutputStream os;
    private final int bufferSize;

    public StreamCopier(InputStream is, OutputStream os, boolean lineBuffering)
    {
        this(is, os, 256, lineBuffering);
    }

    public StreamCopier(InputStream is, OutputStream os, int bufferSize, boolean lineBuffering)
    {
        this.is = is;
        if (lineBuffering)
            this.os = new LineBufferingOutputStream(os, bufferSize);
        else
            this.os = os;
        this.bufferSize = bufferSize;
    }

    public void spawnDaemon(String name)
    {
        Thread thread = new Thread(() ->
        {
            try
            {
                IOUtil.copy(is, os, bufferSize, true);
            }
            catch (Exception e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error copying stream", e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static class LineBufferingOutputStream extends OutputStream
    {
        private final OutputStream delegate;
        private final ByteBuilder byteBuilder;

        public LineBufferingOutputStream(OutputStream delegate, int bufferSize)
        {
            this.delegate = delegate;
            this.byteBuilder = new ByteBuilder(bufferSize);
        }

        @Override
        public void write(int b) throws IOException
        {
            if (byteBuilder.isFull())
            {
                delegate.write(byteBuilder.getBuffer());
                delegate.flush();
                byteBuilder.clear();
            }
            byteBuilder.append(b);
            if (b == '\n' || b == '\r')
            {
                delegate.write(byteBuilder.getBuffer(), 0, byteBuilder.length());
                delegate.flush();
                byteBuilder.clear();
            }
        }

        @Override
        public void close() throws IOException
        {
            delegate.close();
        }
    }

    private static class ByteBuilder
    {
        private final byte[] buffer;
        private int length = 0;

        public ByteBuilder(int buffSize)
        {
            this.buffer = new byte[buffSize];
        }

        public void append(int b)
        {
            if (isFull())
                throw new IllegalStateException("buffer is full");
            buffer[length] = (byte)b;
            length++;
        }

        public void clear()
        {
            length = 0;
        }

        public byte[] getBuffer()
        {
            return buffer;
        }

        public boolean isFull()
        {
            return length == buffer.length;
        }

        public int length()
        {
            return length;
        }
    }
}
