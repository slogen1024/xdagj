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
package io.xdag.chain.anchor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import io.xdag.chain.l1.ChainL1Batch;
import io.xdag.chain.l1.ChainL1Store;

/** Read-modify-write of one {@code 0x09} list. Reads the committed store, writes the batch. */
public final class TriggerOps {

    private TriggerOps() {
    }

    public static List<TriggerList.Item> read(ChainL1Store store, long height) {
        List<TriggerList.Item> items = TriggerList.decode(store.getTrigger(height));
        return items == null ? List.of() : items;
    }

    public static void append(ChainL1Store store, ChainL1Batch batch, long height, TriggerList.Item item) {
        List<TriggerList.Item> items = new ArrayList<>(read(store, height));
        items.add(item);
        batch.putTrigger(height, TriggerList.encode(items));
    }

    public static void write(ChainL1Batch batch, long height, List<TriggerList.Item> items) {
        if (items.isEmpty()) {
            batch.deleteTrigger(height);
        } else {
            batch.putTrigger(height, TriggerList.encode(items));
        }
    }

    /** Removes the first match. A missing match is a no-op so an apply that never queued stays undo-safe. */
    public static void removeFirst(ChainL1Store store, ChainL1Batch batch, long height, Predicate<TriggerList.Item> match) {
        List<TriggerList.Item> items = new ArrayList<>(read(store, height));
        for (int i = 0; i < items.size(); i++) {
            if (match.test(items.get(i))) {
                items.remove(i);
                write(batch, height, items);
                return;
            }
        }
    }
}
