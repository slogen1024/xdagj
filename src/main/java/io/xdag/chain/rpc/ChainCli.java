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
package io.xdag.chain.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;

import io.xdag.chain.ext.ChainBlockBuilder;
import io.xdag.chain.ext.ChainConfigExt;
import io.xdag.chain.ext.ExtResult;
import io.xdag.config.Config;
import io.xdag.core.Block;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.keys.ECKeyPair;

/**
 * Telnet {@code chain}. Reads go through {@link ChainApi}. {@code call}, {@code create},
 * {@code deploy}, {@code bond}, {@code unbond}, {@code challenge}, and the long forms of
 * {@code anchor} and {@code claim}, are signed with {@link ChainBlockBuilder} and handed to
 * {@link Signer#submit}.
 */
public final class ChainCli {

    private static final XAmount FEE = XAmount.of(100, XUnit.MILLI_XDAG);

    private ChainCli() {
    }

    /** A key, the nonce to put on the block, and where the signed block plus its chunks go. */
    public record Signer(Config config, ECKeyPair key, long nonce, long timestamp, Consumer<List<Block>> submit) {
    }

    public static String run(ChainApi api, String... args) {
        return run(api, null, args);
    }

    public static String run(ChainApi api, Signer signer, String... args) {
        if (args == null || args.length == 0) {
            return usage();
        }
        if ("activation".equals(args[0])) {
            if (signer == null || signer.config == null) {
                return "chain";
            }
            long height = signer.config.getChainSpec().getChainActivationHeight();
            return height == Long.MAX_VALUE ? "unscheduled " + height : "scheduled " + height;
        }
        if (signs(args)) {
            if (signer == null) {
                return "key";
            }
            try {
                return sign(signer, args);
            } catch (RuntimeException e) {
                return e.getMessage() == null ? "error" : e.getMessage();
            }
        }
        if (api == null) {
            return "chain";
        }
        try {
            return switch (args[0]) {
                case "balance" -> {
                    require(args, 3);
                    ChainApi.Balance row = api.getBalance(address(args[1]), bytes(args[2]), view(args, 3));
                    yield row.view() + " " + row.seq() + " " + row.balance();
                }
                case "state" -> {
                    require(args, 4);
                    ChainApi.State row = api.getState(address(args[1]), address(args[2]), bytes(args[3]), view(args, 4));
                    yield row.view() + " " + row.seq() + " " + row.value();
                }
                case "query" -> {
                    require(args, 5);
                    ChainApi.Query row = api.query(address(args[1]), address(args[2]), (int) number(args[3]),
                            bytes(args[4]), view(args, 5));
                    yield row.view() + " " + row.seq() + " " + row.data();
                }
                case "head" -> {
                    require(args, 2);
                    ChainApi.Head row = api.getHead(address(args[1]));
                    yield row.anchorSeq() + " " + row.anchorHash() + " " + row.appliedSeq() + " " + row.stateRoot();
                }
                case "anchor" -> {
                    require(args, 3);
                    ChainApi.Anchor row = api.getAnchor(address(args[1]), number(args[2]));
                    yield row == null ? "null" : row.seq() + " " + row.status() + " " + row.stateRoot();
                }
                case "receipt" -> {
                    require(args, 2);
                    ChainApi.Receipt row = api.getReceipt(hash(args[1]));
                    yield row == null ? "null"
                            : row.status() + " " + row.gasUsed() + " " + row.seq() + " " + row.confirm() + " "
                                    + row.events().size();
                }
                case "claim" -> {
                    require(args, 4);
                    Bytes dst = args.length > 4 ? address(args[4]) : null;
                    yield api.buildClaim(address(args[1]), number(args[2]), number(args[3]), dst);
                }
                case "send" -> {
                    require(args, 2);
                    yield String.join(" ", api.sendCall(List.of(args).subList(1, args.length)));
                }
                case "subscribe" -> {
                    require(args, 2);
                    yield api.subscribe(args[1]);
                }
                default -> usage();
            };
        } catch (ChainInactive e) {
            return "inactive";
        } catch (RuntimeException e) {
            return e.getMessage() == null ? "error" : e.getMessage();
        }
    }

    public static String usage() {
        return "chain balance|state|query|head|anchor|receipt|claim|send|subscribe|activation|call|create|deploy|bond|unbond|challenge";
    }

    private static boolean signs(String[] args) {
        return switch (args[0]) {
            case "call", "create", "deploy", "bond", "unbond", "challenge" -> true;
            case "anchor" -> args.length > 3;
            case "claim" -> args.length > 5;
            default -> false;
        };
    }

    private static String sign(Signer signer, String[] args) {
        ExtResult<ChainBlockBuilder.Built> built = switch (args[0]) {
            case "call" -> {
                require(args, 7);
                yield ChainBlockBuilder.call(signer.config, signer.timestamp, signer.key, UInt64.valueOf(signer.nonce),
                        address(args[1]), address(args[2]), (int) number(args[3]), number(args[4]),
                        XAmount.of(number(args[5])), FEE, Bytes.wrap(bytes(args[6])));
            }
            case "create" -> {
                require(args, 6);
                yield ChainBlockBuilder.deployNewChain(signer.config, signer.timestamp, signer.key,
                        UInt64.valueOf(signer.nonce), FEE, Bytes.wrap(bytes(args[1])),
                        new ChainConfigExt(number(args[2]), number(args[3]), number(args[4])), Bytes.EMPTY,
                        number(args[5]));
            }
            case "deploy" -> {
                require(args, 4);
                yield ChainBlockBuilder.deployIntoChain(signer.config, signer.timestamp, signer.key,
                        UInt64.valueOf(signer.nonce), address(args[1]), XAmount.ZERO, FEE, Bytes.wrap(bytes(args[2])),
                        null, Bytes.EMPTY, number(args[3]));
            }
            case "bond", "unbond" -> {
                require(args, 3);
                yield ChainBlockBuilder.bond(signer.config, signer.timestamp, signer.key, UInt64.valueOf(signer.nonce),
                        FEE, "unbond".equals(args[0]), address(args[1]), number(args[2]));
            }
            case "anchor" -> {
                require(args, 11);
                yield ChainBlockBuilder.anchor(signer.config, signer.timestamp, signer.key,
                        UInt64.valueOf(signer.nonce), FEE, address(args[1]), number(args[2]), hash(args[3]),
                        hash(args[4]), number(args[5]), number(args[6]), number(args[7]), hash(args[8]), hash(args[9]),
                        Bytes.wrap(bytes(args[10])));
            }
            case "challenge" -> {
                require(args, 5);
                yield ChainBlockBuilder.challenge(signer.config, signer.timestamp, signer.key,
                        UInt64.valueOf(signer.nonce), FEE, number(args[1]), number(args[2]), hash(args[3]),
                        Bytes.wrap(bytes(args[4])));
            }
            case "claim" -> {
                require(args, 8);
                yield ChainBlockBuilder.claim(signer.config, signer.timestamp, signer.key, UInt64.valueOf(signer.nonce),
                        FEE, false, address(args[1]), number(args[2]), number(args[3]), number(args[4]),
                        address(args[5]), hash(args[6]), Bytes.wrap(bytes(args[7])));
            }
            default -> throw new IllegalArgumentException("verb");
        };
        if (!built.isOk()) {
            return built.error().name();
        }
        List<Block> all = new ArrayList<>();
        all.add(built.value().block());
        all.addAll(built.value().chunks());
        if (signer.submit != null) {
            signer.submit.accept(all);
        }
        return built.value().block().getHash().toHexString();
    }

    private static void require(String[] args, int n) {
        if (args.length < n) {
            throw new IllegalArgumentException("params");
        }
    }

    private static ChainView view(String[] args, int index) {
        return ChainView.parse(index < args.length ? args[index] : "confirmed");
    }

    private static Bytes address(String raw) {
        byte[] body = bytes(raw);
        if (body.length != 20) {
            throw new IllegalArgumentException("address");
        }
        return Bytes.wrap(body);
    }

    private static Bytes32 hash(String raw) {
        byte[] body = bytes(raw);
        if (body.length != 32) {
            throw new IllegalArgumentException("hash");
        }
        return Bytes32.wrap(body);
    }

    private static byte[] bytes(String raw) {
        String s = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
        return org.apache.tuweni.bytes.Bytes.fromHexString(s.length() % 2 == 0 ? "0x" + s : "0x0" + s).toArray();
    }

    private static long number(String raw) {
        if (raw.startsWith("0x") || raw.startsWith("0X")) {
            return Long.parseUnsignedLong(raw.substring(2), 16);
        }
        return Long.parseLong(raw);
    }
}
