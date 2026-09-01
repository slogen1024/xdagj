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
package io.xdag.evm.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.xdag.config.spec.EvmSpec;
import io.xdag.core.XAmount;
import io.xdag.db.AddressStore;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.evm.GenesisAllocEntry;
import io.xdag.evm.state.InMemoryKVSource;
import java.math.BigInteger;
import java.util.List;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class GenesisLockSeederTest {

    private static final Address FUNDED =
            Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    // 1,000,000 * 1e18 wei == 1e24 wei == 1e15 nano (whole-nano by construction).
    private static final BigInteger ALLOC_WEI = new BigInteger("1000000000000000000000000");
    private static final long ALLOC_NANO = 1_000_000_000_000_000L;

    private AddressStore addressStore;

    @Before
    public void setUp() {
        addressStore = new AddressStoreImpl(new InMemoryKVSource());
        addressStore.reset();
    }

    private static EvmSpec spec(long bridgeActivation, List<GenesisAllocEntry> alloc) {
        EvmSpec spec = Mockito.mock(EvmSpec.class);
        Mockito.when(spec.getEvmBridgeActivationHeight()).thenReturn(bridgeActivation);
        Mockito.when(spec.getEvmGenesisAlloc()).thenReturn(alloc);
        return spec;
    }

    @Test
    public void seeds_lock_once_with_the_alloc_native_equivalent() {
        EvmSpec spec = spec(0L, List.of(new GenesisAllocEntry(FUNDED, Wei.of(ALLOC_WEI))));
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();

        assertFalse(addressStore.isEvmGenesisLockSeeded());
        assertEquals(ALLOC_NANO, GenesisLockSeeder.seedIfAbsent(addressStore, spec));
        assertEquals("lock holds the alloc's native equivalent",
                XAmount.of(ALLOC_NANO), addressStore.getBalanceByAddress(lockKey));
        assertTrue(addressStore.isEvmGenesisLockSeeded());

        // Idempotent: a second call (normal restart) must not double-credit.
        assertEquals(0L, GenesisLockSeeder.seedIfAbsent(addressStore, spec));
        assertEquals(XAmount.of(ALLOC_NANO), addressStore.getBalanceByAddress(lockKey));
    }

    @Test
    public void no_seed_when_bridge_unscheduled_or_alloc_empty() {
        byte[] lockKey = BridgeConstants.LOCK_ADDRESS_20.toArray();

        // Bridge unscheduled: alloc wei is unbacked-by-design (unwithdrawable, unroutable) — no seed.
        assertEquals(0L, GenesisLockSeeder.seedIfAbsent(addressStore,
                spec(Long.MAX_VALUE, List.of(new GenesisAllocEntry(FUNDED, Wei.of(ALLOC_WEI))))));
        assertEquals(XAmount.ZERO, addressStore.getBalanceByAddress(lockKey));
        assertFalse(addressStore.isEvmGenesisLockSeeded());

        // Bridge scheduled, empty alloc: nothing to back — no seed, no marker.
        assertEquals(0L, GenesisLockSeeder.seedIfAbsent(addressStore, spec(0L, List.of())));
        assertFalse(addressStore.isEvmGenesisLockSeeded());
    }

    @Test
    public void alloc_total_nano_sums_entries_exactly() {
        assertEquals(3L, GenesisLockSeeder.allocTotalNano(List.of(
                new GenesisAllocEntry(FUNDED, Wei.of(BigInteger.valueOf(1_000_000_000L))),
                new GenesisAllocEntry(Address.fromHexString(
                        "0x00000000000000000000000000000000000000aa"),
                        Wei.of(BigInteger.valueOf(2_000_000_000L))))));
    }

    @Test
    public void alloc_total_nano_rejects_sub_nano_dust() {
        // 1e9 + 1 wei: the trailing wei has no nano representation — conservation would break.
        org.junit.Assert.assertThrows(ArithmeticException.class,
                () -> GenesisLockSeeder.allocTotalNano(List.of(new GenesisAllocEntry(FUNDED,
                        Wei.of(BigInteger.valueOf(1_000_000_001L))))));
    }
}
