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

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Log;

/**
 * The outcome of executing a single EVM message (a contract deployment or a call).
 *
 * @param success         {@code true} iff the frame halted in {@code COMPLETED_SUCCESS}
 * @param returnData      the bytes returned by RETURN (or the revert payload on failure)
 * @param gasUsed         {@code gasLimit - remainingGas}
 * @param logs            the events emitted (LOG0..LOG4) during the message
 * @param revertReason    the REVERT reason payload, if the message reverted
 * @param createdContract the new contract address for a successful deployment
 */
public record XdagExecutionResult(
        boolean success,
        Bytes returnData,
        long gasUsed,
        List<Log> logs,
        Optional<Bytes> revertReason,
        Optional<Address> createdContract) {
}
