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
package io.xdag.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CopyDirFailureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void unreadableSourceFileFails() throws Exception {
        File src = tmp.newFolder("src");
        File f = new File(src, "a.sst");
        Files.writeString(f.toPath(), "x", StandardCharsets.UTF_8);
        File dstDir = tmp.newFolder("dst");
        // destination is a directory where a file is expected → FileOutputStream fails
        File clash = new File(dstDir, "a.sst");
        assertTrue(clash.mkdir());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> XdagCli.copyDir(src.getAbsolutePath(), dstDir.getAbsolutePath()));
        assertTrue(e.getMessage(), e.getMessage().contains("a.sst"));
    }

    @Test
    public void copiesNestedDirectories() throws Exception {
        File src = tmp.newFolder("s");
        File nested = new File(src, "n");
        assertTrue(nested.mkdir());
        Files.writeString(new File(nested, "f").toPath(), "hello", StandardCharsets.UTF_8);
        File dst = new File(tmp.getRoot(), "d/e");
        XdagCli.copyDir(src.getAbsolutePath(), dst.getAbsolutePath());
        assertEquals("hello", Files.readString(new File(dst, "n/f").toPath(), StandardCharsets.UTF_8));
    }
}
