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
package io.xdag.evm.state;

import io.xdag.db.rocksdb.KVSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/**
 * Reconstructs the EVM world state at a past main height within the retained window (sub-project C4)
 * by overlaying reverse-delta journals onto the latest {@code EVM_STATE} store.
 *
 * <p>For a target height {@code H} and executed head {@code head}: walk the journals for heights
 * {@code H+1 .. head} ascending and, for each key, keep the <b>lowest</b> touching height's pre-image
 * — that is the key's value at {@code H}. Keys untouched in that range fall through to the latest
 * store. The overlay is exposed as a read-only {@link KVSource} so an ordinary
 * {@link RocksDbWorldUpdater} reads it exactly like the live store — point reads and {@code eth_call}
 * share one path.
 */
public class HistoricalStateReader {

    private final KVSource<byte[], byte[]> latest;
    private final EvmStateJournal journal;
    private final int window;

    public HistoricalStateReader(KVSource<byte[], byte[]> latest, EvmStateJournal journal, int window) {
        this.latest = latest;
        this.journal = journal;
        this.window = window;
    }

    /**
     * A read-only world at {@code targetHeight}. {@code targetHeight == head} returns the live store;
     * a height above head or below the retained window throws {@link StateUnavailableException}.
     *
     * <p>The returned world is READ-ONLY: use it only through non-committing paths (e.g.
     * {@code getAccount}, {@code eth_call} simulation). Mutating it and committing throws
     * {@link UnsupportedOperationException} from the underlying overlay — it can never write to the
     * live {@code EVM_STATE} store.
     */
    public WorldUpdater worldAt(long targetHeight, long head) {
        if (targetHeight == head) {
            return new RocksDbWorldUpdater(latest);
        }
        if (targetHeight > head || targetHeight < head - window + 1) {
            throw new StateUnavailableException(targetHeight);
        }
        // Tri-state overlay: key -> present value; key -> Optional.empty() (tombstone, absent at target);
        // key absent from the map entirely -> untouched in (target, head], so fall through to the base store.
        Map<Bytes, Optional<byte[]>> overlay = new HashMap<>();
        for (long h = targetHeight + 1; h <= head; h++) {
            Optional<List<EvmStateJournal.Entry>> entries = journal.getHeightJournal(h);
            if (entries.isEmpty()) {
                continue;
            }
            for (EvmStateJournal.Entry e : entries.get()) {
                overlay.putIfAbsent(Bytes.wrap(e.key()), Optional.ofNullable(e.priorValue()));
            }
        }
        return new RocksDbWorldUpdater(new ReadOnlyOverlayKVSource(overlay, latest));
    }

    /**
     * A {@link KVSource} whose {@code get} returns the overlay's pre-image for a key when present (an
     * empty {@link Optional} = absent at the target height), else the latest store. All mutators throw:
     * the historical world is only ever read through non-committing paths. Non-{@code get} reads that
     * the write-path helpers use (e.g. {@code prefixKeyLookup} in a simulated CREATE) delegate to the
     * latest store — harmless, since those helpers only ever act on freshly-created addresses.
     */
    static final class ReadOnlyOverlayKVSource implements KVSource<byte[], byte[]> {

        private static final String READ_ONLY = "historical overlay is read-only";

        private final Map<Bytes, Optional<byte[]>> overlay;
        private final KVSource<byte[], byte[]> base;

        ReadOnlyOverlayKVSource(Map<Bytes, Optional<byte[]>> overlay, KVSource<byte[], byte[]> base) {
            this.overlay = overlay;
            this.base = base;
        }

        @Override
        public byte[] get(byte[] key) {
            Optional<byte[]> o = overlay.get(Bytes.wrap(key));
            if (o != null) {
                return o.orElse(null);
            }
            return base.get(key);
        }

        @Override
        public List<byte[]> prefixKeyLookup(byte[] key) {
            return base.prefixKeyLookup(key);
        }

        @Override
        public String getName() {
            return "historical-overlay";
        }

        @Override
        public void setName(String name) {
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void init() {
        }

        @Override
        public void close() {
        }

        @Override
        public Set<byte[]> keys() {
            return base.keys();
        }

        @Override
        public void fetchPrefix(byte[] key, Function<Pair<byte[], byte[]>, Boolean> func) {
            base.fetchPrefix(key, func);
        }

        @Override
        public List<byte[]> prefixValueLookup(byte[] key) {
            return base.prefixValueLookup(key);
        }

        @Override
        public List<Pair<byte[], byte[]>> prefixKeyAndValueLookup(byte[] key) {
            return base.prefixKeyAndValueLookup(key);
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException(READ_ONLY);
        }

        @Override
        public void put(byte[] key, byte[] val) {
            throw new UnsupportedOperationException(READ_ONLY);
        }

        @Override
        public void delete(byte[] key) {
            throw new UnsupportedOperationException(READ_ONLY);
        }

        @Override
        public void batchWrite(Map<byte[], byte[]> puts, Set<byte[]> deletes) {
            throw new UnsupportedOperationException(READ_ONLY);
        }
    }
}
