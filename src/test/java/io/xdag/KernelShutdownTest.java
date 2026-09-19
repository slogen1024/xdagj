/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag;

import static org.junit.Assert.assertEquals;

import io.xdag.cli.TelnetServer;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.keys.ECKeyPair;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Everything the kernel starts, the kernel stops.
 *
 * <p>The telnet console was the exception: {@code testStart()} built it and started it, and no
 * shutdown path ever called {@code stop()} on it, so its listening socket and the threads jline's
 * telnetd runs it on outlived the node.
 */
public class KernelShutdownTest {

    /**
     * A telnet server that records its stop instead of binding a port. The lifecycle base class
     * only runs {@code doStop()} on something that was started, so this has to be started too —
     * hence the no-op {@code doStart()}.
     */
    private static final class RecordingTelnetServer extends TelnetServer {

        private final AtomicInteger stops = new AtomicInteger();

        private RecordingTelnetServer(Kernel kernel) {
            super(kernel);
        }

        @Override
        protected void doStart() {
            // no terminal, no port
        }

        @Override
        protected void doStop() {
            stops.incrementAndGet();
        }
    }

    /**
     * Driven through {@code stopServices()} rather than {@code testStop()}: the latter shuts down
     * the JVM-global message-queue timer and closes the database factory, neither of which this
     * kernel has. Every component {@code stopServices()} touches is null-guarded, so a kernel
     * carrying nothing but the telnet server is a valid argument to it.
     */
    @Test
    public void stoppingTheKernelStopsTheTelnetServer() throws Exception {
        Config config = new DevnetConfig();
        Kernel kernel = new Kernel(config, ECKeyPair.generate());
        RecordingTelnetServer telnet = new RecordingTelnetServer(kernel);
        telnet.start();
        kernel.setTelnetServer(telnet);

        Method stopServices = Kernel.class.getDeclaredMethod("stopServices");
        stopServices.setAccessible(true);
        stopServices.invoke(kernel);

        assertEquals("the kernel's stop path did not stop the telnet server", 1, telnet.stops.get());
    }
}
