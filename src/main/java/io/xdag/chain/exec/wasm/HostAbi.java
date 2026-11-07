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

package io.xdag.chain.exec.wasm;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.TrapException;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.ValType;

import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.PublicKey;
import io.xdag.crypto.keys.Signature;
import io.xdag.crypto.keys.Signer;

/**
 * §8.3 host functions. Pointers are {@code i32}. Lengths below zero trap. {@code secp256k1_verify}
 * reads a 32-byte hash, a 65-byte {@link Signature#decode} blob and a 33-byte compressed public key.
 * {@code secp256k1_recover} reads a 32-byte hash, 64-byte {@code r || s}, a recovery id and writes
 * 33 bytes. Both return 0 on a bad input instead of trapping. {@code call} charges only the flat
 * 500; the callee does not run until SP1-3. {@code send}'s chain and contract pointers are 20 bytes,
 * and the per-32-byte term is the argument payload. A repeated {@code storage_get} of a key already
 * read in this call skips the witness term; {@code storage_set} skips it only when that read already
 * happened. {@code storage_scan} writes {@code i32 count} then, for each hit, {@code i32 keyLen},
 * the key, {@code i32 valLen}, the value.
 */
public final class HostAbi {

    private static final int HASH_LEN = 32;
    private static final int SIG_LEN = 65;
    private static final int PUB_LEN = 33;
    private static final int RS_LEN = 64;
    private static final int ABORT_MESSAGE_CAP = 1024;

    private static final FunctionType GAS = fun(new ValType[] {ValType.I32}, new ValType[0]);
    private static final Map<String, FunctionType> TYPES = Map.ofEntries(
            Map.entry("input_len", fun(new ValType[0], new ValType[] {ValType.I32})),
            Map.entry("input_read", fun(new ValType[] {ValType.I32}, new ValType[0])),
            Map.entry("selector", fun(new ValType[0], new ValType[] {ValType.I32})),
            Map.entry("return_data", fun(new ValType[] {ValType.I32, ValType.I32}, new ValType[0])),
            Map.entry("caller", fun(new ValType[] {ValType.I32}, new ValType[0])),
            Map.entry("self_address", fun(new ValType[] {ValType.I32}, new ValType[0])),
            Map.entry("chain_id", fun(new ValType[] {ValType.I32}, new ValType[0])),
            Map.entry("value", fun(new ValType[0], new ValType[] {ValType.I64})),
            Map.entry("height", fun(new ValType[0], new ValType[] {ValType.I64})),
            Map.entry("timestamp", fun(new ValType[0], new ValType[] {ValType.I64})),
            Map.entry("storage_get", fun(i32s(4), new ValType[] {ValType.I32})),
            Map.entry("storage_set", fun(i32s(4), new ValType[0])),
            Map.entry("storage_remove", fun(i32s(2), new ValType[0])),
            Map.entry("storage_scan", fun(i32s(4), new ValType[] {ValType.I32})),
            Map.entry("balance", fun(new ValType[] {ValType.I32}, new ValType[] {ValType.I64})),
            Map.entry("transfer", fun(new ValType[] {ValType.I32, ValType.I64}, new ValType[] {ValType.I32})),
            Map.entry("transfer_from_caller", fun(new ValType[] {ValType.I64}, new ValType[] {ValType.I32})),
            Map.entry("withdraw", fun(new ValType[] {ValType.I32, ValType.I64}, new ValType[] {ValType.I32})),
            Map.entry("call", fun(new ValType[] {ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I64,
                    ValType.I32, ValType.I32}, new ValType[] {ValType.I32})),
            Map.entry("send", fun(new ValType[] {ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32,
                    ValType.I64}, new ValType[] {ValType.I32})),
            Map.entry("emit", fun(i32s(4), new ValType[0])),
            Map.entry("sha256", fun(i32s(3), new ValType[0])),
            Map.entry("keccak256", fun(i32s(3), new ValType[0])),
            Map.entry("secp256k1_verify", fun(i32s(3), new ValType[] {ValType.I32})),
            Map.entry("secp256k1_recover", fun(i32s(4), new ValType[] {ValType.I32})),
            Map.entry("gas_left", fun(new ValType[0], new ValType[] {ValType.I64})),
            Map.entry("abort", fun(i32s(2), new ValType[0])));

    private HostAbi() {
    }

    /** Contract-facing imports. {@code null} for {@code gas} and for unknown names. */
    public static FunctionType signature(String name) {
        return TYPES.get(name);
    }

    static FunctionType gasType() {
        return GAS;
    }

    static ImportValues bind(WasmModule module, GasMeter meter, HostContext ctx, boolean writes) {
        Set<Bytes> reads = new HashSet<>();
        ImportValues.Builder builder = ImportValues.builder();
        int n = module.importSection().importCount();
        for (int i = 0; i < n; i++) {
            Import im = module.importSection().getImport(i);
            if (im instanceof FunctionImport fi) {
                builder.addFunction(host(fi.name(), meter, ctx, writes, reads));
            }
        }
        return builder.build();
    }

    private static HostFunction host(String name, GasMeter meter, HostContext ctx, boolean writes, Set<Bytes> reads) {
        FunctionType type = "gas".equals(name) ? GAS : TYPES.get(name);
        if (type == null) {
            throw new IllegalStateException("no host function xdag." + name);
        }
        return new HostFunction("xdag", name, type,
                (instance, args) -> dispatch(name, instance, args, meter, ctx, writes, reads));
    }

    private static long[] dispatch(String name, Instance instance, long[] args, GasMeter meter, HostContext ctx,
            boolean writes, Set<Bytes> reads) {
        Memory mem = instance.memory();
        switch (name) {
            case "gas" -> {
                meter.charge(Integer.toUnsignedLong(i32(args, 0)));
                return none();
            }
            case "input_len" -> {
                meter.charge(GasSchedule.INPUT_LEN);
                return i32(ctx.input().length);
            }
            case "input_read" -> {
                byte[] input = ctx.input();
                meter.charge(GasSchedule.INPUT_READ + GasSchedule.bytesGas(input.length, GasSchedule.PER_32_INPUT));
                write(mem, i32(args, 0), input);
                return none();
            }
            case "selector" -> {
                meter.charge(GasSchedule.SELECTOR);
                return i32(ctx.selector());
            }
            case "return_data" -> {
                byte[] data = read(mem, i32(args, 0), i32(args, 1));
                meter.charge(GasSchedule.RETURN_DATA + GasSchedule.bytesGas(data.length, GasSchedule.PER_32_RETURN));
                ctx.setReturnData(data);
                return none();
            }
            case "caller" -> {
                meter.charge(GasSchedule.ADDRESS_WRITE);
                write(mem, i32(args, 0), MemoryHostContext.address(ctx.caller()));
                return none();
            }
            case "self_address" -> {
                meter.charge(GasSchedule.ADDRESS_WRITE);
                write(mem, i32(args, 0), MemoryHostContext.address(ctx.selfAddress()));
                return none();
            }
            case "chain_id" -> {
                meter.charge(GasSchedule.ADDRESS_WRITE);
                write(mem, i32(args, 0), MemoryHostContext.address(ctx.chainId()));
                return none();
            }
            case "value" -> {
                meter.charge(GasSchedule.VALUE);
                return i64(ctx.value());
            }
            case "height" -> {
                meter.charge(GasSchedule.HEIGHT);
                return i64(ctx.height());
            }
            case "timestamp" -> {
                meter.charge(GasSchedule.HEIGHT);
                return i64(ctx.timestamp());
            }
            case "gas_left" -> {
                meter.charge(GasSchedule.GAS_LEFT);
                return i64(meter.left());
            }
            case "abort" -> {
                int len = i32(args, 1);
                byte[] msg = read(mem, i32(args, 0), len);
                int n = Math.min(msg.length, ABORT_MESSAGE_CAP);
                throw new TrapException(new String(msg, 0, n, StandardCharsets.UTF_8));
            }
            case "sha256" -> {
                hash(mem, args, meter, true);
                return none();
            }
            case "keccak256" -> {
                hash(mem, args, meter, false);
                return none();
            }
            case "secp256k1_verify" -> {
                meter.charge(GasSchedule.SECP256K1);
                return i32(verify(mem, args));
            }
            case "secp256k1_recover" -> {
                meter.charge(GasSchedule.SECP256K1);
                return i32(recover(mem, args));
            }
            case "balance" -> {
                meter.charge(GasSchedule.BALANCE);
                return i64(ctx.balanceOf(read(mem, i32(args, 0), GasSchedule.ADDRESS_BYTES)));
            }
            case "storage_get" -> {
                return i32(storageGet(mem, args, meter, ctx, reads));
            }
            case "storage_set" -> {
                storageSet(mem, args, meter, ctx, writes, reads);
                return none();
            }
            case "storage_remove" -> {
                storageRemove(mem, args, meter, ctx, writes);
                return none();
            }
            case "storage_scan" -> {
                return i32(storageScan(mem, args, meter, ctx, reads));
            }
            case "transfer" -> {
                byte[] to = read(mem, i32(args, 0), GasSchedule.ADDRESS_BYTES);
                long amount = args[1];
                MemoryHostContext.rejectNegative(amount);
                meter.charge(GasSchedule.TRANSFER);
                requireWrite(writes);
                return i32(ctx.transfer(to, amount));
            }
            case "transfer_from_caller" -> {
                long amount = args[0];
                MemoryHostContext.rejectNegative(amount);
                meter.charge(GasSchedule.TRANSFER_FROM_CALLER);
                requireWrite(writes);
                return i32(ctx.transferFromCaller(amount));
            }
            case "withdraw" -> {
                byte[] to = read(mem, i32(args, 0), GasSchedule.ADDRESS_BYTES);
                long amount = args[1];
                MemoryHostContext.rejectNegative(amount);
                meter.charge(GasSchedule.WITHDRAW);
                requireWrite(writes);
                return i32(ctx.withdraw(to, amount));
            }
            case "call" -> {
                byte[] contract = read(mem, i32(args, 0), GasSchedule.ADDRESS_BYTES);
                int sel = i32(args, 1);
                byte[] callArgs = read(mem, i32(args, 2), i32(args, 3));
                long value = args[4];
                MemoryHostContext.rejectNegative(value);
                int rcap = i32(args, 6);
                if (rcap > 0) {
                    bounds(mem, i32(args, 5), rcap);
                }
                meter.charge(GasSchedule.CALL);
                requireWrite(writes);
                return i32(ctx.call(contract, sel, callArgs, value));
            }
            case "send" -> {
                byte[] dst = read(mem, i32(args, 0), GasSchedule.ADDRESS_BYTES);
                byte[] contract = read(mem, i32(args, 1), GasSchedule.ADDRESS_BYTES);
                int sel = i32(args, 2);
                byte[] payload = read(mem, i32(args, 3), i32(args, 4));
                long value = args[5];
                MemoryHostContext.rejectNegative(value);
                meter.charge(GasSchedule.SEND + GasSchedule.bytesGas(payload.length, GasSchedule.PER_32_SEND));
                requireWrite(writes);
                return i32(ctx.send(dst, contract, sel, payload, value));
            }
            case "emit" -> {
                byte[] topic = read(mem, i32(args, 0), i32(args, 1));
                byte[] data = read(mem, i32(args, 2), i32(args, 3));
                meter.charge(GasSchedule.EMIT
                        + GasSchedule.bytesGas(topic.length + data.length, GasSchedule.PER_32_EMIT));
                requireWrite(writes);
                ctx.emit(topic, data);
                return none();
            }
            default -> throw new TrapException("unknown host function " + name);
        }
    }

    private static void hash(Memory mem, long[] args, GasMeter meter, boolean sha256) {
        byte[] data = read(mem, i32(args, 0), i32(args, 1));
        meter.charge(GasSchedule.HASH + GasSchedule.bytesGas(data.length, GasSchedule.PER_32_HASH));
        Bytes32 dig = sha256 ? HashUtils.sha256(Bytes.wrap(data)) : HashUtils.keccak256(Bytes.wrap(data));
        write(mem, i32(args, 2), dig.toArray());
    }

    private static int verify(Memory mem, long[] args) {
        try {
            byte[] hash = read(mem, i32(args, 0), HASH_LEN);
            byte[] sig = read(mem, i32(args, 1), SIG_LEN);
            byte[] pub = read(mem, i32(args, 2), PUB_LEN);
            return Signer.verify(Bytes32.wrap(hash), Signature.decode(sig), PublicKey.fromBytes(pub)) ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int recover(Memory mem, long[] args) {
        try {
            byte[] hash = read(mem, i32(args, 0), HASH_LEN);
            byte[] rs = read(mem, i32(args, 1), RS_LEN);
            byte[] enc = new byte[SIG_LEN];
            System.arraycopy(rs, 0, enc, 0, RS_LEN);
            enc[RS_LEN] = (byte) i32(args, 2);
            PublicKey key = Signer.recoverPublicKey(Bytes32.wrap(hash), Signature.decode(enc));
            write(mem, i32(args, 3), key.toCompressedBytes().toArray());
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int storageGet(Memory mem, long[] args, GasMeter meter, HostContext ctx, Set<Bytes> reads) {
        byte[] key = read(mem, i32(args, 0), i32(args, 1));
        int vptr = i32(args, 2);
        int vcap = i32(args, 3);
        if (vcap < 0) {
            throw new TrapException("invalid bounds");
        }
        byte[] value = ctx.storageGet(key);
        int valueLen = value == null ? 0 : value.length;
        long cost = GasSchedule.STORAGE_GET;
        if (reads.add(Bytes.wrap(key))) {
            cost += GasSchedule.witnessGas(valueLen, ctx.nonEmptySiblings(key));
        }
        meter.charge(cost);
        if (value == null) {
            return -1;
        }
        int n = Math.min(value.length, vcap);
        if (n > 0) {
            write(mem, vptr, java.util.Arrays.copyOf(value, n));
        }
        return value.length;
    }

    private static void storageSet(Memory mem, long[] args, GasMeter meter, HostContext ctx, boolean writes,
            Set<Bytes> reads) {
        byte[] key = read(mem, i32(args, 0), i32(args, 1));
        byte[] value = read(mem, i32(args, 2), i32(args, 3));
        byte[] old = ctx.storageGet(key);
        long cost = GasSchedule.STORAGE_SET + GasSchedule.bytesGas(value.length, GasSchedule.PER_32_STORAGE_SET);
        if (old == null) {
            cost += GasSchedule.STORAGE_SET_NEW_KEY;
        }
        if (!reads.contains(Bytes.wrap(key))) {
            int oldLen = old == null ? 0 : old.length;
            cost += GasSchedule.witnessGas(oldLen, ctx.nonEmptySiblings(key));
        }
        meter.charge(cost);
        requireWrite(writes);
        ctx.storagePut(key, value);
    }

    private static void storageRemove(Memory mem, long[] args, GasMeter meter, HostContext ctx, boolean writes) {
        byte[] key = read(mem, i32(args, 0), i32(args, 1));
        byte[] old = ctx.storageGet(key);
        int oldLen = old == null ? 0 : old.length;
        meter.charge(GasSchedule.STORAGE_REMOVE + GasSchedule.witnessGas(oldLen, ctx.nonEmptySiblings(key)));
        if (old != null) {
            meter.refund(GasSchedule.STORAGE_REMOVE_REFUND);
        }
        requireWrite(writes);
        ctx.storageDelete(key);
    }

    private static int storageScan(Memory mem, long[] args, GasMeter meter, HostContext ctx, Set<Bytes> reads) {
        byte[] prefix = read(mem, i32(args, 0), i32(args, 1));
        int max = i32(args, 2);
        int out = i32(args, 3);
        if (max < 0) {
            throw new TrapException("invalid bounds");
        }
        int limit = Math.min(max, GasSchedule.SCAN_LIMIT);
        List<HostContext.StorageEntry> hits = ctx.storageScan(prefix, limit);
        long cost = GasSchedule.STORAGE_SCAN + (long) GasSchedule.STORAGE_SCAN_PER_ENTRY * hits.size();
        int bytes = 4;
        for (HostContext.StorageEntry hit : hits) {
            if (reads.add(Bytes.wrap(hit.key()))) {
                cost += GasSchedule.witnessGas(hit.value().length, ctx.nonEmptySiblings(hit.key()));
            }
            bytes += 8 + hit.key().length + hit.value().length;
        }
        meter.charge(cost);
        bounds(mem, out, bytes);
        mem.writeI32(out, hits.size());
        int p = out + 4;
        for (HostContext.StorageEntry hit : hits) {
            mem.writeI32(p, hit.key().length);
            p += 4;
            mem.write(p, hit.key(), 0, hit.key().length);
            p += hit.key().length;
            mem.writeI32(p, hit.value().length);
            p += 4;
            mem.write(p, hit.value(), 0, hit.value().length);
            p += hit.value().length;
        }
        return hits.size();
    }

    private static void requireWrite(boolean writes) {
        if (!writes) {
            throw new TrapException("query write");
        }
    }

    private static byte[] read(Memory mem, int ptr, int len) {
        bounds(mem, ptr, len);
        return len == 0 ? new byte[0] : mem.readBytes(ptr, len);
    }

    private static void write(Memory mem, int ptr, byte[] data) {
        bounds(mem, ptr, data.length);
        if (data.length > 0) {
            mem.write(ptr, data, 0, data.length);
        }
    }

    private static void bounds(Memory mem, int ptr, int len) {
        if (ptr < 0 || len < 0) {
            throw new TrapException("invalid bounds");
        }
        long end = (long) ptr + len;
        if (end > (long) mem.pages() * Memory.PAGE_SIZE) {
            throw new TrapException("out of bounds");
        }
    }

    private static int i32(long[] args, int i) {
        return (int) args[i];
    }

    private static long[] i32(int v) {
        return new long[] {v};
    }

    private static long[] i64(long v) {
        return new long[] {v};
    }

    private static long[] none() {
        return null;
    }

    private static FunctionType fun(ValType[] params, ValType[] results) {
        return FunctionType.of(params, results);
    }

    private static ValType[] i32s(int n) {
        ValType[] t = new ValType[n];
        java.util.Arrays.fill(t, ValType.I32);
        return t;
    }
}
