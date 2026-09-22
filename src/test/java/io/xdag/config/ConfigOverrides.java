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

package io.xdag.config;

import com.typesafe.config.ConfigFactory;
import java.util.function.Supplier;

/**
 * Conf-key overrides for config tests.
 *
 * <p>There is no in-repo way to hand {@link AbstractConfig} a parsed config object: it always
 * loads its own via {@code ConfigFactory.load(getConfigName())}, from the constructor. A JVM
 * system property is what that load overlays above the resource file, which is why every config
 * test drives its overrides this way rather than through {@code ConfigFactory.parseString}.
 */
final class ConfigOverrides {

    private ConfigOverrides() {
    }

    /**
     * Sets a JVM system property, invalidates Typesafe's config caches so the next
     * {@code ConfigFactory.load(...)} overlays it above the resource file, runs {@code body},
     * then always clears the property afterwards (and invalidates the caches again) — even if
     * {@code body} throws.
     *
     * <p>Both invalidations matter: without the first the body sees a cached config that predates
     * the property, and without the second the property leaks into every later test in the JVM.
     */
    static <T> T withProperty(String key, String value, Supplier<T> body) {
        System.setProperty(key, value);
        ConfigFactory.invalidateCaches();
        try {
            return body.get();
        } finally {
            System.clearProperty(key);
            ConfigFactory.invalidateCaches();
        }
    }
}
