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

package io.xdag.db;

import java.util.function.Supplier;

/**
 * What the blockchain needs from the persistence layer at a consensus state transition: drain
 * every queued write, then run a body whose writes go straight to the database. With no
 * write-behind layer installed ({@link #NONE}) both are the identity, which is the behaviour of
 * every node before SP0b-2 and of the offline tools that open the databases directly.
 */
public interface PersistControl {

    /** Blocks until every write queued before the call is on disk. Throws if the writer has failed. */
    void flushSync();

    /**
     * {@link #flushSync()} then runs {@code body} on the calling thread in direct-write mode:
     * its writes bypass the queue (and are therefore on disk when it returns, in program
     * order). Re-entrant: a body already in direct mode runs the nested body as is.
     */
    <T> T direct(Supplier<T> body);

    default void direct(Runnable body) {
        direct(() -> {
            body.run();
            return null;
        });
    }

    /** True while the calling thread is inside {@link #direct}. */
    boolean isBypass();

    PersistControl NONE = new PersistControl() {
        @Override
        public void flushSync() {
        }

        @Override
        public <T> T direct(Supplier<T> body) {
            return body.get();
        }

        @Override
        public boolean isBypass() {
            return true;
        }
    };
}
