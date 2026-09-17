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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import io.xdag.config.DevnetConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RocksdbInitFailureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void failedOpenLeavesSourceDeadAndReusable() throws Exception {
        DevnetConfig config = new DevnetConfig();
        File storeDir = tmp.newFolder("store");
        config.getNodeSpec().setStoreDir(storeDir.getAbsolutePath());
        // a regular FILE where RocksDB expects the database directory → open fails
        Files.writeString(new File(storeDir, "BROKEN").toPath(), "not a db", StandardCharsets.UTF_8);
        RocksdbKVSource src = new RocksdbKVSource("BROKEN");
        src.setConfig(config);
        assertThrows(RuntimeException.class, src::init);
        assertFalse(src.isAlive());
        src.close(); // must be a no-op, not a crash, after a failed init
        assertFalse(src.isAlive());
    }
}
