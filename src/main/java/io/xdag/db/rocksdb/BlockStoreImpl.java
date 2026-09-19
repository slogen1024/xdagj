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

package io.xdag.db.rocksdb;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.google.common.collect.Lists;
import io.xdag.core.*;
import io.xdag.db.BlockStore;
import io.xdag.db.execption.DeserializationException;
import io.xdag.db.execption.SerializationException;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.BlockUtils;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.xdag.utils.BytesUtils.equalBytes;

/**
 * The node's block store over RocksDB.
 *
 * <p>Block sums (the {@code SUMS_BLOCK_INFO} keys, one 4 KB array per key) are kept in memory and
 * written back in batches; {@link #flushSums()} documents the triggers and the loss bound. The
 * cache is bounded: reads never populate it, and after every flush it keeps only the arrays that are
 * still dirty plus the four keys of the last saved block. There is one key per deepest bucket of
 * 2^24 XDAG ticks of 1/1024 s (about 4.55 h) plus three shallower levels — about 1,900 keys a year at 4 KB each, so an
 * unbounded cache would retain on the order of 60 MB after a full historical sync.
 */
@Slf4j
public class BlockStoreImpl implements BlockStore {

    private final Kryo kryo;

    /**
     * <prefix-hash,value> eg:<diff-hash,blockDiff>
     */
    private final KVSource<byte[], byte[]> indexSource;
    /**
     * <prefix-time-hash,hash>
     */
    private final KVSource<byte[], byte[]> timeSource;
    /**
     * <hash,rawData>
     */
    private final KVSource<byte[], byte[]> blockSource;
    private final KVSource<byte[], byte[]> txHistorySource;

    /**
     * The store wiring every running node has always used: the raw-block source is the database
     * NAMED {@link DatabaseName#TIME} and the time index is the one NAMED {@link DatabaseName#BLOCK}.
     * That is an argument-order slip in the original {@code Kernel} wiring which has long since
     * become the on-disk layout of every existing node (it also puts {@code RocksdbFactory}'s fixed
     * 9-byte prefix extractor, attached by database name, on the raw-block source); renaming the
     * directories to match their contents would need a data migration and is not what this does.
     *
     * <p>Every offline tool that opens a node's store MUST go through this method, so that it reads
     * back exactly the layout the node wrote. The tests use it too, so that what they exercise is
     * the production layout rather than the signature order.
     */
    public static BlockStoreImpl forNode(DatabaseFactory dbFactory) {
        return new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.TXHISTORY));
    }

    public BlockStoreImpl(
            KVSource<byte[], byte[]> index,
            KVSource<byte[], byte[]> time,
            KVSource<byte[], byte[]> block,
            KVSource<byte[], byte[]> txHistory) {
        this.indexSource = index;
        this.timeSource = time;
        this.blockSource = block;
        this.txHistorySource = txHistory;
        this.kryo = new Kryo();
        kryoRegister();
    }

    private void kryoRegister() {
        kryo.setReferences(false);
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        kryo.register(BigInteger.class);
        kryo.register(byte[].class);
        kryo.register(BlockInfo.class);
        kryo.register(XdagStats.class);
        kryo.register(XdagTopStatus.class);
        kryo.register(SnapshotInfo.class);
        kryo.register(UInt64.class);
        kryo.register(XAmount.class);
    }

    private byte[] serialize(final Object obj) throws SerializationException {
        synchronized (kryo) {
            try {
                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                final Output output = new Output(outputStream);
                kryo.writeObject(output, obj);
                output.flush();
                output.close();
                return outputStream.toByteArray();
            } catch (final IllegalArgumentException | KryoException exception) {
                throw new SerializationException(exception.getMessage(), exception);
            }
        }
    }

    private Object deserialize(final byte[] bytes, Class<?> type) throws DeserializationException {
        synchronized (kryo) {
            try {
                final ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                final Input input = new Input(inputStream);
                return kryo.readObject(input, type);
            } catch (final IllegalArgumentException | KryoException | NullPointerException exception) {
                log.debug("Deserialize data:{}", Hex.toHexString(bytes));
                throw new DeserializationException(exception.getMessage(), exception);
            }
        }
    }

    public void start() {
        indexSource.init();
        timeSource.init();
        blockSource.init();
        txHistorySource.init();
    }

    @Override
    public void stop() {
        flushSums();
        indexSource.close();
        timeSource.close();
        blockSource.close();
        txHistorySource.close();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    public void reset() {
        sumsCache.clear();
        dirtySums.clear();
        sumsSinceFlush.set(0);
        lastBlockKeys = List.of();
        indexSource.reset();
        timeSource.reset();
        blockSource.reset();
        txHistorySource.reset();
    }

    /**
     * Persists the stats only. It deliberately does NOT flush the sums: {@code BlockchainImpl.tryToConnect}
     * calls this once per import, so a flush here would write the sums per block again.
     */
    public void saveXdagStatus(XdagStats status) {
        byte[] value = null;
        try {
            value = serialize(status);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        indexSource.put(new byte[]{SETTING_STATS}, value);
    }

    @Override
    public void saveTxHistoryToRocksdb(TxHistory txHistory, int id) {
        byte[] remark = new byte[]{};
        if (txHistory.getRemark() != null) {
            remark = txHistory.getRemark().getBytes(StandardCharsets.UTF_8);
        }
        byte[] isWalletAddress = new byte[]{(byte) (txHistory.getAddress().getIsAddress() ? 1 : 0)};
        byte[] key = BytesUtils.merge(TX_HISTORY, BytesUtils.merge(txHistory.getAddress().getAddress().toArray(),
                BasicUtils.address2Hash(txHistory.getHash()).toArray(), BytesUtils.intToBytes(id, true)));
        // key: 0xa0 + address hash + txHashLow + id
        byte[] value;
        value = BytesUtils.merge(txHistory.getAddress().getType().asByte(), BytesUtils.merge(isWalletAddress,
                txHistory.getAddress().getAddress().toArray(),
                BasicUtils.address2Hash(txHistory.getHash()).toArray(),
                txHistory.getAddress().getAmount().toXAmount().toBytes().reverse().toArray(),
                BytesUtils.longToBytes(txHistory.getTimestamp(), true),
                BytesUtils.longToBytes(remark.length, true),
                remark));
        // value: type  +  isWalletAddress +address hash +txHashLow+ amount + timestamp + remark_length + remark
        txHistorySource.put(key, value);
        log.info("MySQL write exception, transaction history stored in Rocksdb. {}", txHistory);
    }

    public List<TxHistory> getAllTxHistoryFromRocksdb() {
        List<TxHistory> res = Lists.newArrayList();
        Set<byte[]> Keys = txHistorySource.keys();
        for (byte[] key : Keys) {
            byte[] txHistoryBytes = txHistorySource.get(key);
            byte type = BytesUtils.subArray(txHistoryBytes, 0, 1)[0];
            boolean isAddress = BytesUtils.subArray(txHistoryBytes, 1, 1)[0] == 1;
            XdagField.FieldType fieldType = XdagField.FieldType.fromByte(type);
            Bytes32 addresshashlow = Bytes32.wrap(BytesUtils.subArray(txHistoryBytes, 2, 32));
            Bytes32 txhashlow = Bytes32.wrap(BytesUtils.subArray(txHistoryBytes, 34, 32));
            String hash = BasicUtils.hash2Address(txhashlow);
            XAmount amount =
                    XAmount.ofXAmount(Bytes.wrap(BytesUtils.subArray(txHistoryBytes, 66, 8)).reverse().toLong());
            long timestamp = BytesUtils.bytesToLong(BytesUtils.subArray(txHistoryBytes, 74, 8), 0, true);
            Address address = new Address(addresshashlow, fieldType, amount, isAddress);
            long remarkLength = BytesUtils.bytesToLong(BytesUtils.subArray(txHistoryBytes, 82, 8), 0, true);
            String remark = null;
            if (remarkLength != 0) {
                remark = new String(BytesUtils.subArray(txHistoryBytes, 90, (int) remarkLength),
                        StandardCharsets.UTF_8).trim();
            }
            res.add(new TxHistory(address, hash, timestamp, remark));
        }
        return res;
    }

    public void deleteAllTxHistoryFromRocksdb() {
        for (byte[] key : txHistorySource.keys()) {
            try {
                txHistorySource.delete(key);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }


    // 状态也是存在区块里面的
    public XdagStats getXdagStatus() {
        XdagStats status = null;
        byte[] value = indexSource.get(new byte[]{SETTING_STATS});
        if (value == null) {
            return null;
        }
        try {
            status = (XdagStats) deserialize(value, XdagStats.class);
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
        }
        return status;
    }

    public void saveXdagTopStatus(XdagTopStatus status) {
        byte[] value = null;
        try {
            value = serialize(status);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        indexSource.put(new byte[]{SETTING_TOP_STATUS}, value);
    }

    // pretop状态
    public XdagTopStatus getXdagTopStatus() {
        XdagTopStatus status = null;
        byte[] value = indexSource.get(new byte[]{SETTING_TOP_STATUS});
        if (value == null) {
            return null;
        }
        try {
            status = (XdagTopStatus) deserialize(value, XdagTopStatus.class);
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
        }
        return status;
    }

    // 存储block的过程
    public void saveBlock(Block block) {
        long time = block.getTimestamp();
        // Fix: time中只拿key的后缀（hashlow）就够了，值可以不存
        timeSource.put(BlockUtils.getTimeKey(time, block.getHashLow()), new byte[]{0});
        blockSource.put(block.getHashLow().toArray(), block.getXdagBlock().getData().toArray());
        saveBlockSums(block);
        //我们读取的fee，确保只能是我们自己节点执行该区块后赋的值才行，此时属于还没执行，统一置为零
        block.getInfo().setFee(XAmount.ZERO);
        saveBlockInfo(block.getInfo());
    }

    public void saveOurBlock(int index, byte[] hashlow) {
        indexSource.put(BlockUtils.getOurKey(index, hashlow), new byte[]{0});
    }

    public Bytes getOurBlock(int index) {
        AtomicReference<Bytes> blockHashLow = new AtomicReference<>(Bytes.of(0));
        fetchOurBlocks(pair -> {
            int keyIndex = pair.getKey();
            if (keyIndex == index) {
                if (pair.getValue() != null && pair.getValue().getHashLow() != null) {
                    blockHashLow.set(pair.getValue().getHashLow());
                    return Boolean.TRUE;
                } else {
                    return Boolean.FALSE;
                }
            }
            return Boolean.FALSE;
        });
        return blockHashLow.get();
    }

    public int getKeyIndexByHash(Bytes32 hashlow) {
        AtomicInteger keyIndex = new AtomicInteger(-1);
        fetchOurBlocks(pair -> {
            Block block = pair.getValue();
            if (hashlow.equals(block.getHashLow())) {
                int index = pair.getKey();
                keyIndex.set(index);
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
        return keyIndex.get();
    }

    public void removeOurBlock(byte[] hashlow) {
        fetchOurBlocks(pair -> {
            Block block = pair.getValue();
            if (equalBytes(hashlow, block.getHashLow().toArray())) {
                int index = pair.getKey();
                indexSource.delete(BlockUtils.getOurKey(index, hashlow));
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }

    public void fetchOurBlocks(Function<Pair<Integer, Block>, Boolean> function) {
        indexSource.fetchPrefix(new byte[]{OURS_BLOCK_INFO}, pair -> {
            int index = BlockUtils.getOurIndex(pair.getKey());
            assert BlockUtils.getOurHash(pair.getKey()) != null;
            Block block = getBlockInfoByHash(Bytes32.wrap(Objects.requireNonNull(BlockUtils.getOurHash(pair.getKey()))));
            if (function.apply(Pair.of(index, block))) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }

    /**
     * Sums are updated in memory per saved block and written back in batches. The triggers are every
     * this many saved blocks, the first saved block of a new deepest bucket, {@code Kernel.testStop()}
     * and {@link #stop()} — see {@link #flushSums()}. {@link #saveXdagStatus(XdagStats)} is NOT one.
     */
    public static final int SUMS_FLUSH_EVERY = 256;
    /** The arrays touched since the last flush plus the last saved block's four keys; the class Javadoc gives the bound. */
    private final ConcurrentHashMap<String, MutableBytes> sumsCache = new ConcurrentHashMap<>();
    private final Set<String> dirtySums = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sumsSinceFlush = new AtomicInteger();
    /** The four sums keys of the block last passed to {@link #saveBlockSums}; index 3 is its deepest bucket. */
    private volatile List<String> lastBlockKeys = List.of();

    /**
     * Writes every dirty sums array to the index and drops the clean entries the next block will not
     * reuse. Triggers: every {@value #SUMS_FLUSH_EVERY} saved blocks, the first saved block whose
     * deepest bucket differs from the previous saved block's, {@code Kernel.testStop()} before its
     * final stats save, and {@link #stop()} (a lifecycle path the node does not currently take:
     * {@code Kernel.testStop} calls {@link #flushSums()} explicitly, so do not delete that call on the
     * assumption that {@code stop()} covers shutdown). {@link #saveXdagStatus(XdagStats)} runs once per
     * import and therefore is not a trigger. Safe to call with nothing dirty.
     *
     * <p>Loss bound: a crash — any stop that reaches none of those triggers — loses the contributions
     * of the blocks saved since the last flush: fewer than {@value #SUMS_FLUSH_EVERY} of them, all in
     * the deepest bucket that was growing at the time, because the bucket roll flushes before the
     * first block of the next bucket is added. That loss is permanent: nothing re-sums blocks already
     * on disk, so the bucket under-reports from then on and the legacy sync protocol, which compares
     * sums to choose the ranges it requests, keeps drilling into and re-requesting that range on every
     * sync round. The bucket-roll trigger confines the exposure to the newest bucket's tail; without
     * it a quiet node (fewer than {@value #SUMS_FLUSH_EVERY} blocks in hours) could hold a completed
     * bucket dirty indefinitely. Sums are advisory — they steer those range requests, never consensus.
     */
    @Override
    public void flushSums() {
        // Keys that could not be written stay dirty; collected and re-added after the loop so the
        // weakly consistent iterator is never asked to revisit a key re-added during iteration.
        List<String> failed = new ArrayList<>();
        for (String key : dirtySums) {
            // Un-mark before writing: a putSums racing with this write re-marks the key and the next
            // flush rewrites it, so a concurrent update is written twice rather than lost.
            dirtySums.remove(key);
            MutableBytes sums = sumsCache.get(key);
            if (sums == null || !writeSums(key, sums)) {
                failed.add(key);
            }
        }
        dirtySums.addAll(failed);
        // May swallow increments made concurrently with this flush; acceptable, saves run under the blockchain lock.
        sumsSinceFlush.set(0);
        // Bound the cache: keep what is still dirty and the last block's four keys, which the next block almost always reuses.
        List<String> hot = lastBlockKeys;
        sumsCache.keySet().removeIf(key -> !dirtySums.contains(key) && !hot.contains(key));
    }

    /** Returns false when the array could not be serialized; a null put would DELETE the key, so nothing is written then. */
    private boolean writeSums(String key, Bytes sums) {
        byte[] value;
        try {
            value = serialize(sums.toArray());
        } catch (SerializationException e) {
            log.error("sums {} not written, the index keeps its previous value: {}", key, e.getMessage(), e);
            return false;
        }
        indexSource.put(BytesUtils.merge(SUMS_BLOCK_INFO, key.getBytes(StandardCharsets.UTF_8)), value);
        return true;
    }

    @Override
    public void saveBlockSums(Block block) {
        long size = 512;
        long sum = block.getXdagBlock().getSum();
        long time = block.getTimestamp();
        List<String> filename = FileUtils.getFileName(time);
        List<String> previous = lastBlockKeys;
        lastBlockKeys = filename;
        // Bucket roll: the stream of saved blocks moved to another deepest bucket, so write the one
        // it left now rather than letting a quiet node hold it dirty for hours.
        if (!previous.isEmpty() && !previous.get(3).equals(filename.get(3))) {
            flushSums();
        }
        for (int i = 0; i < filename.size(); i++) {
            updateSum(filename.get(i), sum, size, (time >> (40 - 8 * i)) & 0xff);
        }
        if (sumsSinceFlush.incrementAndGet() >= SUMS_FLUSH_EVERY) {
            flushSums();
        }
    }

    @Override
    public MutableBytes getSums(String key) {
        MutableBytes cached = sumsCache.get(key);
        if (cached != null) {
            return cached.mutableCopy();
        }
        byte[] value = indexSource.get(BytesUtils.merge(SUMS_BLOCK_INFO, key.getBytes(StandardCharsets.UTF_8)));
        if (value == null) {
            return null;
        }
        try {
            // Reads do not populate the cache — only putSums does — so the legacy sync protocol's
            // loadSum traffic over historical buckets cannot grow it.
            return MutableBytes.wrap((byte[]) deserialize(value, byte[].class));
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void putSums(String key, Bytes sums) {
        // Mark dirty BEFORE installing the value: flushSums' eviction tests the dirty set, so the
        // opposite order could evict a freshly put array in the window between the two statements.
        dirtySums.add(key);
        sumsCache.put(key, sums.mutableCopy());
    }

    public void updateSum(String key, long sum, long size, long index) {
        MutableBytes sums = getSums(key);
        if (sums == null) {
//            sums = new byte[4096];
            sums = MutableBytes.create(4096);
//            System.arraycopy(BytesUtils.longToBytes(sum, true), 0, sums, (int) (16 * index), 8);
            sums.set((int) (16 * index), Bytes.wrap(BytesUtils.longToBytes(sum, true)));
//            System.arraycopy(BytesUtils.longToBytes(size, true), 0, sums, (int) (index * 16 + 8), 8);
            sums.set((int) (index * 16 + 8), Bytes.wrap(BytesUtils.longToBytes(size, true)));
            putSums(key, sums);
        } else {
            // size + sum
//            byte[] data = ArrayUtils.subarray(sums, 16 * (int)index, 16 * (int)index + 16);
            MutableBytes data = sums.slice(16 * (int) index, 16).mutableCopy();
//            sum += BytesUtils.bytesToLong(data, 0, true);
            sum += data.getLong(0, ByteOrder.LITTLE_ENDIAN);
//            size += BytesUtils.bytesToLong(data, 8, true);
            size += data.getLong(8, ByteOrder.LITTLE_ENDIAN);
//            System.arraycopy(BytesUtils.longToBytes(sum, true), 0, data, 0, 8);
            data.set(0, Bytes.wrap(BytesUtils.longToBytes(sum, true)));
//            System.arraycopy(BytesUtils.longToBytes(size, true), 0, data, 8, 8);
            data.set(8, Bytes.wrap(BytesUtils.longToBytes(size, true)));
//            System.arraycopy(data, 0, sums, 16 * (int)index, 16);
            sums.set(16 * (int) index, data.slice(0, 16));
            putSums(key, sums);
        }
    }

    public int loadSum(long starttime, long endtime, MutableBytes sums) {
        int level;
        String key;
        endtime -= starttime;

        if (endtime == 0 || (endtime & (endtime - 1)) != 0) {
            return -1;
        }
//        if (endtime == 0 || (endtime & (endtime - 1)) != 0 || (endtime & 0xFFFEEEEEEEEFFFFFL) != 0) return -1;

        for (level = -6; endtime != 0; level++, endtime >>= 4) {
        }

        List<String> files = FileUtils.getFileName((starttime) & 0xffffff000000L);

        if (level < 2) {
            key = files.get(3);
        } else if (level < 4) {
            key = files.get(2);
        } else if (level < 6) {
            key = files.get(1);
        } else {
            key = files.getFirst();
        }

        Bytes buf = getSums(key);
        if (buf == null) {
//            Arrays.fill(sums, (byte)0);
            sums.fill((byte) 0);
            return 1;
        }
        long size = 0;
        long sum = 0;
        if ((level & 1) != 0) {
//            Arrays.fill(sums, (byte)0);
            sums.fill((byte) 0);
            for (int i = 1; i <= 256; i++) {
//                long totalsum = BytesUtils.bytesToLong(buf, i * 16, true);
                long totalsum = buf.getLong((i-1) * 16, ByteOrder.LITTLE_ENDIAN);
                sum += totalsum;
//                long totalsize = BytesUtils.bytesToLong(buf, i * 16 + 8, true);
                long totalsize = buf.getLong((i-1) * 16 + 8, ByteOrder.LITTLE_ENDIAN);
                size += totalsize;
                if (i % 16 == 0) {
//                    System.arraycopy(BytesUtils.longToBytes(sum, true), 0, sums, i - 16, 8);
                    sums.set(i - 16, Bytes.wrap(BytesUtils.longToBytes(sum, true)));
//                    System.arraycopy(BytesUtils.longToBytes(size, true), 0, sums, i - 8, 8);
                    sums.set(i - 8, Bytes.wrap(BytesUtils.longToBytes(size, true)));
                    sum = 0;
                    size = 0;
                }
            }
        } else {
            long index = (starttime >> (level + 4) * 4) & 0xf0;
//            System.arraycopy(buf, (int) (index * 16), sums, 0, 16 * 16);
            sums.set(0, buf.slice((int) index * 16, 16 * 16));
        }
        return 1;
    }

    public void saveBlockInfo(BlockInfo blockInfo) {
        byte[] value = null;
        try {
            value = serialize(blockInfo);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        indexSource.put(BytesUtils.merge(HASH_BLOCK_INFO, blockInfo.getHashlow()), value);
        // 如果区块是主块的话顺便保存对应的高度信息
        // TODO: paulochen 如果回滚了，对应高度的键值对该怎么更新(直接让其height=0的区块覆盖)
//        if (blockInfo.getHeight() > 0) {
        indexSource.put(BlockUtils.getHeight(blockInfo.getHeight()), blockInfo.getHashlow());
//        } else {
//            indexSource.get()
//        }
    }

    public boolean hasBlock(Bytes32 hashlow) {
        return blockSource.get(hashlow.toArray()) != null;
    }

    public boolean hasBlockInfo(Bytes32 hashlow) {
        return indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hashlow.toArray())) != null;
    }

    public List<Block> getBlocksUsedTime(long startTime, long endTime) {
        List<Block> res = Lists.newArrayList();
        long time = startTime;
        while (time < endTime) {
            List<Block> blocks = getBlocksByTime(time);
            // The stride can carry `time` past Long.MAX_VALUE, and the wrapped, hugely negative
            // result still satisfies `time < endTime` -- the loop would then run for about 2^48
            // more iterations before wrapping back, which is minutes of a pinned core. Stop at the
            // boundary instead. Reachable from any caller that passes an extreme startTime,
            // including the peer-supplied one behind BLOCKS_REQUEST.
            long next = time + 0x10000;
            if (next <= time) {
                break;
            }
            time = next;
            if (CollectionUtils.isEmpty(blocks)) {
                continue;
            }
            res.addAll(blocks);
        }
        return res;
    }

    public List<Block> getBlocksByTime(long startTime) {
        List<Block> blocks = Lists.newArrayList();
        byte[] keyPrefix = BlockUtils.getTimeKey(startTime, null);
        List<byte[]> keys = timeSource.prefixKeyLookup(keyPrefix);
        for (byte[] bytes : keys) {
            // 1 + 8 : prefix + time
            byte[] hash = BytesUtils.subArray(bytes, 1 + 8, 32);
            Block block = getBlockByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    // ADD: 通过高度获取区块
    public Block getBlockByHeight(long height) {
        byte[] hashlow = indexSource.get(BlockUtils.getHeight(height));
        if (hashlow == null) {
            return null;
        }
        return getBlockByHash(Bytes32.wrap(hashlow), false);
    }

    public Block getBlockByHash(Bytes32 hashlow, boolean isRaw) {
        if (isRaw) {
            return getRawBlockByHash(hashlow);
        }
        return getBlockInfoByHash(hashlow);
    }

    public Block getRawBlockByHash(Bytes32 hashlow) {
        Block block = getBlockInfoByHash(hashlow);
        if (block == null) {
            return null;
        }
//        log.debug("Data:{}",Hex.toHexString(blockSource.get(hashlow)));
        // 没有源数据
        if (blockSource.get(hashlow.toArray()) == null) {
//            log.error("No block origin data");
            return null;
        }
        block.setXdagBlock(new XdagBlock(blockSource.get(hashlow.toArray())));
        block.setParsed(false);
        block.parse();
        return block;
    }

    public Block getBlockInfoByHash(Bytes32 hashlow) {
        if (!hasBlockInfo(hashlow)) {
            return null;
        }
        BlockInfo blockInfo = null;
        byte[] value = indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hashlow.toArray()));
        if (value == null) {
            return null;
        } else {
            try {
                blockInfo = (BlockInfo) deserialize(value, BlockInfo.class);
            } catch (DeserializationException e) {
                log.error("hash low:{}", hashlow.toHexString());
                log.error("can't deserialize data:{}", Hex.toHexString(value));
                log.error(e.getMessage(), e);
            }
        }
        return new Block(blockInfo);
//        if (blockSource.get(hashlow.toArray()) == null) {
////            log.error("No block origin data");
//            return block;
//        } else {
//            block.setXdagBlock(new XdagBlock(blockSource.get(hashlow.toArray())));
//            return block;
//        }
    }

    public BlockInfo getBlockInfo(Bytes32 hashlow) {
        if (!hasBlockInfo(hashlow)) {
            return null;
        }
        BlockInfo blockInfo = null;
        byte[] value = indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hashlow.toArray()));
        if (value == null) {
            return null;
        } else {
            try {
                blockInfo = (BlockInfo) deserialize(value, BlockInfo.class);
            } catch (DeserializationException e) {
                log.error("hash low:{}", hashlow.toHexString());
                log.error("can't deserialize data:{}", Hex.toHexString(value));
                log.error(e.getMessage(), e);
            }
            return blockInfo;
        }
    }

    public boolean isSnapshotBoot() {
        byte[] data = indexSource.get(new byte[]{SNAPSHOT_BOOT});
        if (data == null) {
            return false;
        } else {
            int res = BytesUtils.bytesToInt(data, 0, false);
            return res == 1;
        }
    }

    public void setSnapshotBoot() {
        indexSource.put(new byte[]{SNAPSHOT_BOOT}, BytesUtils.intToBytes(1, false));
    }

    @Override
    public long getLastCompletedMain() {
        byte[] data = indexSource.get(new byte[]{LAST_COMPLETED_MAIN});
        if (data == null || data.length != 8) {
            return -1L;
        }
        return BytesUtils.bytesToLong(data, 0, false);
    }

    @Override
    public void saveLastCompletedMain(long height) {
        indexSource.put(new byte[]{LAST_COMPLETED_MAIN}, BytesUtils.longToBytes(height, false));
    }

    @Override
    public long getMainInFlight() {
        byte[] data = indexSource.get(new byte[]{MAIN_IN_FLIGHT});
        if (data == null) {
            return -1L;
        }
        if (data.length == 8) {
            return BytesUtils.bytesToLong(data, 0, false); // legacy record, written without an op byte
        }
        if (data.length != 9) {
            return -1L;
        }
        return BytesUtils.bytesToLong(data, 1, false);
    }

    @Override
    public int getMainInFlightOp() {
        byte[] data = indexSource.get(new byte[]{MAIN_IN_FLIGHT});
        if (data == null) {
            return 0;
        }
        if (data.length == 8) {
            return IN_FLIGHT_SET_MAIN; // legacy record: no op byte, so assume setMain (see the interface Javadoc)
        }
        if (data.length != 9) {
            return 0;
        }
        return data[0];
    }

    @Override
    public void saveMainInFlight(long height, byte op) {
        indexSource.put(new byte[]{MAIN_IN_FLIGHT}, BytesUtils.merge(op, BytesUtils.longToBytes(height, false)));
    }

    @Override
    public void clearMainInFlight() {
        indexSource.delete(new byte[]{MAIN_IN_FLIGHT});
    }

    public void savePreSeed(byte[] preseed) {
        indexSource.put(new byte[]{SNAPSHOT_PRESEED}, preseed);
    }

    public byte[] getPreSeed() {
        return indexSource.get(new byte[]{SNAPSHOT_PRESEED});
    }

}

