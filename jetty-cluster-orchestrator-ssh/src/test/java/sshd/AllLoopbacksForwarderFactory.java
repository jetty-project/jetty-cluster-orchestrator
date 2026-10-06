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

package sshd;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.apache.sshd.common.forward.DefaultForwarder;
import org.apache.sshd.common.forward.DefaultForwarderFactory;
import org.apache.sshd.common.forward.Forwarder;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.util.net.SshdSocketAddress;

/**
 * Forwards "localhost" on both 127.0.0.1 and ::1, like OpenSSH does.
 * MINA only listens on one of them, so a JVM that picks the other one gets refused.
 */
public class AllLoopbacksForwarderFactory extends DefaultForwarderFactory {
    private static final List<String> LOOPBACKS = List.of(SshdSocketAddress.LOCALHOST_IPV4, SshdSocketAddress.IPV6_SHORT_LOCALHOST);

    @Override
    public Forwarder create(ConnectionService service) {
        Forwarder forwarder = new AllLoopbacksForwarder(service);
        forwarder.addPortForwardingEventListenerManager(this);
        return forwarder;
    }

    private static class AllLoopbacksForwarder extends DefaultForwarder {
        private AllLoopbacksForwarder(ConnectionService service) {
            super(service);
        }

        @Override
        public synchronized SshdSocketAddress localPortForwardingRequested(SshdSocketAddress local) throws IOException {
            SshdSocketAddress bound = super.localPortForwardingRequested(local);
            if (bound == null || !SshdSocketAddress.LOCALHOST_NAME.equals(local.getHostName())) {
                return bound;
            }
            // MINA listens on one of them, add the other on the same port
            InetAddress boundAddress = InetAddress.getByName(bound.getHostName());
            for (String loopback : LOOPBACKS) {
                InetAddress address = InetAddress.getByName(loopback);
                if (address.equals(boundAddress)) {
                    continue;
                }
                try {
                    getLocalIoAcceptor().bind(new InetSocketAddress(address, bound.getPort()));
                } catch (IOException e) {
                    // no IPv6 here, or the port is taken, just skip it
                }
            }
            return bound;
        }
    }
}
