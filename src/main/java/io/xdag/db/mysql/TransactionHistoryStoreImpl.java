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
package io.xdag.db.mysql;

import com.google.common.collect.Lists;
import io.xdag.core.*;
import io.xdag.crypto.encoding.Base58;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.DruidUtils;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static io.xdag.config.Constants.MIN_GAS;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_INPUT;
import static io.xdag.utils.BasicUtils.hash2Address;
import static io.xdag.utils.BasicUtils.hash2byte;
import static io.xdag.utils.WalletUtils.checkAddress;

@Slf4j
public class TransactionHistoryStoreImpl implements TransactionHistoryStore {

    private static final String SQL_INSERT = "insert into t_transaction_history(faddress,faddresstype,fhash,famount," +
            "ftype,fremark,ftime) values(?,?,?,?,?,?,?)";

    private static final String SQL_QUERY_TXHISTORY_BY_ADDRESS_WITH_TIME = "select faddress,faddresstype,fhash," +
            "famount,ftype,fremark,ftime from t_transaction_history where faddress= ? and ftime >= ? and ftime <= ? order by ftime desc limit ?,?";

    private static final String SQL_QUERY_TXHISTORY_COUNT = "select count(*) from t_transaction_history where faddress=?";

    private static final String SQL_QUERY_TXHISTORY_COUNT_WITH_TIME = "select count(*) from t_transaction_history where faddress=? and ftime >=? and ftime <=?";
    private static final int BLOCK_ADDRESS_FLAG = 0;
    private static final int WALLET_ADDRESS_FLAG = 1;
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_CACHE_SIZE = 50000;
    private final long TX_PAGE_SIZE_LIMIT;
    private Connection connBatch = null;
    private PreparedStatement pstmtBatch = null;
    private int count = 0;

    /**
     * Page count for the most recent {@link #listTxHistoryByAddress} call on the CURRENT thread.
     * RPC requests are processed synchronously on a single Netty worker thread, so the count travels
     * back to the caller via a per-thread value rather than a shared mutable static — the previous
     * {@code public static int totalPage} could be raced on by requests on different worker threads.
     */
    private static final ThreadLocal<Integer> TOTAL_PAGE = ThreadLocal.withInitial(() -> 1);

    /** Page count from the last tx-history query on this thread; defaults to 1 when none has run. */
    public static int getTotalPage() {
        return TOTAL_PAGE.get();
    }

    /** Clears the per-thread page count once the caller has consumed it. */
    public static void resetTotalPage() {
        TOTAL_PAGE.remove();
    }

    public TransactionHistoryStoreImpl(long txPageSizeLimit) {
        this.TX_PAGE_SIZE_LIMIT = txPageSizeLimit;
    }

    @Override
    public boolean saveTxHistory(TxHistory txHistory) {
        Connection conn = null;
        PreparedStatement pstmt = null;

        boolean result = false;
        try {
            conn = DruidUtils.getConnection();
            if (conn != null) {
                pstmt = conn.prepareStatement(SQL_INSERT);

                Address address = txHistory.getAddress();
                String addr = address.getIsAddress() ? Base58.encodeCheck(hash2byte(address.getAddress())) : hash2Address(address.getAddress());
                pstmt.setString(1, addr);
                pstmt.setInt(2, address.getIsAddress() ? WALLET_ADDRESS_FLAG : BLOCK_ADDRESS_FLAG);
                pstmt.setString(3, txHistory.getHash());
                pstmt.setBigDecimal(4, address.getAmount().toDecimal(9, XUnit.XDAG));
                pstmt.setInt(5, address.getType().asByte());
                pstmt.setString(6, txHistory.getRemark() != null ? txHistory.getRemark().trim() : "");
                pstmt.setTimestamp(7,
                        new java.sql.Timestamp(XdagTime.xdagTimestampToMs(txHistory.getTimestamp())));
                result = pstmt.executeUpdate() == 1;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            DruidUtils.close(conn, pstmt);
        }
        return result;
    }

    @Override
    public boolean batchSaveTxHistory(TxHistory txHistory, int... cacheNum) {
        boolean result = false;
        try {
            if (connBatch == null) {
                connBatch = DruidUtils.getConnection();
                if (connBatch != null) {
                    connBatch.setAutoCommit(false);
                }
            }
            if (pstmtBatch == null) {
                if (connBatch != null) {
                    pstmtBatch = connBatch.prepareStatement(SQL_INSERT);
                }
            }
            if (txHistory != null) {
                Address address = txHistory.getAddress();
                String addr = address.getIsAddress() ? Base58.encodeCheck(hash2byte(address.getAddress())) : hash2Address(address.getAddress());
                pstmtBatch.setString(1, addr);
                pstmtBatch.setInt(2, address.getIsAddress() ? WALLET_ADDRESS_FLAG : BLOCK_ADDRESS_FLAG);
                pstmtBatch.setString(3, txHistory.getHash());
                pstmtBatch.setBigDecimal(4, address.getAmount().toDecimal(9, XUnit.XDAG));
                pstmtBatch.setInt(5, address.getType().asByte());
                pstmtBatch.setString(6, txHistory.getRemark() != null ? txHistory.getRemark().trim() : "");
                pstmtBatch.setTimestamp(7,
                        new java.sql.Timestamp(XdagTime.xdagTimestampToMs(txHistory.getTimestamp())));
                pstmtBatch.addBatch();
                count++;
            }
            if (count == (cacheNum.length == 0 ? DEFAULT_CACHE_SIZE : (cacheNum[0] + 1)) || txHistory == null) {
                if (pstmtBatch != null) {
                    pstmtBatch.executeBatch();
                }
                if (connBatch != null) {
                    connBatch.commit();
                }
                result = true;
                count = 0;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            // Roll back the pending batch and reset the counter so the next call starts from a clean slate.
            count = 0;
            if (connBatch != null) {
                try {
                    connBatch.rollback();
                } catch (SQLException re) {
                    log.error(re.getMessage(), re);
                }
            }
            // Drop the (possibly broken) connection and statement; they are re-created on the next call.
            closeBatchResources();
        } finally {
            // End of a load run: release the batch resources (no-op if the catch already closed them).
            if (txHistory == null && (connBatch != null || pstmtBatch != null)) {
                closeBatchResources();
                log.info("The loading is complete, close mysql.");
            }
        }
        return result;
    }

    /**
     * Releases the batch connection and statement, closing the statement BEFORE the connection and
     * guarding each independently so a null (or already-closed) resource cannot trigger an NPE.
     */
    private void closeBatchResources() {
        if (pstmtBatch != null) {
            try {
                pstmtBatch.close();
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            pstmtBatch = null;
        }
        if (connBatch != null) {
            try {
                connBatch.close();
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            connBatch = null;
        }
    }

    @Override
    public List<TxHistory> listTxHistoryByAddress(String address, int page, Object... parameters) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        List<TxHistory> txHistoryList = Lists.newArrayList();
        int totalcount = 0;
        int PAGE_SIZE = DEFAULT_PAGE_SIZE;
        long start = new Date(0).getTime();
        long end = System.currentTimeMillis();
        switch (parameters.length) {
            case 1 -> {
                    int pageSize = Integer.parseInt(parameters[0].toString());
                    PAGE_SIZE = (pageSize > 0 && pageSize <= TX_PAGE_SIZE_LIMIT) ? pageSize : PAGE_SIZE;
                }
            case 2 -> {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    start = sdf.parse(parameters[0].toString()).getTime();
                    end = sdf.parse(parameters[1].toString()).getTime();
                } catch (ParseException e) {
                    start = Long.parseLong(parameters[0].toString());
                    end = Long.parseLong(parameters[1].toString());
                }
            }
            case 3 -> {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    start = sdf.parse(parameters[0].toString()).getTime();
                    end = sdf.parse(parameters[1].toString()).getTime();
                } catch (ParseException e) {
                    start = Long.parseLong(parameters[0].toString());
                    end = Long.parseLong(parameters[1].toString());
                }
                int page_size = Integer.parseInt(parameters[2].toString());
                PAGE_SIZE = (page_size > 0 && page_size <= TX_PAGE_SIZE_LIMIT) ? page_size : PAGE_SIZE;
            }
            default -> {
            }
        }
        try {
            conn = DruidUtils.getConnection();
            if (conn != null) {
                pstmt = conn.prepareStatement(SQL_QUERY_TXHISTORY_COUNT_WITH_TIME);
                pstmt.setString(1, address);
                pstmt.setTimestamp(2, new java.sql.Timestamp(start));
                pstmt.setTimestamp(3, new java.sql.Timestamp(end));
                rs = pstmt.executeQuery();
                if (rs.next()) {
                    totalcount = rs.getInt(1);
                }
                TOTAL_PAGE.set(totalcount < PAGE_SIZE ? 1 : (int) Math.ceil((double) totalcount / PAGE_SIZE));

                pstmt = conn.prepareStatement(SQL_QUERY_TXHISTORY_BY_ADDRESS_WITH_TIME);
                pstmt.setString(1, address);
                pstmt.setTimestamp(2, new java.sql.Timestamp(start));
                pstmt.setTimestamp(3, new java.sql.Timestamp(end));
                pstmt.setInt(4, (page - 1) * PAGE_SIZE);
                pstmt.setInt(5, PAGE_SIZE);
                rs = pstmt.executeQuery();
                while (rs.next()) {
                    TxHistory txHistory = new TxHistory();
                    // Convert address from hash to Bytes32 format
                    String hash = rs.getString(3);
                    txHistory.setHash(hash);
                    XAmount amount = XAmount.of(rs.getBigDecimal(4), XUnit.XDAG);
                    int fType = rs.getInt(5);
                    Address addrObj =
                            new Address(checkAddress(hash) ? BasicUtils.pubAddress2Hash(hash) :
                                    BasicUtils.address2Hash(hash),
                                    XdagField.FieldType.fromByte((byte) fType), amount, checkAddress(hash));
                    txHistory.setAddress(addrObj);
                    txHistory.setRemark(rs.getString(6));
                    txHistory.setTimestamp(rs.getTimestamp(7).getTime());
                    txHistoryList.add(txHistory);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            DruidUtils.close(conn, pstmt, rs);
        }
        return txHistoryList;
    }

    @Override
    public int getTxHistoryCount(String address) {
        int count = 0;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = DruidUtils.getConnection();
            if (conn != null) {
                pstmt = conn.prepareStatement(SQL_QUERY_TXHISTORY_COUNT);
                pstmt.setString(1, address);
                rs = pstmt.executeQuery();
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            DruidUtils.close(conn, pstmt, rs);
        }
        return count;
    }

}
