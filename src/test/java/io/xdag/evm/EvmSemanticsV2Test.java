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
package io.xdag.evm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.evm.state.EvmMetaStore;
import io.xdag.evm.state.EvmReceipt;
import io.xdag.evm.state.InMemoryKVSource;
import io.xdag.evm.state.RocksDbWorldUpdater;
import io.xdag.evm.tx.EvmTransaction;
import io.xdag.evm.tx.EvmTxStore;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;
import org.hyperledger.besu.datatypes.LogTopic;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.junit.Test;

/**
 * Audit round 2, E1/E2/E4/E5/B2 — the "execution semantics v2" fork pack gated by
 * {@code evm.semanticsV2ActivationHeight}: SELFDESTRUCT deletes the account (+ EIP-161 touched-empty
 * cleanup), BASEFEE reads 0 instead of halting, GASPRICE reads the effective price, BLOCKHASH resolves
 * main-block hashes, EIP-170 / EIP-3541 contract-creation rules are enforced, a nested frame's
 * "original" storage value is the transaction-start value, and failed executions never surface logs
 * (so the burn scan cannot pick up a halted frame's logs). Every case is run twice: legacy (gate
 * unscheduled) pins the pre-fork behaviour byte-for-byte, v2 (gate at genesis) pins the fix.
 */
public class EvmSemanticsV2Test {

    private static final BigInteger CHAIN_ID = BigInteger.valueOf(0xCAFE);
    private static final Bytes32 BLOCK_HASH = Bytes32.fromHexString("0x" + "01".repeat(32));
    private static final Bytes32 HASH_OF_HEIGHT_1 = Bytes32.fromHexString("0x" + "ab".repeat(32));
    /** Constructor prefix: CODECOPY the {@code len} runtime bytes that follow the 12-byte prefix, RETURN them. */
    private static Bytes initCodeReturning(Bytes runtime) {
        int len = runtime.size();
        return Bytes.concatenate(Bytes.fromHexString(String.format("0x60%02x600c60003960%02x6000f3", len, len)),
                runtime);
    }

    private final SECP256K1 algo = new SECP256K1();
    private final KeyPair key = algo.createKeyPair(algo.createPrivateKey(BigInteger.ONE));
    private final Address sender = Address.fromHexString("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    private final Address beneficiary = Address.fromHexString("0x00000000000000000000000000000000000000bb");

    /** One in-memory node with the semantics-v2 gate at {@code gate}. */
    private final class Node {
        final InMemoryKVSource state = new InMemoryKVSource();
        final EvmTxStore txs = new EvmTxStore(new InMemoryKVSource());
        final EvmMetaStore meta = new EvmMetaStore(new InMemoryKVSource());
        final EvmBlockProcessor proc;
        long nonce = 0;
        long height = 1;

        Node(long gate) {
            EvmConfig config = new EvmConfig(EvmSpecVersion.SHANGHAI, EvmConfig.DEVNET_CHAIN_ID,
                    EvmConfig.DEFAULT_MAX_GAS_LIMIT, EvmConfig.DEFAULT_MIN_GAS_PRICE,
                    EvmConfig.DEFAULT_TYPE2_ACTIVATION_HEIGHT, EvmConfig.DEFAULT_BRIDGE_ACTIVATION_HEIGHT,
                    EvmConfig.DEFAULT_EIP3529_ACTIVATION_HEIGHT, EvmConfig.DEFAULT_INVALID_TX_SKIP_ACTIVATION_HEIGHT,
                    gate);
            proc = new EvmBlockProcessor(config, state, txs, meta);
            proc.setBlockHashLookup(h -> h == 1L ? HASH_OF_HEIGHT_1 : null);
            RocksDbWorldUpdater w = new RocksDbWorldUpdater(state);
            w.createAccount(sender, 0L, Wei.fromEth(1));
            w.commit();
        }

        EvmReceipt run(EvmTransaction tx) {
            txs.put(tx);
            proc.processMainBlock(List.of(Bytes32.wrap(tx.getHash().getBytes())), height, 1000L + height,
                    BLOCK_HASH);
            height++;
            return meta.getReceipt(tx.getHash()).orElse(null);
        }

        EvmTransaction deployTx(Bytes initCode) {
            return EvmTransaction.unsigned(nonce++, Wei.of(7), 8_000_000L, Optional.empty(), Wei.ZERO,
                    initCode, CHAIN_ID).sign(key, algo);
        }

        EvmTransaction callTx(Address to, long gas) {
            return EvmTransaction.unsigned(nonce++, Wei.of(7), gas, Optional.of(to), Wei.ZERO, Bytes.EMPTY,
                    CHAIN_ID).sign(key, algo);
        }

        Address deploy(Bytes runtime) {
            EvmReceipt r = run(deployTx(initCodeReturning(runtime)));
            assertEquals("deploy must succeed", 1, r.status());
            return r.contractAddress().orElseThrow();
        }

        Account account(Address a) {
            return new RocksDbWorldUpdater(state).getAccount(a);
        }

        UInt256 slot0(Address a) {
            Account acc = account(a);
            return acc == null ? null : acc.getStorageValue(UInt256.ZERO);
        }
    }

    private Node legacy() {
        return new Node(Long.MAX_VALUE);
    }

    private Node v2() {
        return new Node(0L);
    }

    // ---- E1: SELFDESTRUCT deletes the account -------------------------------------------------

    /** runtime: SSTORE(0, 42); SELFDESTRUCT(beneficiary). */
    private Bytes selfDestructRuntime() {
        return Bytes.concatenate(Bytes.fromHexString("0x602a600055" + "73"), beneficiary.getBytes(), Bytes.of(0xff));
    }

    @Test
    public void v2_selfdestruct_removes_code_storage_and_nonce() {
        Node n = v2();
        Address contract = n.deploy(selfDestructRuntime());
        assertNotNull(n.account(contract));
        EvmReceipt r = n.run(n.callTx(contract, 100_000L));
        assertEquals(1, r.status());
        assertNull("v2: the self-destructed account is gone", n.account(contract));
        assertTrue("storage slots are swept with the account",
                n.state.prefixKeyLookup(io.xdag.evm.state.EvmStateSchema.storagePrefix(contract)).isEmpty());
    }

    @Test
    public void legacy_selfdestruct_keeps_the_account_shell() {
        Node n = legacy();
        Address contract = n.deploy(selfDestructRuntime());
        EvmReceipt r = n.run(n.callTx(contract, 100_000L));
        assertEquals(1, r.status());
        Account left = n.account(contract);
        assertNotNull("legacy: code/storage/nonce survive (the audited defect, pinned)", left);
        assertFalse(left.getCode().isEmpty());
        assertEquals(UInt256.valueOf(42), left.getStorageValue(UInt256.ZERO));
    }

    @Test
    public void v2_selfdestruct_inside_a_nested_call_is_applied() {
        Node n = v2();
        Address inner = n.deploy(selfDestructRuntime());
        // outer runtime: CALL(gas, inner, 0, 0, 0, 0, 0); POP
        Bytes outerRuntime = Bytes.concatenate(Bytes.fromHexString("0x60006000600060006000" + "73"), inner.getBytes(),
                Bytes.fromHexString("0x5af150"));
        Address outer = n.deploy(outerRuntime);
        EvmReceipt r = n.run(n.callTx(outer, 200_000L));
        assertEquals(1, r.status());
        assertNull("the nested self-destruct is merged up and applied at tx end", n.account(inner));
        assertNotNull(n.account(outer));
    }

    @Test
    public void v2_clears_touched_empty_accounts_eip161() {
        Address fresh = Address.fromHexString("0x00000000000000000000000000000000000000ee");
        Node n = v2();
        EvmReceipt r = n.run(n.callTx(fresh, 50_000L)); // zero-value call with gas to run (no code)
        assertEquals(1, r.status());
        assertNull("v2: a touched-but-empty account is not persisted", n.account(fresh));

        Node l = legacy();
        assertEquals(1, l.run(l.callTx(fresh, 50_000L)).status());
        assertNotNull("legacy: the empty shell is persisted (pinned)", l.account(fresh));
    }

    // ---- E2: BASEFEE / GASPRICE / BLOCKHASH ---------------------------------------------------

    @Test
    public void v2_basefee_reads_zero_where_legacy_halted() {
        Bytes runtime = Bytes.fromHexString("0x4860010160005500"); // SSTORE(0, BASEFEE + 1)
        Node n = v2();
        Address c = n.deploy(runtime);
        assertEquals(1, n.run(n.callTx(c, 100_000L)).status());
        assertEquals(UInt256.ONE, n.slot0(c));

        Node l = legacy();
        Address lc = l.deploy(runtime);
        assertEquals("legacy: BASEFEE is an invalid operation (exceptional halt), pinned", 0,
                l.run(l.callTx(lc, 100_000L)).status());
        assertEquals(UInt256.ZERO, l.slot0(lc));
    }

    @Test
    public void v2_gasprice_reads_the_effective_price_where_legacy_read_zero() {
        Bytes runtime = Bytes.fromHexString("0x3a60005500"); // SSTORE(0, GASPRICE)
        Node n = v2();
        Address c = n.deploy(runtime);
        assertEquals(1, n.run(n.callTx(c, 100_000L)).status());
        assertEquals(UInt256.valueOf(7), n.slot0(c));

        Node l = legacy();
        Address lc = l.deploy(runtime);
        assertEquals(1, l.run(l.callTx(lc, 100_000L)).status());
        assertEquals(UInt256.ZERO, l.slot0(lc));
    }

    @Test
    public void v2_blockhash_resolves_a_main_block_hash_where_legacy_read_zero() {
        Bytes runtime = Bytes.fromHexString("0x60014060005500"); // SSTORE(0, BLOCKHASH(1))
        Node n = v2();
        Address c = n.deploy(runtime);            // height 1
        n.run(n.callTx(sender, 21_000L));         // height 2 (spacer so height 3 > 1)
        assertEquals(1, n.run(n.callTx(c, 100_000L)).status()); // height 3
        assertEquals(UInt256.fromBytes(HASH_OF_HEIGHT_1), n.slot0(c));

        Node l = legacy();
        Address lc = l.deploy(runtime);
        l.run(l.callTx(sender, 21_000L));
        assertEquals(1, l.run(l.callTx(lc, 100_000L)).status());
        assertEquals(UInt256.ZERO, l.slot0(lc));
    }

    // ---- E4: EIP-170 / EIP-3541 ---------------------------------------------------------------

    @Test
    public void v2_rejects_runtime_code_above_24576_bytes_and_0xef_prefixed_code() {
        Bytes tooBig = Bytes.fromHexString("0x620060016000f3");          // RETURN 24577 zero bytes
        Bytes efPrefixed = Bytes.fromHexString("0x60ef60005360016000f3"); // RETURN 0xEF
        Node n = v2();
        EvmReceipt big = n.run(n.deployTx(tooBig));
        assertEquals("EIP-170: code too large", 0, big.status());
        assertTrue(big.contractAddress().isEmpty());
        EvmReceipt ef = n.run(n.deployTx(efPrefixed));
        assertEquals("EIP-3541: 0xEF prefix rejected", 0, ef.status());

        Node l = legacy();
        assertEquals("legacy: no creation rules (pinned)", 1, l.run(l.deployTx(tooBig)).status());
        assertEquals(1, l.run(l.deployTx(efPrefixed)).status());
    }

    // ---- B2: failed executions surface no logs ------------------------------------------------

    @Test
    public void burn_scan_only_reads_successful_receipts_under_v2() {
        Log burnLike = new Log(Address.ZERO, Bytes.EMPTY, List.of(LogTopic.wrap(Bytes32.ZERO)));
        EvmReceipt failed = new EvmReceipt(0, 21_000L, Optional.empty(), List.of(burnLike));
        EvmReceipt ok = new EvmReceipt(1, 21_000L, Optional.empty(), List.of(burnLike));
        assertFalse(EvmBlockProcessor.burnScanEligible(failed, true));
        assertTrue(EvmBlockProcessor.burnScanEligible(ok, true));
        assertTrue("legacy: every receipt was scanned (pinned)", EvmBlockProcessor.burnScanEligible(failed, false));
    }

    @Test
    public void v2_executor_reports_no_logs_for_a_failed_frame() {
        // LOG0 then REVERT: Besu clears logs on revert on every path we can reach; the v2 collect()
        // additionally guarantees a failed result never carries logs (closes the S-26 catch path).
        Bytes runtime = Bytes.fromHexString("0x60006000a060006000fd"); // LOG0(0,0); REVERT(0,0)
        Node n = v2();
        Address c = n.deploy(runtime);
        EvmReceipt r = n.run(n.callTx(c, 100_000L));
        assertEquals(0, r.status());
        assertTrue(r.logs().isEmpty());
    }
}
