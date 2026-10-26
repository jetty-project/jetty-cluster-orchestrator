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

package org.mortbay.jetty.orchestrator.ssh.launcher;

import java.io.EOFException;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

/**
 * Client-side only {@link SshAgentFactory} talking to a running ssh-agent over its unix domain socket
 * (usually {@code $SSH_AUTH_SOCK}) with the JDK's own unix socket support, so no native library is needed.
 * Agent forwarding is not supported.
 */
class UnixSocketAgentFactory implements SshAgentFactory {
    private final Path socketPath;

    UnixSocketAgentFactory(Path socketPath) {
        this.socketPath = socketPath;
    }

    /**
     * @return true if an ssh-agent accepts connections on the given socket; a stale socket file
     * left behind by a dead agent, a non-socket file or a missing file all make it unusable.
     */
    static boolean isUsable(Path socketPath) {
        if (!Files.isReadable(socketPath) || !Files.isWritable(socketPath)) {
            return false;
        }
        try (SocketChannel ignored = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))) {
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    @Override
    public List<ChannelFactory> getChannelForwardingFactories(FactoryManager manager) {
        return Collections.emptyList();
    }

    @Override
    public SshAgent createClient(Session session, FactoryManager manager) throws IOException {
        return new UnixSocketAgentProxy(SocketChannel.open(UnixDomainSocketAddress.of(socketPath)));
    }

    @Override
    public SshAgentServer createServer(ConnectionService service) {
        throw new UnsupportedOperationException("agent forwarding is not supported");
    }

    private static class UnixSocketAgentProxy extends AbstractAgentProxy {
        private final SocketChannel channel;

        private UnixSocketAgentProxy(SocketChannel channel) {
            super(null);
            this.channel = channel;
        }

        @Override
        protected synchronized Buffer request(Buffer buffer) throws IOException {
            // the buffer was created with room for the length prefix, prepare() fills it in
            buffer = prepare(buffer);
            ByteBuffer out = ByteBuffer.wrap(buffer.array(), buffer.rpos(), buffer.available());
            while (out.hasRemaining()) {
                channel.write(out);
            }
            int length = readFully(4).getInt();
            return new ByteArrayBuffer(readFully(length).array());
        }

        private ByteBuffer readFully(int length) throws IOException {
            ByteBuffer in = ByteBuffer.allocate(length);
            while (in.hasRemaining()) {
                if (channel.read(in) < 0) {
                    throw new EOFException("ssh-agent closed the connection");
                }
            }
            return in.flip();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() throws IOException {
            try {
                channel.close();
            } finally {
                super.close();
            }
        }
    }
}
