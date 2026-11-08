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
package io.xdag.rpc.server.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import io.xdag.chain.rpc.ChainApi;
import io.xdag.chain.rpc.ChainInactive;
import io.xdag.chain.rpc.ChainView;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;

/**
 * {@code chain_*} on the same HTTP JSON-RPC server as {@code xdag_*}. Addresses are 20-byte hex.
 */
public final class ChainRequestHandler implements JsonRpcRequestHandler {

    private static final Set<String> METHODS = Set.of(
            "chain_sendCall",
            "chain_query",
            "chain_getReceipt",
            "chain_getAnchor",
            "chain_getHead",
            "chain_getState",
            "chain_getProof",
            "chain_getBalance",
            "chain_estimateGas",
            "chain_subscribe",
            "chain_buildClaim");

    private final ChainApi api;

    public ChainRequestHandler(ChainApi api) {
        this.api = api;
    }

    @Override
    public boolean supportsMethod(String methodName) {
        return methodName != null && METHODS.contains(methodName);
    }

    @Override
    public Object handle(JsonRpcRequest request) throws JsonRpcException {
        Object[] params = request.getParams() == null ? new Object[0] : request.getParams();
        try {
            return switch (request.getMethod()) {
                case "chain_getBalance" -> api.getBalance(address(params, 0), bytes(params, 1), view(params, 2));
                case "chain_getState" ->
                    api.getState(address(params, 0), address(params, 1), bytes(params, 2), view(params, 3));
                case "chain_query" -> api.query(address(params, 0), address(params, 1), selector(params, 2),
                        bytes(params, 3), view(params, 4));
                case "chain_estimateGas" ->
                    api.estimateGas(address(params, 0), address(params, 1), selector(params, 2), bytes(params, 3));
                case "chain_getProof" ->
                    api.getProof(address(params, 0), address(params, 1), bytes(params, 2), view(params, 3));
                case "chain_getAnchor" -> api.getAnchor(address(params, 0), longArg(params, 1));
                case "chain_getHead" -> api.getHead(address(params, 0));
                case "chain_getReceipt" -> api.getReceipt(hash(params, 0));
                case "chain_buildClaim" -> api.buildClaim(address(params, 0), longArg(params, 1), longArg(params, 2),
                        params.length > 3 && params[3] != null ? address(params, 3) : null);
                case "chain_sendCall" -> api.sendCall(strings(params, 0));
                case "chain_subscribe" -> api.subscribe(params.length == 0 || params[0] == null ? "" : params[0].toString());
                default -> throw JsonRpcException.methodNotFound(request.getMethod());
            };
        } catch (ChainInactive e) {
            throw JsonRpcException.serverError("inactive");
        } catch (IllegalArgumentException e) {
            throw JsonRpcException.invalidParams(e.getMessage() == null ? "params" : e.getMessage());
        } catch (IllegalStateException e) {
            throw JsonRpcException.serverError(e.getMessage() == null ? "state" : e.getMessage());
        }
    }

    private static ChainView view(Object[] params, int i) {
        need(params, i);
        return ChainView.parse(params[i].toString());
    }

    private static Bytes address(Object[] params, int i) {
        byte[] raw = bytes(params, i);
        if (raw.length != 20) {
            throw new IllegalArgumentException("address");
        }
        return Bytes.wrap(raw);
    }

    private static Bytes32 hash(Object[] params, int i) {
        byte[] raw = bytes(params, i);
        if (raw.length != 32) {
            throw new IllegalArgumentException("hash");
        }
        return Bytes32.wrap(raw);
    }

    private static byte[] bytes(Object[] params, int i) {
        need(params, i);
        String s = params[i].toString();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        try {
            return org.apache.commons.codec.binary.Hex.decodeHex(s);
        } catch (org.apache.commons.codec.DecoderException e) {
            throw new IllegalArgumentException("hex");
        }
    }

    private static int selector(Object[] params, int i) {
        return (int) longArg(params, i);
    }

    private static long longArg(Object[] params, int i) {
        need(params, i);
        Object raw = params[i];
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String s = raw.toString();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseUnsignedLong(s.substring(2), 16);
        }
        return Long.parseLong(s);
    }

    private static List<String> strings(Object[] params, int i) {
        need(params, i);
        Object raw = params[i];
        if (raw instanceof String s) {
            return List.of(s);
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(item.toString());
            }
            return out;
        }
        if (raw instanceof Object[] arr) {
            List<String> out = new ArrayList<>(arr.length);
            for (Object item : arr) {
                out.add(item.toString());
            }
            return out;
        }
        throw new IllegalArgumentException("blocks");
    }

    private static void need(Object[] params, int i) {
        if (i >= params.length || params[i] == null) {
            throw new IllegalArgumentException("params");
        }
    }
}
